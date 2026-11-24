#!/usr/bin/env python3
"""Drive the RayNeo companion debug build from the PC over adb.

Standard library only. See android/DEBUG.md for the workflow.
The in-app half is the debug-only ContentProvider `<APP_ID>.debug`.
Set APP_ID when the app was built with -PappId=... (default io.github.rayneohud.companion).
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.parse
import xml.etree.ElementTree as ET
from pathlib import Path

# The installed applicationId (gradle -PappId, same default) and the Kotlin namespace the classes live in.
PKG = os.environ.get("APP_ID", "").strip() or "io.github.rayneohud.companion"
NS = "io.lampyr.glasses"
AUTHORITY = f"{PKG}.debug"
OFFICIAL = "com.rayneo.venus.pub"
ANDROID = Path(__file__).resolve().parent.parent
REPO = ANDROID.parent
APK = ANDROID / "app/build/outputs/apk/debug/app-debug.apk"
OUT = ANDROID / "build/glassesctl"
# Used only when JAVA_HOME is unset; point JAVA_HOME at a JDK 17 instead of editing this.
DEFAULT_JDK = Path(os.environ.get("GLASSESCTL_JDK", str(Path.home() / ".android-build/jdk")))
ACCESSIBILITY = f"{PKG}/{NS}.input.ControllerAccessibilityService"
LISTENER = f"{PKG}/{NS}.data.PhoneNotifications"
RUNTIME_PERMISSIONS = [
    "RECORD_AUDIO", "POST_NOTIFICATIONS", "BLUETOOTH_CONNECT", "BLUETOOTH_SCAN",
    "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACTIVITY_RECOGNITION",
    "READ_PHONE_STATE", "ANSWER_PHONE_CALLS", "READ_CONTACTS", "READ_CALL_LOG", "CALL_PHONE",
]
# The glasses Phone app (calls, contacts, recents, the assistant's call.place) needs all of these plus the app-op below.
CALL_PERMISSIONS = ["READ_PHONE_STATE", "ANSWER_PHONE_CALLS", "READ_CONTACTS", "READ_CALL_LOG", "CALL_PHONE"]


def call_access() -> str:
    """MANAGE_ONGOING_CALLS app-op as Android reports it (allow = Telecom binds the non-UI InCallService)."""
    out = shell(f"appops get {PKG} MANAGE_ONGOING_CALLS", check=False).strip()
    return "allow" if "allow" in out else (out.splitlines()[0] if out else "unknown")


class Fail(Exception):
    pass


def sdk_dir() -> Path | None:
    for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(name):
            return Path(os.environ[name])
    props = ANDROID / "local.properties"
    if props.exists():
        for line in props.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                return Path(line.split("=", 1)[1].strip().replace("\\:", ":").replace("\\\\", "/"))
    return None


def adb_path() -> str:
    if os.environ.get("ADB"):
        return os.environ["ADB"]
    sdk = sdk_dir()
    if sdk:
        for name in ("adb.exe", "adb"):
            candidate = sdk / "platform-tools" / name
            if candidate.exists():
                return str(candidate)
    found = shutil.which("adb")
    if not found:
        raise Fail("adb not found. Set ADB or sdk.dir in android/local.properties.")
    return found


SERIAL: str | None = os.environ.get("GLASSES_SERIAL")


def adb_bytes(*args: str, check: bool = True, timeout: float | None = 120, device: bool = True, merge: bool = False) -> bytes:
    command = [adb_path()] + (["-s", SERIAL] if device and SERIAL else []) + list(args)
    try:
        done = subprocess.run(command, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        raise Fail(f"adb timed out after {timeout}s: {' '.join(args)}")
    if check and done.returncode != 0:
        message = (done.stderr or done.stdout).decode("utf-8", "replace").strip()
        raise Fail(f"adb {' '.join(args)} failed: {message}")
    return done.stdout + (done.stderr if merge else b"")


def adb(*args: str, check: bool = True, timeout: float | None = 120, device: bool = True) -> str:
    """Text output; stderr is included when failures are tolerated so callers can report them."""
    raw = adb_bytes(*args, check=check, timeout=timeout, device=device, merge=not check)
    return raw.decode("utf-8", "replace").replace("\r\n", "\n")


def shell(command: str, **kwargs) -> str:
    return adb("shell", command, **kwargs)


def devices() -> list[tuple[str, str]]:
    rows = []
    for line in adb("devices", "-l", device=False).splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2:
            rows.append((parts[0], " ".join(parts[1:])))
    return rows


def require_device() -> None:
    global SERIAL
    rows = devices()
    ready = [serial for serial, state in rows if state.startswith("device")]
    if SERIAL:
        if SERIAL not in ready:
            raise Fail(f"Device {SERIAL} is not ready. adb devices: {rows or 'none'}")
        return
    if not ready:
        hint = "No phone connected. Plug in USB (allow debugging on the phone) or run: glassesctl.py connect"
        raise Fail(f"{hint}\nadb devices: {rows or 'none'}")
    if len(ready) > 1:
        # Prefer a physical/wireless phone over an emulator when both are present.
        phones = [serial for serial in ready if not serial.startswith("emulator-")]
        if len(phones) != 1:
            raise Fail(f"Several devices attached ({', '.join(ready)}). Pass --serial or set GLASSES_SERIAL.")
        ready = phones
    SERIAL = ready[0]


# ---------------------------------------------------------------- bridge

def bridge_raw(path: str, timeout: float = 30, **query) -> bytes:
    require_device()
    params = {key: value for key, value in query.items() if value is not None}
    uri = f"content://{AUTHORITY}/{path.lstrip('/')}"
    if params:
        uri += "?" + urllib.parse.urlencode(params, quote_via=urllib.parse.quote)
    return adb_bytes("exec-out", f"content read --uri '{uri}'", timeout=timeout)


def bridge(path: str, timeout: float = 30, **query) -> dict:
    raw = bridge_raw(path, timeout=timeout, **query)
    text = raw.decode("utf-8", "replace").strip()
    try:
        reply = json.loads(text)
    except json.JSONDecodeError:
        if not text or "Could not find provider" in text or "Error while accessing provider" in text:
            raise Fail("Debug bridge not reachable. Is the debug build installed? Run: glassesctl.py deploy\n" + text[:500])
        raise Fail(f"Bridge returned non-JSON for {path}:\n{text[:2000]}")
    if isinstance(reply, dict) and reply.get("ok") is False:
        detail = reply.get("detail")
        raise Fail(f"{path}: {reply.get('error')}" + (f"\n{detail}" if detail else ""))
    return reply


def show(value) -> None:
    if isinstance(value, (dict, list)):
        print(json.dumps(value, indent=2, ensure_ascii=False))
    else:
        print(value)


def pairs(items: list[str]) -> dict:
    result = {}
    for item in items:
        if "=" not in item:
            raise Fail(f"Expected key=value, got: {item}")
        key, value = item.split("=", 1)
        result[key] = value
    return result


def stamp() -> str:
    return dt.datetime.now().strftime("%Y%m%d-%H%M%S")


def out_file(name: str, explicit: str | None = None) -> Path:
    path = Path(explicit) if explicit else OUT / name
    path.parent.mkdir(parents=True, exist_ok=True)
    return path


# ---------------------------------------------------------------- commands

def cmd_doctor(args) -> None:
    jdk = Path(os.environ.get("JAVA_HOME") or DEFAULT_JDK)
    print(f"JDK        {jdk} {'ok' if (jdk / 'bin').exists() else 'MISSING'}")
    print(f"SDK        {sdk_dir()}")
    print(f"adb        {adb_path()}")
    print(f"APK        {APK} {'(built ' + dt.datetime.fromtimestamp(APK.stat().st_mtime).isoformat(timespec='seconds') + ')' if APK.exists() else '(not built)'}")
    rows = devices()
    print("devices    " + (", ".join(f"{serial} [{state.split()[0]}]" for serial, state in rows) or "none"))
    for serial, state in rows:
        if state.startswith("unauthorized"):
            print(f"  {serial}: unlock the phone and accept the 'Allow USB debugging' prompt")
    if any(state.startswith("device") for _, state in rows):
        require_device()
        print(f"using      {SERIAL}")
        installed = shell(f"pm path {PKG}", check=False).strip()
        print(f"app        {'installed' if installed.startswith('package:') else 'NOT installed'}")
        official = shell(f"pm path {OFFICIAL}", check=False).strip()
        print(f"official   {'installed' if official.startswith('package:') else 'not installed'}")
        if installed.startswith("package:"):
            access = call_access()
            print(f"calls      MANAGE_ONGOING_CALLS {access}" + ("" if access == "allow" else "  (run: glassesctl.py setup)"))
            try:
                show(bridge("ping", timeout=15))
            except Fail as error:
                print(f"bridge     unavailable: {error}")


def cmd_devices(args) -> None:
    for serial, state in devices():
        print(serial, state)
    services = adb("mdns", "services", device=False, check=False).strip()
    if services:
        print(services)


def cmd_pair(args) -> None:
    print(adb("pair", args.hostport, args.code, device=False, timeout=30).strip())


def wireless_endpoints() -> list[str]:
    found = []
    for line in adb("mdns", "services", device=False, check=False).splitlines():
        match = re.search(r"_adb-tls-connect\._tcp\.?\s+(\d+\.\d+\.\d+\.\d+:\d+)", line)
        if match:
            found.append(match.group(1))
    return found


def cmd_connect(args) -> None:
    targets = [args.hostport] if args.hostport else wireless_endpoints()
    if not targets:
        raise Fail("No wireless-debugging phone advertised on this network. Enable Developer options > Wireless debugging, "
                   "or pass ip:port, or plug in USB and run: glassesctl.py tcpip")
    for target in targets:
        print(adb("connect", target, device=False, timeout=20).strip())


def cmd_tcpip(args) -> None:
    require_device()
    route = shell("ip -f inet addr show wlan0", check=False)
    match = re.search(r"inet (\d+\.\d+\.\d+\.\d+)", route)
    if not match:
        raise Fail("Phone has no Wi-Fi address on wlan0. Join the same Wi-Fi as this PC first.")
    print(adb("tcpip", "5555").strip())
    time.sleep(2)
    print(adb("connect", f"{match.group(1)}:5555", device=False, timeout=20).strip())
    print(f"USB can now be unplugged. This lasts until the phone reboots. Serial: {match.group(1)}:5555")


def gradle(*tasks: str) -> None:
    env = dict(os.environ)
    if not env.get("JAVA_HOME") and DEFAULT_JDK.exists():
        env["JAVA_HOME"] = str(DEFAULT_JDK)
    if env.get("JAVA_HOME"):
        env["PATH"] = str(Path(env["JAVA_HOME"]) / "bin") + os.pathsep + env.get("PATH", "")
    wrapper = ANDROID / ("gradlew.bat" if os.name == "nt" else "gradlew")
    started = time.time()
    done = subprocess.run([str(wrapper), "-p", str(ANDROID), "--console=plain", "-q", *tasks], env=env, cwd=str(REPO))
    if done.returncode != 0:
        raise Fail(f"Gradle {' '.join(tasks)} failed (exit {done.returncode})")
    print(f"gradle {' '.join(tasks)}: ok in {time.time() - started:.0f}s")


def cmd_build(args) -> None:
    gradle(*(["testDebugUnitTest"] if args.tests else []), "assembleDebug")


def start_app(activity: bool = False) -> dict | None:
    # Battery-optimisation exemption / temp allowlist lets a background provider call start the foreground service.
    shell(f"cmd deviceidle tempwhitelist -d 20000 {PKG}", check=False)
    if activity:
        shell(f"am start -n {PKG}/{NS}.MainActivity", check=False)
        time.sleep(1.5)
    try:
        bridge("service/start", timeout=20)
    except Fail:
        pass
    ping: dict | None = None
    for _ in range(10):
        try:
            ping = bridge("ping", timeout=15)
            if ping.get("serviceRunning"):
                return ping
        except Fail:
            ping = None
        time.sleep(1)
    if not activity:
        return start_app(activity=True)
    return ping


def cmd_deploy(args) -> None:
    require_device()
    if not args.no_build:
        gradle(*(["testDebugUnitTest"] if args.tests else []), "assembleDebug")
    if not APK.exists():
        raise Fail(f"No APK at {APK}")
    started = time.time()
    result = adb("install", "-r", str(APK), timeout=300, check=False)
    if "Success" not in result:
        raise Fail(f"Install failed:\n{result.strip()}\n(INSTALL_FAILED_UPDATE_INCOMPATIBLE means a differently signed copy "
                   f"is installed; uninstalling loses app data, so ask first.)")
    print(f"installed in {time.time() - started:.0f}s")
    ping = start_app(activity=args.activity)
    if ping:
        print(f"running: build {ping.get('buildTime')} git {ping.get('gitSha')} pid {ping.get('pid')} service={ping.get('serviceRunning')}")
    else:
        print("installed, but the bridge did not answer; open the app on the phone once")


def cmd_setup(args) -> None:
    """Everything adb can grant, so the owner does not tap through setup screens."""
    require_device()
    sdk = int(shell("getprop ro.build.version.sdk").strip() or 0)
    failed_calls = []
    for permission in RUNTIME_PERMISSIONS:
        result = shell(f"pm grant {PKG} android.permission.{permission}", check=False).strip()
        print(f"grant {permission}: {'ok' if not result else result.splitlines()[0][:110]}")
        if result and permission in CALL_PERMISSIONS:
            failed_calls.append(permission)
    if sdk <= 30:
        shell(f"pm grant {PKG} android.permission.ACCESS_BACKGROUND_LOCATION", check=False)
    current = shell("settings get secure enabled_accessibility_services").strip()
    services = [item for item in current.split(":") if item and item != "null"]
    if ACCESSIBILITY not in services:
        services.append(ACCESSIBILITY)
        shell(f"settings put secure enabled_accessibility_services '{':'.join(services)}'")
    shell("settings put secure accessibility_enabled 1")
    print("accessibility service: enabled")
    print("notification listener:", shell(f"cmd notification allow_listener {LISTENER}", check=False).strip() or "allowed")
    print("battery exemption:", shell(f"dumpsys deviceidle whitelist +{PKG}", check=False).strip())
    shell(f"appops set {PKG} MANAGE_ONGOING_CALLS allow", check=False)
    access = call_access()
    print(f"call access (MANAGE_ONGOING_CALLS): {access}")
    if access != "allow":
        print(f"  WARNING: call control (answer/mute/audio route on the glasses) will not work; check 'adb shell appops get {PKG} MANAGE_ONGOING_CALLS'")
    if failed_calls:
        print("  WARNING: call permissions not granted: " + ", ".join(failed_calls))
    if args.stay_awake:
        shell("settings put global stay_on_while_plugged_in 7")
        print("screen stays on while charging")
    ping = start_app(activity=True)
    print("app:", "running" if ping and ping.get("serviceRunning") else "not confirmed running")


def cmd_official(args) -> None:
    """Keep RayNeo's own app off the Bluetooth link (only one client can own the glasses)."""
    require_device()
    if not shell(f"pm path {OFFICIAL}", check=False).strip().startswith("package:"):
        print("official app not installed")
        return
    verb = "grant" if args.mode == "on" else "revoke"
    for permission in ("BLUETOOTH_CONNECT", "BLUETOOTH_SCAN"):
        print(verb, permission, shell(f"pm {verb} {OFFICIAL} android.permission.{permission}", check=False).strip() or "ok")
    if args.mode == "off":
        shell(f"am force-stop {OFFICIAL}")
        print("official app stopped and blocked from Bluetooth")
    else:
        print("official app may use Bluetooth again; stop our glasses link first (glassesctl.py disable)")


