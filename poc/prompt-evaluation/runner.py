"""Plan, score and record the POC-03 DeepSeek experiment."""
import argparse
from collections import Counter, defaultdict
import csv
from dataclasses import dataclass
from datetime import UTC, datetime
from email.utils import parsedate_to_datetime
import fcntl
import hashlib
from importlib.metadata import PackageNotFoundError, version
import json
import logging
import os
from pathlib import Path
import random
import re
from statistics import median
import sys
from time import perf_counter, sleep

from deepseek import BASE_URL, DeepSeek, Reply

ROOT = Path(__file__).resolve().parent
FILES = ("config.json", "cases.json", "runner.py", "deepseek.py", "requirements.txt")
USD = 1_000_000_000
VALID_ANSWERS = {"PASS", "WRONG_ANSWER"}
TRANSIENT = {429, 500, 502, 503, 504}


@dataclass(frozen=True)
class Cell:
    experiment: str
    group: str
    case: str
    repeat: int
    messages: list
    expected: str | None = None

    @property
    def id(self):
        return f"{self.experiment}/{self.group}/{self.case}/{self.repeat}"


def role(name, content):
    return {"role": name, "content": content}


def plan(cases, config):
    words = cases["three_words"]
    mapping = cases["mapping"]
    prompts, inputs = words["prompts"], words["inputs"]
    result = [
        Cell("SMOKE", group, case, 1, [role("system", prompts[group]), role("user", inputs[case])])
        for group, case in (("A", "F01"), ("C", "F02"))
    ]
    main = []
    for group, prompt in prompts.items():
        for case, text in inputs.items():
            for repeat in range(1, config["repeats"]["T1"] + 1):
                main.append(Cell("T1", group, case, repeat, [role("system", prompt), role("user", text)]))
    for group, prompt in mapping["prompts"].items():
        for case, item in mapping["inputs"].items():
            for repeat in range(1, config["repeats"]["T2"] + 1):
                messages = [role("system", prompt), role("user", item["input"])]
                main.append(Cell("T2", group, case, repeat, messages, item["expected"]))
    question, answer = words["examples"][0]
    prefixes = {
        "R1": [role("system", prompts["C"])],
        "R2": [role("user", prompts["C"])],
        "R3": [role("user", prompts["C"]), role("assistant", cases["roles"]["acknowledgement"])],
        "R4": [role("system", prompts["C"]), role("user", question), role("assistant", answer)],
    }
    for group, prefix in prefixes.items():
        for case in cases["roles"]["inputs"]:
            for repeat in range(1, config["repeats"]["T3"] + 1):
                main.append(Cell("T3", group, case, repeat, [*prefix, role("user", inputs[case])]))
    random.Random(config["seed"]).shuffle(main)
    return result + main


def payload(config, cell):
    params = {key: config[key] for key in ("model", "thinking", "stream", "temperature", "max_tokens")}
    return {**params, "messages": cell.messages}


def reserve(config, cell):
    rates = config["peak_price_nusd_per_token"]
    message_bytes = len(json.dumps(cell.messages, ensure_ascii=False).encode("utf-8"))
    inputs = max(config["minimum_input_tokens_reserved"], message_bytes + 128)
    return inputs * rates["input_miss"] + config["max_tokens"] * rates["output"]


def utc_now():
    return datetime.now(UTC).isoformat()


def token_cost(config, body):
    source = body.get("usage")
    source = source if isinstance(source, dict) else {}
    keys = ("prompt_tokens", "completion_tokens", "total_tokens", "prompt_cache_hit_tokens",
            "prompt_cache_miss_tokens")
    usage = {key: source.get(key) for key in keys}
    usage = {key: value if type(value) is int and value >= 0 else None for key, value in usage.items()}
    inputs, outputs = usage["prompt_tokens"], usage["completion_tokens"]
    cached = usage["prompt_cache_hit_tokens"]
    cost = None
    if inputs is not None and outputs is not None:
        cached = cached if cached is not None and cached <= inputs else 0
        rate = config["peak_price_nusd_per_token"]
        cost = (inputs - cached) * rate["input_miss"] + cached * rate["input_hit"] + outputs * rate["output"]
    return usage, cost


