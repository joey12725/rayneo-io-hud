"""Run with python -m unittest discover -s hud -p test_server.py."""
import io
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request
import sys

import server


def response(value):
    return io.BytesIO(json.dumps(value).encode())


def stream(*events):
    return io.BytesIO(b": keepalive\n\n" + b"".join(("event: " + name + "\ndata: " + json.dumps(body, ensure_ascii=False) + "\n\n").encode() for name, body in events))


class BridgeTests(unittest.TestCase):
    def test_native_session_schema_no_model_override_and_lifecycle(self):
        bridge = server.Bridge("http://example.invalid", "not-a-real-key")
        seen = []
        def open_api(path, body=None):
            seen.append((path, body))
            if path == "/api/sessions":
                return response({"session": {"id": "api_example"}})
            return stream(("run.started", {}), ("tool.started", {"tool_name": "delegate_task", "args": {"api_key": "never-forward"}}), ("assistant.delta", {"delta": "Café"}), ("assistant.completed", {"content": "Café!", "session_id": "api_rotated"}), ("run.completed", {}), ("done", {}))
        bridge.open = open_api
        emitted = []
        result = bridge.chat("hello", emit=emitted.append)
        self.assertEqual(seen, [("/api/sessions", {}), ("/api/sessions/api_example/chat/stream", {"message": "hello"})])
        self.assertEqual(result["text"], "Café!")
        self.assertEqual(result["session_id"], "api_rotated")
        self.assertEqual(emitted[2]["status"], "delegating")
        self.assertNotIn("never-forward", json.dumps(emitted))
        self.assertEqual(bridge._busy, set())

    def test_existing_session_and_truncated_stream(self):
        bridge = server.Bridge()
        bridge.open = lambda path, body: stream(("assistant.delta", {"delta": "partial"}))
        with self.assertRaisesRegex(server.BridgeError, "before the turn completed"):
            bridge.chat("hello", "api_existing")
        self.assertEqual(bridge._busy, set())

    def test_errors_and_busy(self):
        bridge = server.Bridge()
        bridge._busy.add("api_busy")
        with self.assertRaises(server.BridgeError) as ctx:
            bridge.chat("hello", "api_busy")
        self.assertEqual(ctx.exception.status, 409)
        bridge.open = lambda *args: stream(("error", {"message": "secret upstream detail"}))
        with self.assertRaisesRegex(server.BridgeError, "could not complete") as ctx:
            bridge.chat("hello", "api_other")
        self.assertNotIn("secret upstream", str(ctx.exception))

    def test_validation(self):
        for message, sid in [("", None), ("hi", "../secret"), ("hi", []), (None, None)]:
            with self.assertRaises(server.BridgeError):
                server.Bridge().chat(message, sid)
        with self.assertRaises(server.BridgeError) as ctx:
            server.Bridge().health()
        self.assertEqual(ctx.exception.status, 503)
        for url in ["file:///secret", "http://user:password@host", "http://host/?secret=1"]:
            with self.assertRaises(ValueError):
                server.Bridge(url)

    def test_audio_unavailable_and_command_no_shell(self):
        with self.assertRaises(server.BridgeError) as ctx:
            server.Bridge().audio(b"anything", "multipart/form-data; boundary=x")
        self.assertEqual(ctx.exception.status, 501)
        command = [sys.executable, "-c", "import pathlib,sys; assert pathlib.Path(sys.argv[1]).read_bytes()==b'voice'; print('A transcription')", "{audio}"]
        bridge = server.Bridge(transcriber=command)
        body = b'--x\r\nContent-Disposition: form-data; name="audio"; filename="evil; command.webm"\r\nContent-Type: audio/webm\r\n\r\nvoice\r\n--x--\r\n'
        self.assertEqual(bridge.audio(body, "multipart/form-data; boundary=x")["text"], "A transcription")
        with self.assertRaises(server.BridgeError):
            bridge.audio(b"bad", "multipart/form-data; boundary=x")

    def test_sse_multiline_and_bound(self):
        parsed = list(server.sse_events(io.BytesIO(b'event: done\r\ndata: {"a":\r\ndata: 1}\r\n\r\n')))
        self.assertEqual(parsed, [("done", {"a": 1})])
        with self.assertRaises(server.BridgeError):
            list(server.sse_events(io.BytesIO(b"x" * (server.MAX_EVENT + 1))))


class HTTPTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        root = Path(self.folder.name)
        (root / "hud").mkdir()
        (root / "hud/index.html").write_text("HUD")
        (root / "hud/.key.json").write_text("secret")
        (root / "outside.json").write_text("secret")
        self.bridge = server.Bridge()
        self.server = server.make_server(self.bridge, 0, root)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.folder.cleanup()

    def request(self, path, data=None, headers=None):
        return urllib.request.urlopen(urllib.request.Request(self.base + path, data=data, headers=headers or {}))

    def test_static_paths_and_same_origin(self):
        with self.request("/hud/") as response:
            self.assertEqual(response.read(), b"HUD")
        for path in ["/hud/../outside.json", "/hud/%2e%2e/outside.json", "/hud/.key.json", "/hud/server.py", "/outside.json"]:
            with self.assertRaises(urllib.error.HTTPError) as ctx:
                self.request(path)
            self.assertEqual(ctx.exception.code, 404)
        for headers in [{"Origin": "https://evil.example"}, {"Host": "evil.example"}, {"Sec-Fetch-Site": "cross-site"}]:
            with self.assertRaises(urllib.error.HTTPError) as ctx:
                self.request("/api/chat", b'{}', headers)
            self.assertEqual(ctx.exception.code, 403)

    def test_json_and_ndjson_contract(self):
        def chat(message, session_id, emit):
            self.assertEqual(message, "hello")
            emit({"type": "session", "session_id": "api_test"})
            emit({"type": "delta", "text": "Hi"})
            return {"type": "result", "text": "Hi", "session_id": "api_test", "events": []}
        self.bridge.chat = chat
        with self.request("/api/chat", b'{"message":"hello"}', {"Content-Type": "application/json"}) as response:
            self.assertEqual(json.load(response)["text"], "Hi")
        with self.request("/api/chat", b'{"message":"hello"}', {"Content-Type": "application/json", "Accept": "application/x-ndjson"}) as response:
            events = [json.loads(line) for line in response]
            self.assertEqual([event["type"] for event in events], ["session", "delta", "result"])

    def test_bad_json_and_request_limit(self):
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            self.request("/api/chat", b'[]', {"Content-Type": "application/json"})
        self.assertEqual(ctx.exception.code, 400)
        with patch.object(server, "MAX_BODY", 1):
            with self.assertRaises(urllib.error.HTTPError) as ctx:
                self.request("/api/chat", b'{}', {"Content-Type": "application/json"})
            self.assertEqual(ctx.exception.code, 413)


if __name__ == "__main__":
    unittest.main()