def summary(state: dict) -> str:
    glasses, ui, app = state.get("glasses", {}), state.get("ui", {}), state.get("app", {})
    status = glasses.get("deviceStatus") or {}
    lines = [
        f"app      build {app.get('buildTime')} git {app.get('gitSha')} service={app.get('serviceRunning')}",
        f"glasses  {glasses.get('connectionState')} battery={status.get('battery')} firmware={status.get('firmware')} "
        f"page={glasses.get('pageOwner')} mic={(glasses.get('mic') or {}).get('mode')} error={glasses.get('lastError')}",
        f"ui       display={ui.get('displayMode')} flash={ui.get('flash')} locked={ui.get('locked')} stack={ui.get('stack')}",
        f"input    {json.dumps(state.get('controller', {}), ensure_ascii=False)[:300]}",
    ]
    if (state.get("latency") or {}).get("n"):
        lines.append("latency  " + latency_line(state["latency"]))
    denied = [name.rsplit('.', 1)[-1] for name, granted in (state.get("permissions") or {}).items() if granted is False]
    if denied:
        lines.append("missing  " + ", ".join(denied))
    screen = (state.get("screen") or {})
    for label in ("master", "sent"):
        rows = screen.get(label)
        if rows:
            lines.append(f"--- {label} ---")
            lines.extend(str(row) for row in rows)
    return "\n".join(lines)


