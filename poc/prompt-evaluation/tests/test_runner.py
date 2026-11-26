import json
from collections import Counter
from io import StringIO
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

import httpx

from deepseek import DeepSeek, Reply
from runner import (budget_allows, classify, journal, load_inputs, payload, plan, read_key,
                    read_events, reserve, run_cells, snapshot)

CONFIG, CASES = load_inputs()
CELLS = plan(CASES, CONFIG)
KEY = "synthetic-test-credential"


def completion(content, finish="stop", **extra):
    return Reply(200, {
        "choices": [{"message": {"content": content, **extra}, "finish_reason": finish}],
        "usage": {"prompt_tokens": 10, "completion_tokens": 5, "prompt_cache_hit_tokens": 4},
        "model": "mock",
    })


class TestPlan(unittest.TestCase):
    def test_counts_unique_and_repeatable(self):
        self.assertEqual(Counter(c.experiment for c in CELLS), {"SMOKE": 2, "T1": 72, "T2": 12, "T3": 24})
        self.assertEqual(len({c.id for c in CELLS}), 110)
        self.assertEqual(CELLS, plan(CASES, CONFIG))
        self.assertEqual(sum(reserve(CONFIG, c) for c in CELLS), 66_792_000)

    def test_roles_and_common_parameters(self):
        for group, roles in {
            "R1": ["system", "user"], "R2": ["user", "user"],
            "R3": ["user", "assistant", "user"], "R4": ["system", "user", "assistant", "user"],
        }.items():
            cell = next(c for c in CELLS if c.group == group)
            self.assertEqual([m["role"] for m in cell.messages], roles)
            data = payload(CONFIG, cell)
            self.assertEqual((data["thinking"], data["temperature"], data["max_tokens"], data["stream"]),
                             ({"type": "disabled"}, 0.3, 256, False))


class TestJudge(unittest.TestCase):
    def test_exact_ascii_words(self):
        for text, expected in [
            ("Foo bar baz", "PASS"), ("a B z", "PASS"), ("I love you.", "WRONG_ANSWER"),
            ("a  b c", "WRONG_ANSWER"), ("a b c\n", "WRONG_ANSWER"),
            (" a b c", "WRONG_ANSWER"), ("a b c ", "WRONG_ANSWER"),
            ("I don't know", "WRONG_ANSWER"), ("a\tb c", "WRONG_ANSWER"),
            ("a b 中文", "WRONG_ANSWER"), ("`a b c`", "WRONG_ANSWER"),
            ("a\u00a0b c", "WRONG_ANSWER"), ("", "WRONG_ANSWER"), (None, "PROTOCOL_ERROR"),
        ]:
            with self.subTest(text=text):
                row = classify(CONFIG, CELLS[0], completion(text))
                self.assertEqual((row["result"], row["content"]), (expected, text))

    def test_mapping_exact(self):
        cell = next(c for c in CELLS if c.case == "M02")
        self.assertEqual(classify(CONFIG, cell, completion("green"))["result"], "PASS")
        for value in ("red", "Green", "green\n"):
            self.assertEqual(classify(CONFIG, cell, completion(value))["result"], "WRONG_ANSWER")

    def test_transport_protocol_and_refusal(self):
        for reply, status in [
            (Reply(401), "HTTP_ERROR"), (Reply(error="TIMEOUT"), "TIMEOUT"),
            (Reply(200, []), "PROTOCOL_ERROR"), (Reply(200, {"choices": [None]}), "PROTOCOL_ERROR"),
            (completion("a b c", "length"), "TRUNCATED"),
            (completion(None, "content_filter"), "CONTENT_FILTER"),
            (completion(None, refusal="explicit"), "REFUSAL"),
        ]:
            self.assertEqual(classify(CONFIG, CELLS[0], reply)["result"], status)

    def test_redaction_usage_and_hidden_reasoning(self):
        reply = completion(f"reflected {KEY}", reasoning_content="hidden-test-value")
        row = classify(CONFIG, CELLS[0], reply, KEY)
        serialized = json.dumps(row)
        self.assertNotIn(KEY, serialized)
        self.assertNotIn("hidden-test-value", serialized)
        self.assertEqual(row["reason"], "credential_reflected")
        self.assertEqual(row["estimated_cost_nusd"], 7_824)
        self.assertIsNone(classify(CONFIG, CELLS[0], Reply(403))["estimated_cost_nusd"])


