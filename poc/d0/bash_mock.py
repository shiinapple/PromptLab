from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from tempfile import TemporaryDirectory
import json
import subprocess
import threading
import time

root = Path(__file__).resolve().parents[2]
goldens = json.loads((root / "content/problems/002/v1/fixtures/case-1.json").read_text())
raw = " \r\n" + goldens[0]["rawOutput"] + "\t\n"
response_bytes = json.dumps({"choices": [{"finish_reason": "stop", "message": {"content": raw}}]}).encode()
request = {"model": "local-mock", "messages": [{"role": "user", "content": "mock"}], "stream": False}
guide = (root / "docs/guides/D0_CLI.md").read_text().split("```bash\n", 1)[1].split("```", 1)[0]
calls = []
mode = "ok"

class Mock(BaseHTTPRequestHandler):
    def do_POST(self):
        received = self.rfile.read(int(self.headers["Content-Length"]))
        calls.append((mode, self.path, self.headers.get("Authorization"), json.loads(received)))
        status = {"ok": 200, "unauthorized": 401, "redirect": 302, "slow": 200, "broken": 200}[mode]
        self.send_response(status)
        if mode == "redirect":
            self.send_header("Location", "/unexpected")
        if mode == "slow":
            time.sleep(2)
        self.send_header("Content-Length", str(len(response_bytes) + (5 if mode == "broken" else 0)))
        try:
            self.end_headers()
            self.wfile.write(response_bytes)
        except BrokenPipeError:
            pass
    def log_message(self, *_):
        pass

with ThreadingHTTPServer(("127.0.0.1", 0), Mock) as server:
    threading.Thread(target=server.serve_forever, daemon=True).start()
    script = guide.replace("11434", str(server.server_port)).replace("--max-time 180", "--max-time 1")
    with TemporaryDirectory(prefix="promptlab-cli-") as temporary:
        work = Path(temporary)
        (work / "request.json").write_text(json.dumps(request))
        for mode, stdin, ok in [
            ("ok", "\n", True), ("ok", "mock-token\n", True), ("unauthorized", "\n", False),
            ("redirect", "\n", False), ("slow", "\n", False), ("broken", "\n", False),
        ]:
            before = set(work.glob("promptlab-run.*"))
            result = subprocess.run(["/bin/bash", "-c", script], cwd=work, input=stdin, text=True, capture_output=True, timeout=5)
            newdir, = set(work.glob("promptlab-run.*")) - before
            replies = list(newdir.glob("response.json"))
            assert len(replies) == int(ok) and (result.returncode == 0) == ok, result
            assert not (newdir / "body.tmp").exists()
            if ok:
                assert json.loads(replies[0].read_bytes())["choices"][0]["message"]["content"] == raw
            assert "mock-token" not in result.stdout + result.stderr
            assert calls[-1] == (mode, "/v1/chat/completions",
                                 "Bearer mock-token" if stdin != "\n" else None, request)
            print(f"{mode} auth={stdin != chr(10)} code={result.returncode} response={len(replies)}")
        assert len(calls) == 6