def cmd_state(args) -> None:
    state = bridge("state")
    print(json.dumps(state, indent=2, ensure_ascii=False) if args.json else summary(state))


def cmd_text(args) -> None:
    sys.stdout.write(bridge_raw("text", which=args.which).decode("utf-8", "replace"))


def cmd_frame(args) -> None:
    data = bridge_raw("frame.png", which=args.which, scale=args.scale)
    if not data.startswith(b"\x89PNG"):
        raise Fail("frame.png: " + data.decode("utf-8", "replace")[:500])
    path = out_file(f"frame-{args.which}-{stamp()}.png", args.output)
    path.write_bytes(data)
    print(path)


def print_keys(reply: dict) -> None:
    for key in ("text", "screen"):
        value = reply.get(key)
        if value:
            print("\n".join(value) if isinstance(value, list) else value)
            break
    else:
        show(reply)
    if reply.get("stack"):
        print("stack:", reply["stack"])


def cmd_key(args) -> None:
    print_keys(bridge("key", b=args.button, a=args.action, ms=args.ms))


def cmd_keys(args) -> None:
    print_keys(bridge("keys", timeout=120, seq=",".join(args.sequence), gap=args.gap))


def latency_stats(samples: list[dict]) -> dict:
    """Median/p90 per stage, same rounding as the app's Latency.stat."""
    def stat(values):
        if not values:
            return None
        ordered = sorted(values)
        pick = lambda q: ordered[min(len(ordered) - 1, max(0, int((len(ordered) - 1) * q + 0.5)))]
        return {"median": pick(0.5), "p90": pick(0.9)}
    out = {"n": len(samples)}
    for stage in ("total", "render", "queue", "wire"):
        out[stage + "Ms"] = stat([s[stage] for s in samples if stage in s])
    out["pipelined"] = sum(1 for s in samples if s.get("pipelined"))
    out["behindFrame"] = sum(1 for s in samples if s.get("behindFrame"))
    return out


