"""glasses-notify core: turns one completed Hermes exchange into a POST /glasses/v1/notify/hermes body.

Pure Python standard library, no Hermes imports, so it is unit-tested with fakes (tests/test_glasses_notify.py).
__init__.py wires it to Hermes' pre_llm_call / post_llm_call hooks and to Hermes' session context.

Contract with the gateway (server/gateway/apis/glasses/lib/notify-hermes.mjs):
  {platform, chat, chatId, threadId, isDM, sender: owner|other|scheduler|system, senderName, inboundKind,
   delivered, job, prompt: {at, text}, response: {at, text}, turns: [...], sessionId, turnId}

Rules: never raise into Hermes, never block a turn (one daemon worker, bounded queue, short HTTP timeout), skip the
glasses channel (api_server sessions), subagent turns and Hermes' internal notes.
"""
from __future__ import annotations

import json
import logging
import queue
import re
import threading
import time
import urllib.request
from datetime import datetime, timezone
from typing import Any, Callable, Dict, List, Optional

logger = logging.getLogger("glasses-notify")

MAX_TURNS = 5
TURN_WINDOW_S = 4 * 3600
MAX_HISTORY = 40          # messages remembered per conversation
MAX_CONVERSATIONS = 200
DEFAULT_URL = "http://localhost:8080/glasses/v1/notify/hermes"
DEFAULT_SKIP = ("api_server",)
TOKEN_KEY = "_glasses_token"  # queue-only field (never posted): the bearer read on the hook's thread
# Never a conversation with the owner, whatever GLASSES_NOTIFY_SKIP_PLATFORMS says: delegate_task children (their parent
# reports the result) and the glasses channel itself (an echo of what they just saw on the lens).
ALWAYS_SKIP = ("subagent", "api_server")

# Injected by Hermes itself (compaction, retries, task lists): neither a prompt nor a conversational turn.
INTERNAL = re.compile(r"^\[(System|CONTEXT COMPACTION|Your active task list|STILL IN PROGRESS|System note)")
# Background work finishing (a process or delegated subagents): the assistant's reply answers an earlier request of the owner's.
BACKGROUND = re.compile(r"^\[(IMPORTANT: Background process|IMPORTANT: \d+ background process|ASYNC DELEGATION)")
CRON_DELIVERY = re.compile(r"^\[Cron delivery: ([^\]]*)\]\s*")
SENDER_TAG = re.compile(r"^\[([^\]\n\s]{1,64})\]\s*")
CRON_SESSION = re.compile(r"^cron_(.+?)_\d{8}_\d{6}")