class TestExecution(unittest.TestCase):
    def run_fake(self, replies, config=CONFIG):
        temporary = TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        path = Path(temporary.name) / "attempts.jsonl"
        calls = []
        delays = []
        result = run_cells(config, CELLS, path, lambda body: calls.append(body) or next(replies),
                           "smoke", KEY, delays.append)
        return path, calls, delays, result

    def test_retry_count_and_retry_after(self):
        path, calls, delays, result = self.run_fake(iter([
            Reply(429, retry_after="2"), completion("a b c"), completion("a b c"),
        ]))
        self.assertEqual((len(calls), delays, result), (3, [2], "COMPLETE"))
        self.assertEqual([r["result"] for r in read_events(path) if r["event"] == "finished"],
                         ["HTTP_ERROR", "PASS", "PASS"])

    def test_budget_blocks_before_send(self):
        cap = reserve(CONFIG, CELLS[0])
        self.assertFalse(budget_allows(CONFIG, 120, 0, cap))
        self.assertFalse(budget_allows(CONFIG, 0, CONFIG["budget_nusd"], cap))
        path, calls, _, outcome = self.run_fake(iter([completion("a b c")]), {**CONFIG, "budget_nusd": cap})
        self.assertEqual((len(calls), outcome, len(read_events(path))), (1, "BUDGET_LIMIT", 2))

    def test_auth_and_timeout_stop_without_retries(self):
        for reply in (Reply(401), Reply(402), Reply(403), Reply(error="TIMEOUT")):
            _, calls, _, outcome = self.run_fake(iter([reply]))
            self.assertEqual(len(calls), 1)
            self.assertNotEqual(outcome, "COMPLETE")

    def test_resume_keeps_previous_failure_and_never_replays_inflight_attempt(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "attempts.jsonl"
            journal(path, {"event": "started", "attempt": 1, "cell_id": CELLS[0].id,
                           "reservation_nusd": reserve(CONFIG, CELLS[0])})
            calls = []
            outcome = run_cells(CONFIG, CELLS, path, lambda body: calls.append(body) or completion("a b c"),
                                "smoke", KEY)
            self.assertEqual((outcome, calls), ("COMPLETE", [payload(CONFIG, CELLS[1])]))
            snapshot(path.parent, CELLS, read_events(path))
            rows = json.loads((path.parent / "cells.json").read_text())
            self.assertEqual([row["result"] for row in rows[:3]], ["UNKNOWN", "PASS", "NOT_RUN"])

    def test_r2_http_rejected_is_recorded_as_interface_error(self):
        r2 = next(c for c in CELLS if c.group == "R2")
        with TemporaryDirectory() as directory:
            path = Path(directory) / "attempts.jsonl"
            outcome = run_cells(CONFIG, [r2], path, lambda _: Reply(400), "main", KEY)
            self.assertEqual(outcome, "COMPLETE")
            self.assertEqual(read_events(path)[1]["result"], "HTTP_ERROR")

    def test_long_retry_after_pauses(self):
        _, calls, delays, outcome = self.run_fake(iter([Reply(429, retry_after="600")]))
        self.assertEqual((len(calls), delays, outcome), (1, [], "RETRY_AFTER_EXCEEDS_WAIT_LIMIT"))

    def test_single_smoke_never_retries(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "attempts.jsonl"
            calls = []
            outcome = run_cells(CONFIG, CELLS[:1], path, lambda body: calls.append(body) or Reply(429),
                                "smoke", KEY, single_smoke=True)
            self.assertEqual((len(calls), outcome), (1, "SMOKE_RESPONSE_INVALID"))
            self.assertEqual(len(read_events(path)), 2)

    def test_full_mock_matrix_writes_recomputable_artifacts(self):
        with TemporaryDirectory() as directory:
            output = Path(directory)
            path = output / "attempts.jsonl"

            def fake_model(body):
                user = body["messages"][-1]["content"]
                colors = {item["input"]: item["expected"] for item in CASES["mapping"]["inputs"].values()}
                return completion(colors.get(user, "one two three"))

            for phase in ("smoke", "main"):
                self.assertEqual(run_cells(CONFIG, CELLS, path, fake_model, phase, KEY), "COMPLETE")
            events = read_events(path)
            self.assertEqual(len(events), 220)
            snapshot(output, CELLS, events)
            rows = json.loads((output / "cells.json").read_text())
            self.assertEqual(Counter(row["result"] for row in rows), {"PASS": 110})
            self.assertEqual(json.loads((output / "comparisons.json").read_text())["all"]["B_to_C_pp"], 0.0)


class TestSdkMock(unittest.TestCase):
    def test_credential_stdin_rejects_empty_or_labelled_inputs(self):
        for text in ("", "  \n", f"label {KEY}\n", "a" * 4096):
            with self.subTest(length=len(text)), self.assertRaisesRegex(ValueError, "Expected a single"):
                read_key(StringIO(text))

    def test_requests_preserve_messages_and_parameters(self):
        cell = next(c for c in CELLS if c.group == "R2")
        def provider(request):
            self.assertEqual(str(request.url), "https://api.deepseek.com/chat/completions")
            self.assertEqual(request.headers["Authorization"], f"Bearer {KEY}")
            self.assertEqual(json.loads(request.content), payload(CONFIG, cell))
            return httpx.Response(200, json=completion(" a b c ").body)
        credential = read_key(StringIO(f" {KEY}\r\n"))
        api = DeepSeek(credential, 60, transport=httpx.MockTransport(provider))
        self.addCleanup(api.close)
        reply = api(payload(CONFIG, cell))
        result = classify(CONFIG, cell, reply)
        self.assertEqual((result["content"], result["result"]), (" a b c ", "WRONG_ANSWER"))

    def test_no_sdk_retries_or_redirects(self):
        for status in (307, 429, 503):
            calls = []
            def provider(request):
                calls.append(str(request.url))
                return httpx.Response(status, headers={"Location": "https://example.invalid/redirect"})
            api = DeepSeek(KEY, 60, transport=httpx.MockTransport(provider))
            self.addCleanup(api.close)
            reply = api(payload(CONFIG, CELLS[0]))
            self.assertEqual((reply.status, calls), (status, ["https://api.deepseek.com/chat/completions"]))