def latency_line(summary: dict) -> str:
    def part(name):
        value = summary.get(name + "Ms") or {}
        return f"{name} {value.get('median')}/{value.get('p90')}"
    return (f"{part('total')} ms (median/p90, n={summary.get('n')}) {part('render')} {part('queue')} {part('wire')} "
            f"pipelined={summary.get('pipelined')} behindFrame={summary.get('behindFrame')}")


def cmd_latency(args) -> None:
    """Press-to-COMMIT latency over the app's last 50 presses, optionally after injecting presses."""
    before = (bridge("state").get("latency") or {}).get("samples", [])
    marker = before[-1]["seq"] if before else 0
    if args.presses:
        keys = [args.keys[i % len(args.keys)] for i in range(args.presses)]
        bridge("keys", timeout=max(120, args.presses * (args.gap + 500) / 1000 + 30), seq=",".join(keys), gap=args.gap)
        time.sleep(2)
    summary = bridge("state").get("latency") or {}
    samples = summary.get("samples", [])
    if args.presses:
        samples = [s for s in samples if s.get("seq", 0) > marker]
        summary = latency_stats(samples)
    if args.json:
        print(json.dumps({"summary": {k: v for k, v in summary.items() if k != "samples"}, "samples": samples}, indent=2))
        return
    print(latency_line(summary))
    print(f"{'seq':>5} {'key':<8} {'total':>6} {'render':>6} {'queue':>6} {'wire':>6} {'bytes':>6}  flags")
    for s in samples[-args.rows:]:
        flags = " ".join(f for f, on in (("pipelined", s.get("pipelined")), ("behind", s.get("behindFrame"))) if on)
        print(f"{s.get('seq'):>5} {str(s.get('key')):<8} {s.get('total'):>6} {s.get('render'):>6} {s.get('queue'):>6} {s.get('wire'):>6} {s.get('bytes'):>6}  {flags}")