def classify(config, cell, reply: Reply, secret=""):
    body = reply.body if isinstance(reply.body, dict) else {}
    choices = body.get("choices")
    choice = choices[0] if isinstance(choices, list) and choices and isinstance(choices[0], dict) else {}
    message = choice.get("message")
    message = message if isinstance(message, dict) else {}
    content, finish = message.get("content"), choice.get("finish_reason")
    usage, cost = token_cost(config, body)

    def safe_text(value):
        if not isinstance(value, str):
            return None
        return value.replace(secret, "[REDACTED]") if secret else value

    if reply.error == "TIMEOUT":
        result, reason = "TIMEOUT", "timeout"
    elif reply.status is not None and not 200 <= reply.status < 300:
        result, reason = "HTTP_ERROR", f"http_{reply.status}"
    elif reply.error or body.get("error") or not (reply.status and 200 <= reply.status < 300):
        result, reason = "PROTOCOL_ERROR", "invalid_or_missing_response"
    elif isinstance(content, str) and secret and secret in content:
        result, reason = "PROTOCOL_ERROR", "credential_reflected"
    elif finish == "length":
        result, reason = "TRUNCATED", "length"
    elif finish == "content_filter":
        result, reason = "CONTENT_FILTER", "content_filter"
    elif message.get("refusal"):
        result, reason = "REFUSAL", "explicit_refusal"
    elif finish != "stop" or not isinstance(content, str) or message.get("tool_calls"):
        result, reason = "PROTOCOL_ERROR", "invalid_choice"
    else:
        passed = content == cell.expected if cell.expected is not None else bool(
            re.fullmatch(r"[A-Za-z]+ [A-Za-z]+ [A-Za-z]+", content)
        )
        result, reason = ("PASS", "matched") if passed else ("WRONG_ANSWER", "rule_mismatch")
    return {
        "http_status": reply.status, "response_id": safe_text(body.get("id")),
        "returned_model": safe_text(body.get("model")), "finish_reason": safe_text(finish),
        "content": safe_text(content), "result": result, "reason": reason,
        "usage": usage, "estimated_cost_nusd": cost,
    }


def write_json(path, value):
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def read_events(path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()] if path.exists() else []


def journal(path, entry):
    with path.open("a", encoding="utf-8") as target:
        target.write(json.dumps(entry, ensure_ascii=False) + "\n")
        target.flush()
        os.fsync(target.fileno())


def snapshot(directory, cells, events):
    started = {row["attempt"]: row for row in events if row["event"] == "started"}
    finished = {row["attempt"]: row for row in events if row["event"] == "finished"}
    attempts = defaultdict(list)
    for attempt, start in started.items():
        attempts[start["cell_id"]].append((start, finished.get(attempt)))
    rows = []
    for cell in cells:
        history = attempts[cell.id]
        last = history[-1][1] if history else None
        result = last["result"] if last else ("UNKNOWN" if history else "NOT_RUN")
        rows.append({
            "cell_id": cell.id, "experiment": cell.experiment, "group": cell.group,
            "case": cell.case, "repeat": cell.repeat, "result": result,
            "attempts": len(history), "http_status": last["http_status"] if last else None,
            "last_latency_ms": last["latency_ms"] if last else None,
            "costs": [end["estimated_cost_nusd"] if end else None for _, end in history],
        })
    write_json(directory / "cells.json", rows)

    buckets = defaultdict(list)
    for row in rows:
        prefix = (row["experiment"], row["group"])
        buckets[(*prefix, "all")].append(row)
        buckets[(*prefix, row["case"])].append(row)
        buckets[(*prefix, f"repeat-{row['repeat']}")].append(row)
        if row["experiment"] == "T1":
            scope = "aligned" if row["case"] == "F01" else "conflict"
            buckets[(*prefix, scope)].append(row)
    columns = ("experiment", "group", "scope", "planned", "valid", "passed", "wrong_answer",
               "errors", "not_run", "http_accepted", "http_responses", "attempts",
               "pass_valid_pct", "pass_planned_pct", "whole_repeat_pass",
               "median_ms", "min_ms", "max_ms", "peak_cost_estimate_usd", "unknown_cost_attempts")
    summary = {}
    with (directory / "summary.csv").open("w", newline="", encoding="utf-8") as output:
        csv_rows = csv.writer(output)
        csv_rows.writerow(columns)
        for (experiment, group, scope), entries in sorted(buckets.items()):
            counts = Counter(row["result"] for row in entries)
            planned, passed = len(entries), counts["PASS"]
            valid = passed + counts["WRONG_ANSWER"]
            latencies = [row["last_latency_ms"] for row in entries if row["last_latency_ms"] is not None]
            costs = [cost for row in entries for cost in row["costs"]]
            unknown_costs = costs.count(None)
            estimates = [cost for cost in costs if cost is not None]
            accepted = sum(200 <= (row["http_status"] or 0) < 300 for row in entries)
            accepted_responses = sum(row["http_status"] is not None for row in entries)
            pass_valid = 100 * passed / valid if valid else None
            pass_planned = 100 * passed / planned
            whole_repeat = int(passed == planned) if scope.startswith("repeat-") and not counts["NOT_RUN"] else None
            summary[(experiment, group, scope)] = pass_valid
            csv_rows.writerow((experiment, group, scope, planned, valid, passed, counts["WRONG_ANSWER"],
                               planned - valid - counts["NOT_RUN"], counts["NOT_RUN"], accepted,
                               accepted_responses, len(costs), pass_valid, pass_planned, whole_repeat,
                               median(latencies) if latencies else None, min(latencies, default=None),
                               max(latencies, default=None), f"{sum(estimates) / USD:.9f}" if estimates else None,
                               unknown_costs))
    differences = {}
    for scope in ("all", "aligned", "conflict"):
        differences[scope] = {}
        for first, second in (("A", "B"), ("B", "C"), ("C", "D")):
            a, b = summary[("T1", first, scope)], summary[("T1", second, scope)]
            differences[scope][f"{first}_to_{second}_pp"] = None if a is None or b is None else b - a
    write_json(directory / "comparisons.json", differences)


