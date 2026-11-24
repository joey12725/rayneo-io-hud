"""Loopback HUD bridge for Hermes native sessions. Python 3.10+, no dependencies."""
from __future__ import annotations

import argparse
from email.parser import BytesParser
from email.policy import default
import http.server
import json
import mimetypes
import os
from pathlib import Path
import re
import subprocess
import tempfile
import threading
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
MAX_BODY = 8 * 1024 * 1024
MAX_EVENT = 2 * 1024 * 1024
SID = re.compile(r"[A-Za-z0-9_-]{1,200}\Z")


class BridgeError(Exception):
    def __init__(self, status, message):
        self.status, self.message = status, message
        super().__init__(message)


def sse_events(response):
    """Parse bounded SSE records, including UTF-8, multiline data and keepalives."""
    name, data, size = "message", [], 0
    while True:
        raw = response.readline(MAX_EVENT + 1)
        if not raw:
            if data:
                yield name, json.loads("\n".join(data))
            return
        size += len(raw)
        if size > MAX_EVENT:
            raise BridgeError(502, "Hermes sent an oversized event.")
        line = raw.decode("utf-8").rstrip("\r\n")
        if not line:
            if data:
                yield name, json.loads("\n".join(data))
            name, data, size = "message", [], 0
        elif line.startswith("event:"):
            name = line[6:].strip()
        elif line.startswith("data:"):
            data.append(line[5:].lstrip(" "))


def tool_status(name):
    name = name.lower()
    if any(s in name for s in ("delegate", "subagent", "spawn")):
        return "delegating"
    if any(s in name for s in ("search", "browse", "web_")):
        return "searching"
    if any(s in name for s in ("memory", "recall")):
        return "remembering"
    if any(s in name for s in ("write", "save")):
        return "saving"
    return "working"