def print_trace(entries: list[dict]) -> None:
    for entry in entries:
        clock = dt.datetime.fromtimestamp(entry.get("t", 0) / 1000).strftime("%H:%M:%S.%f")[:-3]
        print(f"{entry.get('seq'):>7} {clock} {entry.get('cat', ''):<14} {entry.get('msg', '')}")


def cmd_trace(args) -> None:
    since = args.since
    if since is None and args.last:
        latest = bridge("trace", since=10**15, max=1).get("latest", 0)
        since = max(0, latest - args.last)
    while True:
        reply = bridge("trace", since=since, max=args.max, cat=args.cat)
        entries = reply.get("entries", [])
        print_trace(entries)
        if entries:
            since = entries[-1]["seq"]
        if not args.follow:
            print(f"latest seq {reply.get('latest')}", file=sys.stderr)
            return
        sys.stdout.flush()
        time.sleep(1)


def cmd_logcat(args) -> None:
    require_device()
    pid = shell(f"pidof {PKG}", check=False).strip().split(" ")[0]
    base = ["logcat", "-v", "threadtime"]
    if args.all or not pid:
        base += ["-s", "Glasses/*:V", "GlassesService:V", "AndroidRuntime:E"] if not args.all else []
    else:
        base += [f"--pid={pid}"]
    if args.follow:
        command = [adb_path()] + (["-s", SERIAL] if SERIAL else []) + base
        subprocess.run(command)
    else:
        print(adb(*base, "-d", "-t", str(args.lines)))


def cmd_crash(args) -> None:
    require_device()
    print(adb("logcat", "-b", "crash", "-d", "-t", "200"))


def cmd_pref(args) -> None:
    show(bridge("pref", file=args.file, key=args.key, value=args.value, type=args.type))


def cmd_call(args) -> None:
    """Phone call state, or one call.* / contacts.* agent op (as the assistant outside the glasses: call.place shows the card)."""
    if not args.op or args.op == "state":
        show(bridge("call/state"))
        return
    json.loads(args.args)  # fail early on bad JSON
    raw = bridge_raw("call/op", timeout=60, name=args.op, args=args.args, timeoutMs=45000)
    print(raw.decode("utf-8", "replace").strip())


def cmd_simple(path: str, timeout: float = 30):
    def run(args) -> None:
        show(bridge(path, timeout=timeout))
    return run


def cmd_scan(args) -> None:
    show(bridge("glasses/scan", timeout=args.ms / 1000 + 20, ms=args.ms))


def cmd_select(args) -> None:
    show(bridge("glasses/select", ble=args.ble, classic=args.classic, name=args.name))


def cmd_enable(on: str):
    def run(args) -> None:
        show(bridge("glasses/enable", on=on))
    return run


def cmd_wait(args) -> None:
    reply = bridge("glasses/wait", timeout=args.timeout + 15, state=args.state, timeoutMs=int(args.timeout * 1000))
    print_trace(reply.pop("entries", []) or reply.pop("trace", []) or [])
    show(reply)


def cmd_notify(args) -> None:
    show(bridge("glasses/notify", title=args.title, body=args.body, appId=args.app, category=args.category))


def cmd_inject(args) -> None:
    """Route a synthetic notification through the app's real pipeline (no Android notification is posted)."""
    params = {"pkg": args.pkg, "title": args.title, "body": args.body}
    for key in ("kind", "channel", "category", "conversation", "sender", "network", "chatId"):
        value = getattr(args, key.lower() if key != "chatId" else "chat_id")
        if value:
            params[key] = value
    if args.group:
        params["group"] = "1"
    show(bridge("notify/inject", timeout=30, **params))


