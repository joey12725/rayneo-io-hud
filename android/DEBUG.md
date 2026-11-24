# Remote debugging and the hardware test loop

The debug build can be built, installed, driven and read entirely from the PC
over `adb`. Nothing here exists in a release build.

Two halves:

- **In the app (debug source set):** a ContentProvider, `<applicationId>.debug`
  (`io.github.rayneohud.companion.debug` by default; set `APP_ID` for glassesctl when you build with `-PappId`),
  that only answers the adb shell user. It reports state, returns the current
  frame, injects controller buttons, runs the glasses setup and display
  experiments headlessly, and serves the structured trace. `Trace` (in the main
  source set) records what the Bluetooth, display, input and mic layers do.
- **On the PC:** `android/tools/glassesctl.py` (Python standard library only).

## One-time phone setup

1. Settings → About phone → tap **Build number** seven times.
2. Settings → Developer options → enable **USB debugging**.
3. Plug the phone into the PC and accept **Allow USB debugging** (tick "Always allow").

Then, from the repository root:

```powershell
python android/tools/glassesctl.py doctor      # JDK, SDK, adb, phone, app, bridge
python android/tools/glassesctl.py tcpip       # optional: adb over Wi-Fi, unplug USB (until reboot)
python android/tools/glassesctl.py deploy      # build, install in place, restart
python android/tools/glassesctl.py setup       # permissions, accessibility, notification access, battery exemption
python android/tools/glassesctl.py official off  # keep RayNeo's own app off the Bluetooth link
```

For adb over Wi-Fi that survives reboots, use Developer options → **Wireless
debugging** → *Pair device with pairing code*, then
`glassesctl.py pair <ip:port> <code>` once and `glassesctl.py connect` afterwards.

`deploy` keeps app data, pairing and settings (`adb install -r`). `setup` does
through adb what the setup screen's permission shortcuts do by hand. Learning
the 16 controller buttons still needs the physical controller.

Calls: `setup` grants every call permission (`READ_PHONE_STATE`, `ANSWER_PHONE_CALLS`,
`READ_CONTACTS`, `READ_CALL_LOG`, `CALL_PHONE`) and the `MANAGE_ONGOING_CALLS` app-op,
then reads the app-op back and prints `call access (MANAGE_ONGOING_CALLS): allow` or a
warning. Without the app-op Telecom never binds the non-UI InCallService, so answer,
mute and audio routing from the glasses do nothing. An uninstall (not `deploy`) wipes
all of these: run `setup` again after a fresh install. `doctor` prints the app-op state.

## The loop

```powershell
python android/tools/glassesctl.py deploy            # new build on the phone, about a minute
python android/tools/glassesctl.py state             # connection, firmware, battery, screen text, missing permissions
python android/tools/glassesctl.py trace --last 300  # what the Bluetooth stack just did
python android/tools/glassesctl.py latency           # press-to-COMMIT median/p90 over the last 50 presses (--presses 24 injects them)
python android/tools/glassesctl.py snapshot          # everything above plus frames, logcat, phone screenshot, in one folder
```

Pairing the glasses without touching the phone (glasses in pairing mode: hold
the button about five seconds until the blue LED blinks):

```powershell
python android/tools/glassesctl.py scan
python android/tools/glassesctl.py select --ble AA:BB:CC:DD:EE:FF --classic 11:22:33:44:55:66 --name "RayNeo ..."
python android/tools/glassesctl.py enable
python android/tools/glassesctl.py tap Pair          # confirm Android's pairing dialog
python android/tools/glassesctl.py wait CONNECTED --timeout 90
python android/tools/glassesctl.py status            # battery / firmware
python android/tools/glassesctl.py testdisplay
```

Other commands (`glassesctl.py --help` lists all):