class Bridge:
    def __init__(self, url="", key="", transcriber=None, timeout=300):
        self.url = url.rstrip("/")
        self.key = key
        self.transcriber = transcriber
        self.timeout = timeout
        self._lock = threading.Lock()
        self._busy = set()
        if self.url:
            parsed = urllib.parse.urlsplit(self.url)
            if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
                raise ValueError("HERMES_URL must be an HTTP(S) base URL without credentials or query.")

    def open(self, path, body=None):
        if not self.url:
            raise BridgeError(503, "Set HERMES_URL and HERMES_API_KEY (or HERMES_API_KEY_FILE) on the local bridge.")
        if not self.key:
            raise BridgeError(503, "The local bridge needs HERMES_API_KEY or HERMES_API_KEY_FILE.")
        headers = {"Authorization": "Bearer " + self.key, "Accept": "text/event-stream, application/json"}
        payload = None if body is None else json.dumps(body).encode()
        if payload is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self.url + path, data=payload, headers=headers)
        try:
            # Do not follow a redirect carrying the API credential to another origin.
            class NoRedirect(urllib.request.HTTPRedirectHandler):
                def redirect_request(self, *args, **kwargs):
                    return None
            return urllib.request.build_opener(NoRedirect).open(request, timeout=self.timeout)
        except urllib.error.HTTPError as exc:
            status = exc.code
            exc.close()
            messages = {401: "Hermes rejected the bridge API key.", 403: "Hermes denied this request.", 404: "Hermes session or endpoint was not found.", 409: "Hermes session is already busy.", 429: "Hermes is busy; retry later."}
            raise BridgeError(status if status in messages else 502, messages.get(status, "Hermes returned an upstream error.")) from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise BridgeError(502, "Cannot reach Hermes. Check HERMES_URL or your SSH tunnel.") from None

    def health(self):
        with self.open("/health") as response:
            response.read(65536)
        return {"ok": True, "transcription": bool(self.transcriber), "voice": bool(self.transcriber), "voice_mode": "local-command" if self.transcriber else "browser-or-unavailable"}

    def chat(self, message, session_id=None, emit=lambda event: None):
        if not isinstance(message, str) or not message.strip() or len(message) > 32000:
            raise BridgeError(400, "message must be nonempty text of at most 32000 characters.")
        if session_id is not None and (not isinstance(session_id, str) or not SID.fullmatch(session_id)):
            raise BridgeError(400, "Invalid session_id.")
        if not session_id:
            with self.open("/api/sessions", {}) as response:
                created = json.loads(response.read(MAX_EVENT + 1))
            session_id = created.get("session", {}).get("id")
            if not isinstance(session_id, str) or not SID.fullmatch(session_id):
                raise BridgeError(502, "Hermes did not return a valid session ID.")
        with self._lock:
            if session_id in self._busy:
                raise BridgeError(409, "This conversation already has a running turn.")
            self._busy.add(session_id)
        reserved_session_id = session_id
        events, text, completed, terminal = [], "", False, False
        try:
            emit({"type": "session", "session_id": session_id})
            with self.open("/api/sessions/" + session_id + "/chat/stream", {"message": message}) as response:
                for name, payload in sse_events(response):
                    if not isinstance(payload, dict):
                        raise BridgeError(502, "Hermes sent an invalid event.")
                    effective = payload.get("session_id")
                    if isinstance(effective, str) and SID.fullmatch(effective):
                        session_id = effective
                    if name == "assistant.delta":
                        delta = payload.get("delta", "")
                        if isinstance(delta, str):
                            text += delta
                            emit({"type": "delta", "text": delta, "session_id": session_id})
                    elif name == "assistant.completed":
                        text = payload.get("content", "")
                        if not isinstance(text, str):
                            raise BridgeError(502, "Hermes returned unsupported response content.")
                        completed = True
                    elif name in ("error", "run.failed", "run.cancelled"):
                        raise BridgeError(502, "Hermes could not complete this turn (" + name + ").")
                    elif name == "run.queued":
                        raise BridgeError(503, "Hermes queued delivery to another active session; no final response is available yet.")
                    elif name == "done":
                        terminal = True
                        break
                    elif name in ("run.started", "tool.started", "tool.completed", "tool.failed", "tool.progress", "assistant.commentary", "approval.request", "run.completed"):
                        tool = str(payload.get("tool_name") or "")[:120]
                        status = ("waiting" if name == "approval.request" else "idle" if name == "run.completed" else "speaking" if name == "assistant.commentary" else tool_status(tool) if name.startswith("tool.") else "thinking")
                        event = {"type": "event", "event": name, "status": status, "session_id": session_id}
                        # Deliberately omit runtime credentials, tool arguments and transcript dumps.
                        if tool:
                            event["tool_name"] = tool
                        if name == "approval.request":
                            event["message"] = "Approval required. Review this request in your existing Hermes client."
                        events.append(event)
                        emit(event)
                if not completed or not terminal:
                    raise BridgeError(502, "Hermes stream ended before the turn completed. Do not resend blindly; the turn may have run.")
            return {"type": "result", "text": text, "session_id": session_id, "events": events}
        except (ValueError, UnicodeError, TimeoutError, OSError):
            raise BridgeError(502, "Hermes stream was interrupted or malformed. Check the session before retrying.") from None
        finally:
            with self._lock:
                # Session compression can rotate IDs during the turn.
                self._busy.discard(reserved_session_id)

    def audio(self, body, content_type):
        if not self.transcriber:
            raise BridgeError(501, "This Hermes API has no audio transcription endpoint. Use browser speech recognition or configure HERMES_TRANSCRIBE_COMMAND as a JSON argv array.")
        if not content_type.lower().startswith("multipart/form-data;"):
            raise BridgeError(415, "Expected multipart/form-data with an audio part.")
        message = BytesParser(policy=default).parsebytes(("Content-Type: " + content_type + "\r\nMIME-Version: 1.0\r\n\r\n").encode() + body)
        if not message.is_multipart():
            raise BridgeError(400, "Invalid audio multipart body.")
        audio = next((p for p in message.iter_parts() if p.get_param("name", header="content-disposition") == "audio"), None)
        if audio is None:
            raise BridgeError(400, "Missing audio part.")
        suffixes = {"audio/webm": ".webm", "audio/ogg": ".ogg", "audio/wav": ".wav", "audio/x-wav": ".wav", "audio/mp4": ".m4a", "audio/mpeg": ".mp3"}
        suffix = suffixes.get(audio.get_content_type())
        if not suffix:
            raise BridgeError(415, "Unsupported audio format.")
        data = audio.get_payload(decode=True)
        if not data:
            raise BridgeError(400, "Empty audio part.")
        with tempfile.TemporaryDirectory(prefix="hud-voice-") as folder:
            path = Path(folder) / ("recording" + suffix)
            path.write_bytes(data)
            argv = [s.replace("{audio}", str(path)) for s in self.transcriber]
            try:
                # shell=False: uploaded filenames and audio never become commands.
                result = subprocess.run(argv, shell=False, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=90, check=True)
                text = result.stdout.decode("utf-8").strip()
            except (OSError, subprocess.SubprocessError, UnicodeError):
                raise BridgeError(502, "Local audio transcription failed.") from None
        if not text or len(text) > 32000:
            raise BridgeError(502, "Local transcriber returned empty or oversized text.")
        return {"text": text, "transcript": text}