def cmd_notify_check(args) -> None:
    """Read-only, text-free routing check of the real notifications currently posted on the phone."""
    show(bridge("notify/active", **({"pkg": args.pkg} if args.pkg else {})))


def cmd_mic(args) -> None:
    show(bridge("glasses/mic", on="1" if args.mode == "on" else "0"))


def cmd_exp(args) -> None:
    reply = bridge(f"experiment/{args.step}", timeout=60, **pairs(args.params))
    print(reply["log"] if args.step == "log" and isinstance(reply.get("log"), str) else json.dumps(reply, indent=2, ensure_ascii=False))


def cmd_send(args) -> None:
    show(bridge("glasses/send", business=args.business, type=args.type, json=args.json, channel=args.channel))


def cmd_raw(args) -> None:
    data = bridge_raw(args.path, timeout=args.timeout, **pairs(args.params))
    if args.output:
        Path(args.output).write_bytes(data)
        print(args.output)
    else:
        sys.stdout.write(data.decode("utf-8", "replace"))
        print()


def run_as(command: str) -> bytes:
    return adb_bytes("exec-out", f"run-as {PKG} {command}", check=False, timeout=120)


def pull_into(folder: Path) -> None:
    folder.mkdir(parents=True, exist_ok=True)
    listing = run_as("ls files/debug").decode("utf-8", "replace").split()
    for name in listing:
        if name.endswith((".jsonl", ".log", ".txt")):
            (folder / name).write_bytes(run_as(f"cat files/debug/{name}"))
    log = run_as("cat files/display-experiments.log")
    if log and b"No such file" not in log:
        (folder / "display-experiments.log").write_bytes(log)


def cmd_pull(args) -> None:
    require_device()
    folder = Path(args.output) if args.output else OUT / f"pull-{stamp()}"
    pull_into(folder)
    print(folder)


def cmd_snapshot(args) -> None:
    """One folder with everything needed to diagnose the current moment."""
    require_device()
    folder = Path(args.output) if args.output else OUT / f"snapshot-{stamp()}"
    folder.mkdir(parents=True, exist_ok=True)
    notes = []
    for name, action in (
        ("state.json", lambda: json.dumps(bridge("state"), indent=2, ensure_ascii=False).encode()),
        ("text-master.txt", lambda: bridge_raw("text", which="master")),
        ("text-sent.txt", lambda: bridge_raw("text", which="sent")),
        ("frame-master.png", lambda: bridge_raw("frame.png", which="master", scale=2)),
        ("frame-sent.png", lambda: bridge_raw("frame.png", which="sent", scale=2)),
        ("trace.json", lambda: json.dumps(bridge("trace", since=0, max=5000), ensure_ascii=False).encode()),
        ("logcat.txt", lambda: adb_bytes("logcat", "-d", "-v", "threadtime", "-t", "4000")),
        ("crash.txt", lambda: adb_bytes("logcat", "-b", "crash", "-d", "-t", "200")),
        ("phone.png", lambda: adb_bytes("exec-out", "screencap -p")),
        ("bluetooth.txt", lambda: adb_bytes("shell", "dumpsys bluetooth_manager")),
    ):
        try:
            (folder / name).write_bytes(action())
        except Fail as error:
            notes.append(f"{name}: {error}")
    try:
        pull_into(folder / "files")
    except Fail as error:
        notes.append(f"files: {error}")
    if notes:
        (folder / "errors.txt").write_text("\n".join(notes), encoding="utf-8")
    print(folder)
    for note in notes:
        print("  missing", note.splitlines()[0])


def cmd_screen(args) -> None:
    require_device()
    path = out_file(f"phone-{stamp()}.png", args.output)
    path.write_bytes(adb_bytes("exec-out", "screencap -p"))
    print(path)


def ui_nodes() -> list[dict]:
    require_device()
    shell("uiautomator dump /sdcard/window_dump.xml", check=False)
    xml = adb_bytes("exec-out", "cat /sdcard/window_dump.xml")
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        raise Fail("Could not read the phone's UI tree (screen off or a secure window?)")
    nodes = []
    for node in root.iter("node"):
        label = node.get("text") or node.get("content-desc") or ""
        bounds = re.findall(r"\d+", node.get("bounds", ""))
        if not label or len(bounds) != 4:
            continue
        left, top, right, bottom = map(int, bounds)
        nodes.append({"text": label, "x": (left + right) // 2, "y": (top + bottom) // 2,
                      "clickable": node.get("clickable") == "true", "package": node.get("package", "")})
    return nodes


def cmd_ui(args) -> None:
    for node in ui_nodes():
        print(f"{node['x']:>5},{node['y']:<5} {'*' if node['clickable'] else ' '} {node['text'][:100]}  [{node['package']}]")