def iso(ts: float) -> str:
    return datetime.fromtimestamp(ts, tz=timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def clip(value: Any, limit: int) -> str:
    text = value if isinstance(value, str) else ("" if value is None else str(value))
    return text if len(text) <= limit else text[: limit - 1] + "…"


def tail(value: str, limit: int) -> str:
    return value if len(value) <= limit else "…" + value[-(limit - 1):]


def as_text(value: Any) -> str:
    """Hermes passes a str, or a list of content parts for multimodal messages."""
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        parts = []
        for p in value:
            if isinstance(p, dict) and isinstance(p.get("text"), str):
                parts.append(p["text"])
            elif isinstance(p, str):
                parts.append(p)
        return "\n".join(parts)
    return "" if value is None else str(value)


def classify(text: str, platform: str, user_name: str, owner_names: List[str], is_dm: bool = False) -> Optional[Dict[str, Any]]:
    """Who sent the inbound message and what kind it is, or None for Hermes' internal notes.

    Hermes prefixes "[name] " only in shared multi-user sessions (never a DM), with the same name it binds as
    HERMES_SESSION_USER_NAME. So a leading "[...]" is a sender tag only outside DMs and, when the session names the
    user, only if it is that name; otherwise it is the message's own text ("[WIP] fix this" from the owner stays the owner's)."""
    if not text.strip() or INTERNAL.match(text):
        return None
    if platform == "cron":
        return {"sender": "scheduler", "inboundKind": "cron-prompt", "text": text}
    if BACKGROUND.match(text):
        return {"sender": "system", "inboundKind": "background-completion", "text": text}
    m = CRON_DELIVERY.match(text)
    if m:
        return {"sender": "scheduler", "inboundKind": "cron-delivery", "text": text[m.end():], "job": m.group(1)}
    names = {n.strip().lower() for n in owner_names if n and n.strip()}
    tag = None if is_dm else SENDER_TAG.match(text)
    if tag and user_name and tag.group(1).lower() != user_name.strip().lower():
        tag = None
    if tag:
        name = tag.group(1)
        owner = name.lower() in names
        return {"sender": "owner" if owner else "other", "senderName": None if owner else name, "inboundKind": "request", "text": text[tag.end():]}
    # Without configured owner names a named session user cannot be told apart, so it counts as the owner.
    if user_name and names and user_name.strip().lower() not in names:
        return {"sender": "other", "senderName": user_name, "inboundKind": "request", "text": text}
    return {"sender": "owner", "inboundKind": "request", "text": text}


def previous_turns(messages: List[Dict[str, Any]], target_at: float, max_turns: int = MAX_TURNS, window_s: float = TURN_WINDOW_S) -> List[Dict[str, Any]]:
    """Up to `max_turns` turns (runs of one speaker) before `target_at`, only those whose last message is within the window."""
    turns: List[Dict[str, Any]] = []
    cur = None
    for m in reversed(messages):
        if m["ts"] > target_at:
            continue
        if cur is None or cur["speaker"] != m["speaker"] or cur["isOwner"] != m["isOwner"]:
            if len(turns) >= max_turns or target_at - m["ts"] > window_s:
                break
            cur = {"speaker": m["speaker"], "isOwner": m["isOwner"], "messages": []}
            turns.append(cur)
        cur["messages"].append({"at": iso(m["ts"]), "text": clip(m["text"], 400)})
    for t in turns:
        t["messages"].reverse()
        t["messages"] = t["messages"][-8:]
    turns.reverse()
    return turns


class Notifier:
    """Holds per-conversation history and the delivery worker. All public methods swallow every exception."""

    def __init__(self, *, url: str = DEFAULT_URL, token: Callable[[], Optional[str]] = lambda: None,
                 session_env: Callable[[str], str] = lambda name: "", jobs: Callable[[], Dict[str, Dict[str, Any]]] = lambda: {},
                 post: Optional[Callable[[str, Dict[str, Any], str, float], Any]] = None, clock: Callable[[], float] = time.time,
                 skip_platforms=DEFAULT_SKIP, owner_names: Optional[List[str]] = None, assistant_name: str = "Hermes", timeout: float = 3.0,
                 queue_size: int = 50, start_worker: bool = True):
        self.url, self.token, self.session_env, self.jobs, self.clock = url, token, session_env, jobs, clock
        self.post = post or http_post
        self.skip = {p.strip().lower() for p in skip_platforms if p and p.strip()} | set(ALWAYS_SKIP)
        self.owner_names = list(owner_names or [])
        self.assistant_name = assistant_name or "Hermes"
        self.timeout = timeout
        self.history: Dict[str, List[Dict[str, Any]]] = {}
        self.starts: Dict[str, Dict[str, Any]] = {}
        self.lock = threading.Lock()
        self.queue: "queue.Queue[Dict[str, Any]]" = queue.Queue(maxsize=queue_size)
        self.dropped = 0
        self.warned_token = False
        if start_worker:
            threading.Thread(target=self._worker, name="glasses-notify", daemon=True).start()

    # ---- hooks ----

    def pre_llm_call(self, *, session_id: str = "", turn_id: str = "", platform: str = "", parent_session_id: str = "", **_: Any) -> None:
        try:
            with self.lock:
                self.starts[f"{session_id}:{turn_id}"] = {"ts": self.clock(), "parent": bool(parent_session_id)}
                if len(self.starts) > 500:
                    for k in list(self.starts)[:250]:
                        self.starts.pop(k, None)
        except Exception:  # never raise into Hermes
            logger.debug("glasses-notify pre_llm_call failed", exc_info=True)
        return None

    def post_llm_call(self, *, session_id: str = "", turn_id: str = "", user_message: Any = None, assistant_response: Any = None,
                      platform: str = "", **_: Any) -> None:
        try:
            body = self.build(session_id=session_id or "", turn_id=turn_id or "", user_message=user_message, assistant_response=assistant_response, platform=platform or "")
            if body is not None:
                # The token is read here, on the hook's thread: Hermes copies the turn's context (the profile's secret
                # scope) into it, while the delivery worker has no scope (an overlay-only secret would read as unset).
                body[TOKEN_KEY] = self._read_token()
                try:
                    self.queue.put_nowait(body)
                except queue.Full:
                    self.dropped += 1
        except Exception:
            logger.debug("glasses-notify post_llm_call failed", exc_info=True)
        return None

    # ---- building the body ----

    def env(self, name: str) -> str:
        try:
            return str(self.session_env(name) or "")
        except Exception:
            return ""

    def build(self, *, session_id: str, turn_id: str, user_message: Any, assistant_response: Any, platform: str) -> Optional[Dict[str, Any]]:
        bound = {self.env("HERMES_SESSION_PLATFORM").strip().lower(), self.env("HERMES_SESSION_SOURCE").strip().lower()}
        platform = (platform or self.env("HERMES_SESSION_PLATFORM") or self.env("HERMES_SESSION_SOURCE")).strip().lower()
        # The session's bound channel counts too: a glasses (api_server) session never reports, whatever the agent says.
        if not platform or platform in self.skip or bound & self.skip:
            with self.lock:
                self.starts.pop(f"{session_id}:{turn_id}", None)
            return None
        with self.lock:
            start = self.starts.pop(f"{session_id}:{turn_id}", None)
        if start and start.get("parent"):
            return None  # a subagent's turn: its parent reports the result
        response = as_text(assistant_response).strip()
        if not response:
            return None
        prompt_text = as_text(user_message)
        who = classify(prompt_text, platform, self.env("HERMES_SESSION_USER_NAME"), self.owner_names,
                       is_dm=self.env("HERMES_SESSION_CHAT_TYPE").lower() == "dm")
        if who is None:
            return None
        now = self.clock()
        prompt_at = start["ts"] if start else now
        chat_id, thread_id = self.env("HERMES_SESSION_CHAT_ID"), self.env("HERMES_SESSION_THREAD_ID")
        chat = self.env("HERMES_SESSION_CHAT_NAME")
        body: Dict[str, Any] = {
            "platform": platform, "chat": clip(chat, 200), "chatId": clip(chat_id, 200), "threadId": clip(thread_id, 200),
            "isDM": self.env("HERMES_SESSION_CHAT_TYPE").lower() == "dm", "sender": who["sender"], "inboundKind": who["inboundKind"],
            "prompt": {"at": iso(prompt_at), "text": tail(who["text"].strip(), 6000)}, "response": {"at": iso(now), "text": tail(response, 8000)},
            "sessionId": clip(session_id, 200), "turnId": clip(turn_id, 200),
        }
        if who.get("senderName"):
            body["senderName"] = clip(who["senderName"], 80)
        if who.get("job"):
            body["job"] = clip(who["job"], 120)
        if platform == "cron":
            m = CRON_SESSION.match(session_id)
            body["jobId"] = m.group(1) if m else ""
            body["deliverTo"] = self.env("HERMES_CRON_AUTO_DELIVER_PLATFORM")
        # Conversation history: Discord threads/channels, DMs, one per cron job, else per session.
        key = f"{platform}:{thread_id or chat_id or (body.get('jobId') or session_id)}"
        speaker = {"owner": "owner", "scheduler": "Scheduler", "system": self.assistant_name}.get(who["sender"], who.get("senderName") or "Someone")
        with self.lock:
            hist = self.history.setdefault(key, [])
            body["turns"] = previous_turns(hist, prompt_at - 0.001)
            hist.append({"ts": prompt_at, "speaker": speaker, "isOwner": who["sender"] == "owner", "text": who["text"]})
            hist.append({"ts": now, "speaker": self.assistant_name, "isOwner": False, "text": response})
            del hist[:-MAX_HISTORY]
            self.history.pop(key)
            self.history[key] = hist  # most recently used last
            while len(self.history) > MAX_CONVERSATIONS:
                self.history.pop(next(iter(self.history)))
        return body

    def finish(self, body: Dict[str, Any]) -> Dict[str, Any]:
        """Worker-side enrichment (file reads stay off the turn): cron job name and whether its output is delivered."""
        if body.get("platform") == "cron":
            job_id = body.pop("jobId", "")
            deliver_to = body.pop("deliverTo", "")
            job = {}
            try:
                job = self.jobs().get(job_id) or {}
            except Exception:
                job = {}
            if job.get("name"):
                body["job"] = clip(job["name"], 120)
                body["chat"] = body.get("chat") or body["job"]
            deliver = str(job.get("deliver") or "")
            body["delivered"] = bool(deliver_to) or (bool(deliver) and deliver != "local")
        return body

    # ---- delivery ----

    def _worker(self) -> None:
        while True:
            body = self.queue.get()
            try:
                self.send(body)
            except Exception:
                logger.debug("glasses-notify send failed", exc_info=True)

    def _read_token(self) -> Optional[str]:
        try:
            return self.token() or None
        except Exception:
            return None

    def send(self, body: Dict[str, Any]) -> bool:
        token = body.pop(TOKEN_KEY, None) or self._read_token()
        if not token:
            if not self.warned_token:
                logger.warning("glasses-notify: GLASSES_AGENT_TOKEN is not set; exchanges are not sent to the glasses")
                self.warned_token = True
            return False
        try:
            self.post(self.url, self.finish(body), token, self.timeout)
            return True
        except Exception as exc:
            logger.info("glasses-notify: gateway unavailable (%s)", type(exc).__name__)
            return False


def http_post(url: str, body: Dict[str, Any], token: str, timeout: float) -> int:
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="POST", headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as res:  # noqa: S310 (fixed internal URL)
        res.read(4096)
        return res.status


def load_jobs(path: str, ttl: float = 60.0, clock: Callable[[], float] = time.time) -> Callable[[], Dict[str, Dict[str, Any]]]:
    """Cached reader of Hermes' cron/jobs.json: {id: {name, deliver}}."""
    cache: Dict[str, Any] = {"at": -1e18, "jobs": {}}

    def read() -> Dict[str, Dict[str, Any]]:
        if clock() - cache["at"] < ttl:
            return cache["jobs"]
        jobs: Dict[str, Dict[str, Any]] = {}
        try:
            with open(path, "r", encoding="utf-8") as f:
                raw = json.load(f)
            for j in (raw.get("jobs") if isinstance(raw, dict) else raw) or []:
                if isinstance(j, dict) and j.get("id"):
                    jobs[str(j["id"])] = {"name": j.get("name") or "", "deliver": j.get("deliver") or ""}
        except Exception:
            jobs = cache["jobs"]
        cache.update(at=clock(), jobs=jobs)
        return jobs

    return read