def retry_delay(value):
    if value is None:
        return 1
    try:
        return max(0, int(value))
    except ValueError:
        try:
            return max(0, (parsedate_to_datetime(value) - datetime.now(UTC)).total_seconds())
        except (ValueError, TypeError, OverflowError):
            return 61


def budget_allows(config, attempts, total, next_cost):
    return attempts < config["attempt_limit"] and total + next_cost <= config["budget_nusd"]


def run_cells(config, cells, path, call, phase, secret, sleeper=sleep, *, single_smoke=False):
    history = read_events(path)
    starts = [row for row in history if row["event"] == "started"]
    attempted = {row["cell_id"] for row in starts}
    spent = sum(row["reservation_nusd"] for row in starts)
    count = len(starts)
    errors_in_a_row = 0
    max_attempts = 1 if phase == "smoke" and single_smoke else 2
    for cell in cells:
        if (cell.experiment == "SMOKE") != (phase == "smoke") or cell.id in attempted:
            continue
        held = reserve(config, cell)
        for within_cell in range(1, max_attempts + 1):
            if not budget_allows(config, count, spent, held):
                return "BUDGET_LIMIT"
            count += 1
            spent += held
            journal(path, {
                "event": "started", "attempt": count, "cell_id": cell.id,
                "experiment_id": cell.experiment, "group_id": cell.group, "case_id": cell.case,
                "repeat": cell.repeat, "attempt_in_cell": within_cell, "messages": cell.messages,
                "timestamp_utc": utc_now(), "requested_model": config["model"], "reservation_nusd": held,
            })
            start = perf_counter()
            reply = call(payload(config, cell))
            result = classify(config, cell, reply, secret)
            journal(path, {"event": "finished", "attempt": count, "cell_id": cell.id,
                           "latency_ms": round(1000 * (perf_counter() - start), 2), **result})
            if reply.status in TRANSIENT and within_cell < max_attempts:
                delay = retry_delay(reply.retry_after)
                if delay > 60:
                    return "RETRY_AFTER_EXCEEDS_WAIT_LIMIT"
                if budget_allows(config, count, spent, held):
                    sleeper(delay)
                    continue
            break
        if reply.status in {401, 402, 403}:
            return "AUTH_OR_BALANCE"
        known_role_rejection = cell.experiment == "T3" and cell.group == "R2" and reply.status in {400, 422}
        if (reply.status in {400, 404, 422} and not known_role_rejection) or (
            reply.status is not None and 300 <= reply.status < 400
        ):
            return "REVIEW_REQUEST_PARAMETERS"
        if phase == "smoke" and (result["result"] not in VALID_ANSWERS or result["estimated_cost_nusd"] is None):
            return "SMOKE_RESPONSE_INVALID"
        errors_in_a_row = 0 if result["result"] in VALID_ANSWERS or known_role_rejection else errors_in_a_row + 1
        if errors_in_a_row >= 3:
            return "REPEATED_FAILURES"
    return "COMPLETE"


def fingerprint():
    return hashlib.sha256(b"\0".join((ROOT / name).read_bytes() for name in FILES)).hexdigest()


def load_inputs():
    return tuple(json.loads((ROOT / name).read_text(encoding="utf-8")) for name in FILES[:2])


def tool_versions():
    installed = {"python": sys.version.split()[0]}
    for name in ("openai", "httpx"):
        try:
            installed[name] = version(name)
        except PackageNotFoundError:
            installed[name] = None
    return installed