def cmd_tap(args) -> None:
    require_device()
    if len(args.target) == 2 and all(part.isdigit() for part in args.target):
        x, y = args.target
    else:
        wanted = " ".join(args.target).lower()
        matches = [node for node in ui_nodes() if node["text"].lower() == wanted] or \
                  [node for node in ui_nodes() if wanted in node["text"].lower()]
        if not matches:
            raise Fail(f"No on-screen element matching '{wanted}'. Run: glassesctl.py ui")
        x, y = matches[0]["x"], matches[0]["y"]
        print(f"tapping '{matches[0]['text']}' at {x},{y}")
    shell(f"input tap {x} {y}")


def cmd_wake(args) -> None:
    require_device()
    shell("input keyevent KEYCODE_WAKEUP")
    shell("wm dismiss-keyguard", check=False)


def cmd_lens(args) -> None:
    """Display power on the glasses lens: wake, sleep, popup or state (not the phone screen; see `wake`)."""
    params = {"do": args.action}
    if args.ms is not None:
        params["ms"] = args.ms
    if args.action == "popup":
        params["title"] = args.title or "Popup"
        params["body"] = args.body or ""
        if args.app:
            params["app"] = args.app
        if args.target:
            params["target"] = args.target
    show(bridge("power", **params))


def cmd_open(args) -> None:
    require_device()
    target = {"main": f"{NS}.MainActivity"}.get(args.what)
    if not target:
        raise Fail("Only 'main' is launchable from adb; other screens are not exported.")
    print(shell(f"am start -n {PKG}/{target}").strip())


def cmd_stop(args) -> None:
    require_device()
    shell(f"am force-stop {PKG}")
    print("stopped (accessibility may need: glassesctl.py setup)")


def cmd_bugreport(args) -> None:
    require_device()
    path = out_file(f"bugreport-{stamp()}.zip", args.output)
    print(adb("bugreport", str(path), timeout=900).strip()[-300:])
    print(path)