| Command | Purpose |
| --- | --- |
| `key a`, `key b hold --ms 600`, `keys down down a` | Press controller buttons; prints the resulting screen |
| `text`, `frame` | Current screen as the 26×7 text grid or a PNG (`--which sent` = last frame the glasses acknowledged) |
| `trace -f`, `logcat -f`, `crash` | Follow the structured trace, the Android log, or read crash stacks |
| `exp firmware`, `exp glyph page=0`, `exp weather code=100`, `exp record result=PASS note=...`, `exp log` | Display experiments from [GLASSES.md](GLASSES.md#display-experiments-in-decision-order) |
| `mic on` / `mic off` | Glasses mic test; level and packet counters appear in `state` |
| `send <business> <type> '<json>'` | One raw stock command for protocol probing. OTA writes are refused |
| `pref display.mode TEXT` | Set a preference in the `glasses` file (`--file app` for app settings) |
| `raw <path> key=value` | Call any bridge endpoint directly; `help-bridge` lists them |
| `screen`, `ui`, `tap <text or x y>`, `wake` | See and operate the phone itself (system dialogs) |
| `pull`, `bugreport` | Copy trace files and the experiment log; full bugreport including the HCI snoop log |
| `lens state`, `lens wake --ms 5000`, `lens sleep`, `lens popup --title T --body B --app beeper --target ID` | Glasses display power: state (`power` object: state, lit, idleMs, lastWakeReason, backend), wake, sleep, or a test popup card |
| `pref beeper.order RECENT` | Beeper list order: `PRIORITY` (default: pins, important, unread, rest, collapsed Quiet row) or `RECENT` (pins, then unread first by recency) |
| `inject "Sam" "Dinner at 7?" [--pkg com.tinder --channel MATCHES --group --conversation C --sender S --network N --category C --kind K]` | Route a synthetic phone notification through the real pipeline (offline rules, `POST /notify/route`, pop-up); no Android notification is posted. The reply lists the `Notify` trace lines (`pkg ch route by reason ms`, never text) |
| `notify-check [--pkg com.tinder]` | Read-only: for every real notification an allow-listed app (Beeper, T3 Code, apps in the notification policy) has posted right now, show `pkg channel category`, who decides (`phone-rule`, `gateway` = the gateway's classifier, `dropped-on-phone`), the rule route and reason, and the offline route. Never text (only `hasTitle`/`hasBody`); nothing is routed, posted or shown on the lens |
| `call`, `call contacts.search '{"query":"mom"}'`, `call call.place '{"contact":"mom"}'`, `call call.route '{"endpoint":"speaker"}'`, `call call.hangup` | Phone calls: state (phases, routes, access; no names or numbers), or one `call.*` / `contacts.*` agent op run exactly as the assistant would from outside the glasses. `call.place` stops at the "Call Mom?" card until Y is pressed on the glasses |
| `pref power.idleMs 5000` | Display power preferences (`power.idleMs` 0 = never, `power.dimStep`, `power.firstPressActs`, `power.lockHomeMs`, `power.popupWhenLocked`, `power.lookUp` ON/OFF/TOGGLE/NOTHING (old boolean `power.lookUpWake` migrates true->ON, false->NOTHING), `power.lookUpWhenLocked`, `power.backend` auto/black/park, `display.textCase` CAPS/NORMAL; `power.idleMs` allows 1 s to 60 s) |

Output files go to `android/build/glassesctl/` (git-ignored).

### Hardware test: optional dating preset

The dating preset in the notification policy is off by default. With `"dating": {"enabled": true}` in the policy
(phone Settings or the gateway's `NOTIFY_POLICY_FILE`), a new match and an inbound message pop up, a new like goes to
the list only, and promotions are suppressed. The per-app channel ids live in
`server/gateway/apis/glasses/notify-policy.default.json` (mirrored by `data/NotifyPolicy.kt`).

1. Synthetic, any time (does post to the lens):
   `inject "Riley" "Hey" --pkg com.tinder --channel MATCHES` -> `route=popup reason=dating.match`;
   `--channel MESSAGES` -> popup `dating.message`; `--channel NEW_LIKES` -> stream `dating.like`;
   `--channel TOP_PICKS` -> suppress `dating.channel`.
2. Real: leave a notification in the phone's shade and run `notify-check --pkg <package>`. Confirm the route matches
   what the lens did. `trace --cat Notify` shows the decision that was actually taken (`pkg ch route by reason ms`,
   no text). An unknown channel id means the app added one: add it to the policy's `dating.apps` table.

## What cannot be seen from the PC

The bridge reports what the app sent and what the glasses acknowledged. It
cannot see the lens. Whether text is legible, rows are clipped, an icon
appeared, or the recording LED is lit has to be reported by the wearer.

## Safety

- The provider rejects every caller except the adb shell (uid 2000) and root.
- It is declared only in `src/debug`; release builds do not contain it.
- It adds no firmware-flashing or OTA-write capability. `send` goes through the
  same write boundary as the rest of the app, which allows only OTA queries.
- Verbose tracing (payload hex, display text) is on only in debug builds. Gateway
  tokens, the pairing proof and microphone audio are never traced. Trace files
  and snapshots can contain notification text and device addresses; keep them
  local.
