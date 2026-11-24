"""glasses-notify: the assistant's exchanges on every channel except the glasses, sent to the glasses gateway.

On each completed exchange (an inbound message -> the assistant's final response; post_llm_call, once per turn) on any
platform except api_server (the glasses channel and internal pipeline calls), and for scheduled-job (cron) outputs, POSTs
{platform, chat, isDM, sender, prompt, response, timestamps, up to 5 previous turns within 4 h} to
POST /glasses/v1/notify/hermes. The RayNeo iO HUD gateway routes it with its rules and its classifier model: popup,
the glasses list, or nothing.

Settings (Hermes environment, e.g. $HERMES_HOME/.env):
  GLASSES_AGENT_TOKEN              bearer for the gateway (scope glasses.agent); required
  GLASSES_NOTIFY_URL               default http://localhost:8080/glasses/v1/notify/hermes
  GLASSES_NOTIFY_OWNER_NAMES       comma list of the owner's sender names/handles in shared chats (recommended)
  GLASSES_NOTIFY_ASSISTANT_NAME    the assistant's display name in conversation history, default Hermes
  GLASSES_NOTIFY_SKIP_PLATFORMS    comma list, default api_server
  GLASSES_NOTIFY_TIMEOUT_S         HTTP timeout, default 3
  GLASSES_NOTIFY_DISABLED=1        turns the plugin off

Observer only: the hooks always return None, never raise, and never block a turn (one daemon worker, bounded queue).
"""
import logging
import os

logger = logging.getLogger("glasses-notify")


def _session_env():
    try:
        from gateway.session_context import get_session_env
        return lambda name: get_session_env(name, "")
    except Exception:
        return lambda name: os.environ.get(name, "")


def _token():
    try:
        from agent.secret_scope import get_secret
        value = get_secret("GLASSES_AGENT_TOKEN")
        if value:
            return value
    except Exception:
        pass
    return os.environ.get("GLASSES_AGENT_TOKEN") or None


def _hermes_home():
    try:
        from hermes_constants import get_hermes_home
        return str(get_hermes_home())
    except Exception:
        return os.environ.get("HERMES_HOME", os.path.expanduser("~/.hermes"))


def register(ctx):
    if os.environ.get("GLASSES_NOTIFY_DISABLED") == "1":
        logger.info("glasses-notify disabled (GLASSES_NOTIFY_DISABLED=1)")
        return
    from .notify_core import Notifier, load_jobs, DEFAULT_URL

    def _list(name, default):
        return [x.strip() for x in os.environ.get(name, default).split(",") if x.strip()]

    try:
        timeout = float(os.environ.get("GLASSES_NOTIFY_TIMEOUT_S", "3"))
    except ValueError:
        timeout = 3.0
    notifier = Notifier(
        url=os.environ.get("GLASSES_NOTIFY_URL", DEFAULT_URL),
        token=_token,
        session_env=_session_env(),
        jobs=load_jobs(os.path.join(_hermes_home(), "cron", "jobs.json")),
        skip_platforms=_list("GLASSES_NOTIFY_SKIP_PLATFORMS", "api_server"),
        owner_names=_list("GLASSES_NOTIFY_OWNER_NAMES", ""),
        assistant_name=os.environ.get("GLASSES_NOTIFY_ASSISTANT_NAME", "").strip() or "Hermes",
        timeout=max(0.5, min(timeout, 10.0)),
    )
    ctx.register_hook("pre_llm_call", notifier.pre_llm_call)
    ctx.register_hook("post_llm_call", notifier.post_llm_call)
    if not notifier.owner_names:
        logger.warning("glasses-notify: GLASSES_NOTIFY_OWNER_NAMES is not set; in shared chats every named sender counts as someone else")
    logger.info("glasses-notify registered (skip: %s)", ",".join(sorted(notifier.skip)))