def main() -> int:
    global SERIAL
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", "-s", help="adb serial (default: the only attached phone, or GLASSES_SERIAL)")
    sub = parser.add_subparsers(dest="command", required=True)

    def add(name, handler, help_text, *arguments):
        item = sub.add_parser(name, help=help_text)
        for flags, options in arguments:
            item.add_argument(*flags, **options)
        item.set_defaults(handler=handler)

    out = (("-o", "--output"), {"help": "output path"})
    which = (("--which",), {"choices": ["master", "sent"], "default": "master"})
    add("doctor", cmd_doctor, "check JDK, SDK, adb, phone, app and bridge")
    add("devices", cmd_devices, "list adb devices and wireless-debugging adverts")
    add("pair", cmd_pair, "pair wireless debugging once", (("hostport",), {}), (("code",), {}))
    add("connect", cmd_connect, "connect to a wireless-debugging phone", (("hostport",), {"nargs": "?"}))
    add("tcpip", cmd_tcpip, "switch a USB-attached phone to adb over Wi-Fi")
    add("build", cmd_build, "assemble the debug APK", (("--tests",), {"action": "store_true"}))
    add("deploy", cmd_deploy, "build, install in place (data kept) and restart",
        (("--tests",), {"action": "store_true"}), (("--no-build",), {"action": "store_true"}),
        (("--activity",), {"action": "store_true", "help": "also bring the phone UI to the front"}))
    add("setup", cmd_setup, "grant permissions, accessibility, notification access, battery exemption",
        (("--stay-awake",), {"action": "store_true"}))
    add("official", cmd_official, "block/unblock RayNeo's own app from Bluetooth", (("mode",), {"choices": ["on", "off"]}))
    add("state", cmd_state, "what the app is doing right now", (("--json",), {"action": "store_true"}))
    add("text", cmd_text, "text grid of the current screen", which)
    add("frame", cmd_frame, "PNG of the current frame", which, (("--scale",), {"type": int, "default": 2}), out)
    add("key", cmd_key, "press a controller button", (("button",), {}),
        (("action",), {"nargs": "?", "default": "tap", "choices": ["tap", "down", "up", "hold"]}),
        (("--ms",), {"type": int}))
    add("keys", cmd_keys, "press a sequence, e.g. down down a b:hold:600", (("sequence",), {"nargs": "+"}),
        (("--gap",), {"type": int, "default": 150}))
    add("latency", cmd_latency, "button-to-display latency (median/p90 of the last 50 presses); --presses N injects them first",
        (("--presses",), {"type": int, "default": 0}), (("--keys",), {"nargs": "+", "default": ["down", "up"]}),
        (("--gap",), {"type": int, "default": 1500}), (("--rows",), {"type": int, "default": 20}),
        (("--json",), {"action": "store_true"}))
    add("buttons", cmd_simple("buttons"), "valid button ids and bindings")
    add("trace", cmd_trace, "structured app trace (Bluetooth, display, input)",
        (("--since",), {"type": int}), (("--last",), {"type": int, "default": 200}),
        (("--max",), {"type": int, "default": 2000}), (("--cat",), {}), (("-f", "--follow"), {"action": "store_true"}))
    add("logcat", cmd_logcat, "Android log for the app", (("-f", "--follow"), {"action": "store_true"}),
        (("--lines",), {"type": int, "default": 400}), (("--all",), {"action": "store_true"}))
    add("crash", cmd_crash, "recent crash stacks")
    add("pref", cmd_pref, "set a preference", (("key",), {}), (("value",), {"nargs": "?"}),
        (("--file",), {"default": "glasses", "choices": ["glasses", "app"]}),
        (("--type",), {"default": "string", "choices": ["string", "bool", "int", "long", "float", "remove"]}))
    add("scan", cmd_scan, "scan for glasses", (("--ms",), {"type": int, "default": 12000}))
    add("select", cmd_select, "choose the scanned glasses", (("--ble",), {"required": True}), (("--classic",), {}), (("--name",), {}))
    add("enable", cmd_enable("1"), "turn Real glasses on (connects)")
    add("disable", cmd_enable("0"), "turn Real glasses off (disconnects)")
    add("reconnect", cmd_simple("glasses/reconnect"), "force a reconnect")
    add("wait", cmd_wait, "wait for a connection state", (("state",), {"nargs": "?", "default": "CONNECTED"}),
        (("--timeout",), {"type": float, "default": 60}))
    add("status", cmd_simple("glasses/status"), "query battery / firmware / brightness")
    add("testdisplay", cmd_simple("glasses/testdisplay"), "send the 26x7 test grid")
    add("reclaim", cmd_simple("glasses/reclaim"), "take the display page back after a crown exit")
    add("notify", cmd_notify, "send a stock notification card", (("title",), {}), (("body",), {}),
        (("--app",), {}), (("--category",), {"type": int}))
    add("inject", cmd_inject, "route a synthetic phone notification: inject TITLE BODY [--pkg P --channel C --group ...]",
        (("title",), {}), (("body",), {"nargs": "?", "default": ""}), (("--pkg",), {"default": "com.beeper.android"}), (("--kind",), {}),
        (("--channel",), {}), (("--category",), {}), (("--conversation",), {}), (("--sender",), {}), (("--network",), {}),
        (("--chat-id",), {"dest": "chat_id"}), (("--group",), {"action": "store_true"}))
    add("notify-check", cmd_notify_check, "how the rules route the real notifications posted now (no text): notify-check [--pkg com.tinder]",
        (("--pkg",), {}))
    add("mic", cmd_mic, "start/stop the glasses mic test", (("mode",), {"choices": ["on", "off"]}))
    add("exp", cmd_exp, "display experiment step, e.g. exp glyph page=0", (("step",), {}), (("params",), {"nargs": "*"}))
    add("send", cmd_send, "send one raw stock command (OTA writes are refused)", (("business",), {"type": int}),
        (("type",), {"type": int}), (("json",), {"nargs": "?", "default": "{}"}),
        (("--channel",), {"default": "rfcomm", "choices": ["rfcomm", "ble"]}))
    add("raw", cmd_raw, "call any bridge endpoint: raw <path> key=value ...", (("path",), {}), (("params",), {"nargs": "*"}),
        (("--timeout",), {"type": float, "default": 60}), out)
    add("help-bridge", cmd_simple("help"), "endpoints the installed build supports")
    add("reload", cmd_simple("service/reload"), "reload app data (live/fake) without restarting")
    add("pull", cmd_pull, "copy trace files and the experiment log to the PC", out)
    add("snapshot", cmd_snapshot, "state + frames + trace + logcat + phone screenshot in one folder", out)
    add("screen", cmd_screen, "screenshot of the phone itself", out)
    add("ui", cmd_ui, "list what is on the phone screen (for system dialogs)")
    add("tap", cmd_tap, "tap coordinates or on-screen text, e.g. tap Pair", (("target",), {"nargs": "+"}))
    add("wake", cmd_wake, "wake the phone screen")
    add("call", cmd_call, "phone calls: 'call' (state) or 'call <op> [json]', e.g. call contacts.search '{\"query\":\"mom\"}'",
        (("op",), {"nargs": "?"}), (("args",), {"nargs": "?", "default": "{}"}))
    add("lens", cmd_lens, "glasses display power: lens wake|sleep|state|popup [--ms N] [--title T --body B --app ID --target T]",
        (("action",), {"nargs": "?", "default": "state", "choices": ["wake", "sleep", "state", "popup"]}),
        (("--ms",), {"type": int}), (("--title",), {}), (("--body",), {}), (("--app",), {}), (("--target",), {}))
    add("open", cmd_open, "open the app's main screen on the phone", (("what",), {"nargs": "?", "default": "main"}))
    add("stop", cmd_stop, "force-stop the app")
    add("bugreport", cmd_bugreport, "full Android bugreport (contains the Bluetooth HCI snoop log)", out)

    args = parser.parse_args()
    if args.serial:
        SERIAL = args.serial
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    try:
        args.handler(args)
    except Fail as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main())