def prepare(directory, config, cells, live):
    manifest = directory / "manifest.json"
    sha = fingerprint()
    tools = tool_versions()
    planned = dict(Counter(c.experiment for c in cells))
    if manifest.exists() and live:
        prior = json.loads(manifest.read_text(encoding="utf-8"))
        if prior["fingerprint_sha256"] != sha or prior["tools"] != tools or prior["planned"] != planned:
            raise ValueError("Run configuration, source, plan or tool versions changed")
        return
    write_json(manifest, {"run_id": directory.name, "fingerprint_sha256": sha, "created_utc": utc_now(),
                          "execution": "LIVE" if live else "DRY_RUN", "base_url": BASE_URL,
                          "config": config, "tools": tools, "planned": planned,
                          "planned_reserved_nusd": sum(reserve(config, cell) for cell in cells)})
    write_json(directory / "plan.json", [{"cell_id": c.id, "payload": payload(config, c),
                                         "expected_exact": c.expected, "reservation_nusd": reserve(config, c)}
                                        for c in cells])


def smoke_passed(cells, events):
    ids = {cell.id for cell in cells if cell.experiment == "SMOKE"}
    valid = {row["cell_id"] for row in events if row["event"] == "finished" and
             row["result"] in VALID_ANSWERS and row["estimated_cost_nusd"] is not None}
    return ids <= valid


def read_key(stream):
    line = stream.readline(4096)
    key = line.strip()
    if len(line) == 4096 or not re.fullmatch(r"[!-~]+", key):
        raise ValueError("Expected a single non-empty API Key without spaces or a label")
    return key


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["preflight", "dry-run", "smoke", "run", "all", "resume", "summary"])
    parser.add_argument("--run-id", default="deepseek-flash")
    parser.add_argument("--allow-live", action="store_true")
    parser.add_argument("--key-stdin", action="store_true", help="Read the Key once from a private pipe")
    parser.add_argument("--single-smoke", action="store_true", help="Use only A/F01 for smoke, without retry")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", args.run_id):
        parser.error("Run ID must be a simple filename")
    config, cases = load_inputs()
    cells = plan(cases, config)
    if args.single_smoke:
        cells = [cell for cell in cells if cell.experiment != "SMOKE" or cell.group == "A"]
    key = os.environ.get("DEEPSEEK_API_KEY", "").strip()
    if args.command == "preflight":
        tools = tool_versions()
        ready = bool(key and tools["openai"] and tools["httpx"])
        print(json.dumps({"ready": ready, "key_configured": bool(key), "tools": tools,
                          "planned": dict(Counter(cell.experiment for cell in cells)),
                          "reserved_usd_no_retries": sum(reserve(config, cell) for cell in cells) / USD}))
        return 0 if ready else 2
    if args.command == "dry-run":
        directory = ROOT / "results" / "preflight"
        directory.mkdir(parents=True, exist_ok=True)
        prepare(directory, config, cells, live=False)
        snapshot(directory, cells, [])
        print("DRY_RUN: 0 HTTP attempts; all cells NOT_RUN")
        return 0

    directory = ROOT / "results" / "live" / args.run_id
    if args.command == "summary" and not directory.is_dir():
        parser.error("Run directory does not exist")
    if args.command != "summary":
        if not args.allow_live:
            parser.error("Live calls require --allow-live")
        if args.key_stdin:
            try:
                key = read_key(sys.stdin)
            except ValueError as error:
                parser.error(str(error))
        if not key:
            parser.error("DeepSeek API Key is missing")
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / "attempts.jsonl"
    with (directory / ".lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            prepare(directory, config, cells, live=True)
            events = read_events(path)
            if args.command == "summary":
                snapshot(directory, cells, events)
                return 0
            logging.disable(logging.CRITICAL)
            api = DeepSeek(key, config["timeout_seconds"])
            try:
                phase = "smoke" if args.command == "smoke" or (
                    args.command in {"all", "resume"} and not smoke_passed(cells, events)
                ) else "main"
                if phase == "main" and not smoke_passed(cells, events):
                    outcome = "SMOKE_REQUIRED"
                else:
                    outcome = run_cells(config, cells, path, api, phase, key, single_smoke=args.single_smoke)
                if phase == "smoke" and outcome == "COMPLETE" and not smoke_passed(cells, read_events(path)):
                    outcome = "SMOKE_RESPONSE_INVALID"
                if phase == "smoke" and outcome == "COMPLETE" and args.command in {"all", "resume"}:
                    print("SMOKE: COMPLETE", flush=True)
                    phase = "main"
                    outcome = run_cells(config, cells, path, api, phase, key)
            finally:
                api.close()
                snapshot(directory, cells, read_events(path))
            print(f"{phase.upper()}: {outcome} ({directory.relative_to(ROOT)})")
            return 0 if outcome == "COMPLETE" else 2
        except (OSError, ValueError, ImportError):
            print("Run blocked: check exclusive lock, journal, tool versions and configuration.", file=sys.stderr)
            return 2


if __name__ == "__main__":
    raise SystemExit(main())