class Handler(http.server.BaseHTTPRequestHandler):
    server_version = "HermesHUD/1"

    def log_message(self, *_):
        pass  # Requests may contain private conversation/session data.

    def headers_common(self):
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Cross-Origin-Resource-Policy", "same-origin")
        self.send_header("X-Frame-Options", "DENY")

    def json_response(self, code, value):
        data = json.dumps(value, ensure_ascii=False).encode()
        self.send_response(code)
        self.headers_common()
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def same_origin(self):
        host = self.headers.get("Host", "")
        expected = self.server.server_address
        allowed = {f"127.0.0.1:{expected[1]}", f"localhost:{expected[1]}"}
        if host not in allowed:
            raise BridgeError(403, "Invalid local Host header.")
        origin = self.headers.get("Origin")
        if origin is not None and origin != "http://" + host:
            raise BridgeError(403, "Cross-origin requests are not allowed.")
        if self.headers.get("Sec-Fetch-Site") == "cross-site":
            raise BridgeError(403, "Cross-site requests are not allowed.")

    def body(self):
        if self.headers.get("Transfer-Encoding"):
            raise BridgeError(400, "Chunked uploads are not supported.")
        try:
            length = int(self.headers.get("Content-Length", "-1"))
        except ValueError:
            raise BridgeError(400, "Invalid Content-Length.") from None
        if length < 0 or length > MAX_BODY:
            raise BridgeError(413, "Request body must be at most 8 MiB.")
        self.connection.settimeout(30)
        data = self.rfile.read(length)
        if len(data) != length:
            raise BridgeError(400, "Incomplete request body.")
        return data

    def do_GET(self):
        try:
            self.same_origin()
            path = urllib.parse.urlsplit(self.path).path
            if path == "/api/health":
                self.json_response(200, self.server.bridge.health())
                return
            if path == "/api/capabilities":
                self.json_response(200, {"transcription": bool(self.server.bridge.transcriber), "streaming": True})
                return
            if path.startswith("/api/"):
                raise BridgeError(404, "Unknown endpoint.")
            decoded = urllib.parse.unquote(path)
            if "\\" in decoded or "\x00" in decoded or any(part.startswith(".") for part in decoded.split("/")):
                raise BridgeError(404, "File not found.")
            if decoded == "/":
                decoded = "/hud/index.html"
            elif decoded == "/hud/":
                decoded = "/hud/index.html"
            target = (self.server.root / decoded.lstrip("/")).resolve()
            # Serve only product assets, never source/config/git/secrets elsewhere in the repo.
            rel = target.relative_to(self.server.root)
            allowed = rel.parts and rel.parts[0] in ("hud", "simulator")
            if not allowed or target.suffix.lower() not in (".html", ".js", ".css", ".json", ".png", ".gif", ".webp", ".ico", ".bin", ".mp4", ".woff2") or not target.is_file():
                raise BridgeError(404, "File not found.")
            self.send_response(200)
            self.headers_common()
            self.send_header("Content-Type", mimetypes.guess_type(target)[0] or "application/octet-stream")
            self.send_header("Content-Length", str(target.stat().st_size))
            self.end_headers()
            with target.open("rb") as source:
                while chunk := source.read(65536):
                    self.wfile.write(chunk)
        except BridgeError as exc:
            self.json_response(exc.status, {"error": exc.message})
        except (ValueError, OSError):
            self.json_response(404, {"error": "File not found."})

    def do_POST(self):
        streaming = False
        try:
            self.same_origin()
            path = urllib.parse.urlsplit(self.path).path
            body = self.body()
            if path == "/api/audio":
                self.json_response(200, self.server.bridge.audio(body, self.headers.get("Content-Type", "")))
                return
            if path != "/api/chat":
                raise BridgeError(404, "Unknown endpoint.")
            if self.headers.get_content_type() != "application/json":
                raise BridgeError(415, "Expected application/json.")
            try:
                payload = json.loads(body)
            except (ValueError, UnicodeError):
                raise BridgeError(400, "Invalid JSON.") from None
            if not isinstance(payload, dict):
                raise BridgeError(400, "Expected a JSON object.")
            wants_stream = "application/x-ndjson" in self.headers.get("Accept", "")

            def emit(event):
                nonlocal streaming
                if not wants_stream:
                    return
                if not streaming:
                    self.send_response(200)
                    self.headers_common()
                    self.send_header("Content-Type", "application/x-ndjson; charset=utf-8")
                    self.end_headers()
                    streaming = True
                self.wfile.write(json.dumps(event, ensure_ascii=False).encode() + b"\n")
                self.wfile.flush()

            result = self.server.bridge.chat(payload.get("message"), payload.get("session_id"), emit)
            if wants_stream:
                emit(result)
            else:
                self.json_response(200, result)
        except BridgeError as exc:
            if streaming:
                self.wfile.write(json.dumps({"type": "error", "error": exc.message}).encode() + b"\n")
            else:
                self.json_response(exc.status, {"error": exc.message})
        except (BrokenPipeError, ConnectionResetError):
            pass
        except (OSError, ValueError):
            if not streaming:
                self.json_response(400, {"error": "Invalid or incomplete request."})


def make_server(bridge, port=8765, root=ROOT):
    server = http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler)
    server.bridge, server.root = bridge, Path(root).resolve()
    server.daemon_threads = True
    return server


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    key = os.environ.get("HERMES_API_KEY", "")
    keyfile = os.environ.get("HERMES_API_KEY_FILE")
    if not key and keyfile:
        key = Path(keyfile).read_text(encoding="utf-8").strip()
    command = json.loads(os.environ.get("HERMES_TRANSCRIBE_COMMAND", "null"))
    if command is not None and (not isinstance(command, list) or not command or not all(isinstance(s, str) for s in command) or not any("{audio}" in s for s in command)):
        parser.error("HERMES_TRANSCRIBE_COMMAND must be a JSON argv array containing {audio}.")
    bridge = Bridge(os.environ.get("HERMES_URL", ""), key, command)
    server = make_server(bridge, args.port)
    print(f"Hermes HUD: http://127.0.0.1:{server.server_port}/hud/")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
