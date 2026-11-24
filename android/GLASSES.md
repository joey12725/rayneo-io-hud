# RayNeo iO: real Bluetooth transport

This build supports **stock text** and capability-probed **custom raster**,
with BLE guest authentication, then the secure
RFCOMM business service UUID `99999999-9999-9999-9999-999999999999`.
No firmware flashing, private socket reflection, cloud account authentication,
or Turbo-IO implementation code is included. Original raster senders use wire
facts. Sources: [stock protocol](../docs/rayneo-protocol.md),
[raster contract](../docs/raster-wire.md), [firmware decision](../docs/firmware-decision.md).

## Build and install

PowerShell from the repository root:

```powershell
$env:JAVA_HOME = '<jdk-17>'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\android\gradlew.bat -p android testDebugUnitTest assembleDebug
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

The app always retains the phone preview. Real Bluetooth is opt-in, stored in
private preferences file `glasses`: `glasses.enabled` (default `false`),
`glasses.address` (selected classic address, or BLE address when the advert lacks
manufacturer data), `glasses.bleAddress`, and `glasses.phoneId` (persisted six-byte
ANDROID_ID suffix). The app never adopts the ID of a previously bound phone.
`GlassesTransports` owns a single real transport for the foreground service.
The registry reacts to preference changes without restarting the service.

## Pair and test

1. Charge the glasses, unfold the temples, and turn on the phone's Bluetooth.
2. **Unbind in the official RayNeo app**, then forget the glasses in Android's
   Bluetooth settings. Close/force-stop the official app. If it reconnects,
   manually deny its Nearby devices permission while testing this companion.
3. Hold the glasses button about **five seconds**, until the blue LED blinks.
4. Open the companion's **Real glasses setup** entry. Grant Nearby devices
   permission when requested. Android 10/11 instead requires Location permission
   and system location services for BLE scans. Bluetooth features are optional:
   preview-only phones/emulators remain supported.
5. Tap **Scan**, select the discovered glasses, then **Connect selected / saved
   glasses**. Confirm the system Bluetooth pairing dialog. Connect enables the
   saved Real glasses preference. A classic bond is attempted before BLE auth
   when the advert supplies its address. Pairing can take up to 45 seconds.
6. Wait for `CONNECTED`; check battery percentage and the firmware string. Unknown
   values mean no corresponding response has arrived, not a synthetic success.
   BLE auth has a 25-second limit and RFCOMM connect a 20-second limit.
7. Tap **Test display**. Count all 26 columns and seven rows. Row 7 must be visible;
   inspect left/right alignment, spaces and punctuation. Press a controller key
   to render another app frame. The test uses the same conversion/session/pacing
   path. The phone preview switches to the same text grid and page sizes as the glasses.
8. Test page exit with the crown: while Real glasses is enabled, this app reopens
   the caption grid and replays the most recent frame. Disable Real glasses to
   release ownership. Firmware `effective_config` is checked and used: font 1
   means 26 columns; font 2 fallback means 20, with the returned row count. An
   unsupported layout is surfaced as an error and is never treated as confirmed.
9. Tap **Test mic**, speak, and watch the meter. The meter decodes locally and
   discards PCM. Tap **Stop test mic** before testing dictation; mic consumers are
   exclusive. Backgrounding setup stops its mic test. Check permission/temples/
   battery if LifeLog rejects the fallback.
10. Turn glasses off/on, move out of range, and restore range. Reconnect delays are
    5, 10, 15 seconds (three retries); controller input or Connect restarts the
    retry budget. Reconnection rescans the saved device and repeats auth; it
    never deletes bonds. Turn off Real glasses to cancel retries.

If discovery stops after re-pairing, re-enter pairing mode. If BLE auth succeeds
but RFCOMM fails with a link-key/auth error, forget the phone-side bond and retry;
do not silently copy another phone's identity. An account-bound auth error means
unbind through the official app first; that authentication branch is unsupported.

## What the glasses show

Stock firmware displays a full-width **26 x 7 character grid**. The confirmed
firmware layout also selects the 20 x 5 fallback when font 2 is returned. AUTO
uses this path unless an actual raster capability reply is received. Custom
TVP2/Glassly sends the native **540×220** artwork 1:1 with configurable
grey levels; panel buffers themselves are 540×280. With only the phone preview
active, `Display.Bitmap` retains the simulator artwork. Turn on
**Preview as glasses text** on the phone preview to use the same text layout and
pagination without connected glasses. On connection the mirror follows successful
text writes or acknowledged raster frames; its font is not optical proof.

A normal reader has a header, five body rows (three at 20 x 5), and a final row
for hints and a right-aligned page number. All four D-pad directions page readers.
Lists use `>` for focus, a detail line immediately below the focused item, and
`^` / `v` when more items exist. Questions and approvals use left/right to page
the question or command and up/down to select options. People quick view reserves
left/right for people; usage reserves them for providers. Teleprompter starts each
sentence on a new page and scrolls one line at a time with up/down.

Only ASCII is sent to the full-width converter: `...` replaces an ellipsis,
`>>` prefixes a 1.7-second toast, and `[name]` replaces supported emoji using the
requirements' Appendix A names. Unknown emoji appear as `[emoji]`; other unknown
characters remain visible as `?`. The phone text mirror uses these same cells,
including listening/processing states, the media layer, quiet-wait blanking and
the completely blank locked display. These are content/layout examples, not a
claim of identical phone and waveguide typography.

The following seven-row examples come directly from the checked-in text goldens
in `app/src/test/resources/textgolden/` (fixed clock: November 11, 7:45 PM Chicago).

Home:

```text
7:45 PM         Wed Nov 11
72F rain after 4 PM
>Msgs: 4 unread . Sam, Hermes
 T3: 4 need you . 2 done
 Play: Project Hail Mary
 Notifs: 3 new
 Next: Team sta... 8:00 PM
```

Assistant reply:

```text
Hermes               Reply
Morning. You have standup
at 8 tonight, two T3
threads waiting on you,
and Sam wants to know if
you're coming out. Want me
X menu L2 talk         1/2
```

Beeper thread:

```text
Sam               iMessage
>Sam                   3/3
reel sent an Instagram
reel

[tears of joy]
Y read X react L2 msg
```

T3 approval (left/right reads the reason):

```text
Deploy event-s... APPROVAL
Run command
docker compose -f prod.yml
up -d --build scraper
>Approve once
 Approve for this thread
 Deny               <> 1/3
```

Now playing:

```text
Now playing      Audiobook
Project Hail Mary
Andy Weir . Chapter 14
[###.......]           34%
5h 30m / 16h 10m
Speed 1.5x           Vol 6
Y play/pause X menu
```

## Microphone choice and limitations

The source documents **48 kHz stereo** on the `0x17` prompt page: channel 0 is
wearer/bone conduction, channel 1 is the forward microphone. That page replaces
the caption grid; no source proves simultaneous stereo prompt audio and a 26×7
caption grid. The caption `0x13` type-1 session retains the grid but is documented
as **48 kHz mono**, so it is this implementation's primary dictation input. Its
bone-conduction attribution is **unverified**; do not infer two isolated channels.
If actual packets are stereo, decoding selects channel 0; occasional mono packets
are configured as mono from the Opus TOC.

The display initially uses text-only type 7. Starting a mic consumer closes that
session and starts caption type 1, waits for type 2, sets layout, waits for type 8
and settles 500 ms before text. Ending capture closes that audio session and
restores text-only layout. Microphone start therefore involves a page transition.
There is no independent mic-off command for the caption page.

If caption audio/startup fails or stalls for ten seconds, the app restores the
text-only grid and explicitly arms **LifeLog A3 mono** for this mic consumer.
LifeLog is speech-triggered: A1 starts an A2/A8 recording round, A3 is split into
240-byte Opus packets, A5 ends it. Task IDs isolate rounds; VAD/VPU metadata is
parsed. LifeLog/display coexistence and fallback wearer attribution are unverified.
This dictation fallback is not continuous stereo capture. Separately, the explicit
phone Ambient capture controls can arm background LifeLog, use continuous prompt
stereo with phone VAD, and opt into a diagnostic cached-audio probe (see below).
Stopping dictation disarms its LifeLog round, then restores ambient if enabled.
With ambient disabled, connecting alone never arms LifeLog.
Deliberate disconnect/service shutdown attempts stop/disarm for up to one second
before closing the link; unexpected radio loss cannot deliver a stop command.
Check the glasses' recording indicator after a forced-loss fallback test.

Android MediaCodec `audio/opus` uses OpusHead, zero pre-skip and 80 ms pre-roll CSD;
only PCM16 at 48 kHz is accepted. A bounded 16-packet queue drops excess audio.
Channel 0 is selected before a streaming three-tap average and decimation to
16 kHz. This simple filter has modest stop-band rejection and is for initial
dictation testing, not archival recording. MediaCodec/device compatibility is
unverified until tested on the actual phone. `MicRegistry.glasses` is registered
on connection and cleared on disconnect; a successful `start` means a capture
request was accepted locally, not that PCM has arrived. The meter and last-error
field distinguish decoding from a silent or missing stream.

## Verification boundary

**JVM tests, no hardware:** CRC16-XMODEM known vectors; frame round trip,
address/opaque slice parsing, CRC resync, split/concatenated frames, MTU chunking,
ACKs and maximum payload; protobuf fields and malformed lengths; a SHA-256 auth
vector independently derived with Python hashlib; phone-ID/TLV rules and MTU
callback gate; command fields, UTF-8 vs UTF-16 units, file-transfer chunks and
completion, FNV-1a, ordered weather hours, int64 todo IDs and OTA allowlist; event
and LifeLog batch parsing; 64-sequence audio dedupe; full-width/emoji conversion,
first-frame prefix reset, frame dedupe/pacing with a fake clock/sink; fake RFCOMM
socket ordering/chunking/ACK/close; channel isolation and streaming downsample
math on synthetic PCM; Opus TOC timing.

The alignment prefix workaround was reported for font 2 upstream. This port
applies it to font 1 as requested; verify successive font-1 frames on hardware.

**Source reading only in this app:** GATT UUIDs, guest commands 17→24→25 and proof,
manufacturer classic address, RFCOMM UUID, caption startup and layout, stock
notification/teleprompter/todo/weather command fields, read-only firmware queries,
Opus stream parameters, LifeLog fallback. Some are reported wearer-tested by
OpenRayneo; that does not constitute a hardware test of this port. No hardware
claim about this port has been verified here. Pixel geometry is static firmware
evidence. Frame writes are not
render acknowledgements. Slice bytes are preserved as opaque metadata; outgoing
segmentation is stream chunking, since no slice-assembly semantics are known.

**Operational caveats:** both BLE and RFCOMM feed the parser; caption audio is
deduped by SID and the most recent 64 JSON sequences. LifeLog lacks a documented
JSON sequence in A3. Ambient uses the AA55 frame sequence for a bounded duplicate
window; whether BLE/RFCOMM reuse that sequence needs a real capture before
claiming lossless fallback audio. JSON surface builders and request-driven script
transfer are protocol APIs, not full UI features. Todo replacement is explicit
and callers must preserve queried items. Status is refreshed once per minute.
Only OTA query types 1, 2, 11 are allowed at the write boundary. No raw crown or
global head gesture stream is known; typed page-specific events are exposed by
the parser. Setup does not imply that those events are mapped to app controls.

## Week-one hardware checklist (requirements §28 phase 2)

Record date, phone/Android, glasses firmware, battery before/after, and pass/fail:

- [ ] Fresh pairing, saved reconnect, power-cycle reconnect, bond rejection,
      auth failure and account-bound rejection; no official app running.
- [ ] Coexistence: deliberately open the official app after disconnecting this
      client. Confirm whether ownership is exclusive and weather/todos change.
      Restore the closed-official-app baseline for normal operation.
- [ ] Measure actual visible resolution/character geometry. Check all 26×7 cells,
      row breaks, clipped columns, unsupported glyph placeholders and successive
      frame prefix alignment. The preview's 540×220 pixels match the exposed UI surface 1:1.
- [ ] Glyphs: the morning report's money reads `$43,192` / `$219-164` (not `?43192`) on the bitmap lens in both
      text cases and on the 26×7 stock page (full-width `＄`); `€ £ ✓ ← ↑ ↓ •` draw as glyphs, an emoji as `☺`
      on bitmap, and an unknown symbol as an empty box `□`, never `?`.
- [ ] Measure page-change latency (start→type 2→type 8→first visible frame),
      controller navigation and mic-start/stop transitions. Film through the lens
      or use timestamps against a changing grid. The text send cap is 8 Hz (raster 10 Hz), with stale
      frames dropped; confirm the glasses don't catch up with old frames.
- [ ] Stream mic for 1, 10 and 30 minutes. Record PCM rate, dropouts, battery cost,
      channel identity with wearer/room speech, stereo TOC occurrence, display
      usability and LifeLog fallback behaviour. Compare caption mono against a
      separate prompt stereo experiment before promising bone-conduction isolation.
- [ ] Test LifeLog wake, exit, silence and repeated rounds. Cached audio remains
      a separate investigation; the opt-in A7 probe logs A4 availability only.
- [ ] Test crown page exits and teleprompter events; suggestion-card nod/shake
      needs an explicit suggestion session. Do not infer raw crown/head streams.
- [ ] Raster needs already-installed compatible firmware; stock cannot upload
      arbitrary pixels. Investigate assistant disablement separately. No OTA
      write is used as a probe.

## Display experiments in decision order

**Do not update firmware yet.** Record the version shipped with the unit,
decline OTA prompts, and establish the stock LifeLog baseline before deciding
to flash. See [firmware-decision.md](../docs/firmware-decision.md) for exact
build/version/hash choices and go/no-go conditions. This app cannot flash.

The setup screen adds a stepwise test runner, observation notes and
Record PASS/FAIL/UNKNOWN. "Requested" means a command was sent, not that the
glasses rendered it. Commands/capabilities/relevant replies and manual results
go to a bounded private log, shared as text with **Share experiment log**.
No audio is saved by these experiments. Stop experiments (or leave setup) to
return to normal application output. They intentionally change test notifications,
weather and the single `companion_test` widget; uninstall that widget afterwards.

1. **Read firmware.** Require an actual `OsVersion` reply to business 0x09
   type1; unknown is not a version. Record phone OS/date/battery and whether
   LifeLog was armed. TEXT skips raw capability probes. AUTO opens the stock
   text grid immediately while TVP2 HELLO and Glassly QUERY/IDENT run separately;
   forced RASTER waits for the result. Results are cached per selected device and
   actual OsVersion reply. A firmware change probes again; a setup forced try
   bypasses and invalidates that cached result. A silent/busy probe does not prove
   stock: return glasses home and use a setup forced try before deciding. Ordinary
   version strings do not identify an AP patch.
2. **Glyph pages.** Test raw rows `▀▄▌▐█░▒▓`, `▖▗▘▙▚▛▜▝▞▟`,
   `┌─┬┐│├┼┤└┴┘`, `←↑→↓↖↗`, `■□▲△●○◆★`, `⠿⣿`, and an emoji plus
   U+E000. Rows are padded to 26 code points using U+3000; font2 fallback uses
   returned columns/rows and another page. Repeat updates to test the whole-frame
   prefix and compare each glyph's advance/ink against U+3000. These pages
   bypass the app's normal ASCII/emoji replacement. Blank/tofu/variable-width
   results mean leave block graphics OFF. HALF offers 26×14 samples; only
   proven quadrants justify QUADRANT's 52×14. Braille is not presumed usable.
3. **Notification icons.** Step appId Spotify, WhatsApp, Telegram, YouTube,
   Gmail, then category **0..33 inclusive**. Record which resident image appears
   for each, including repeated/default results. App images are selected, not
   uploaded. Existing app notifications are otherwise not rerouted by this feature.
4. **Weather icons.** Step **100,101,104,150,151,300,302,305,310,400,501,2001**.
   Look at the stock dashboard/home card, not the companion caption page.
   Record grouped icons, missing images and day/night changes. Most are
   QWeather-style conditions; 2001's meaning is not established. Stock cards
   may already cover the owner's non-custom icon needs.
5. **A2UI install, inspect, uninstall.** Uses launcher 0x0F type18 and the
   stock protobuf envelope; `payload.data` is an object and `extras` holds
   the widget JSON string. The full AA55 frame is checked ≤509 bytes.
   Expected install response is **type19, code0**; also require a visible
   "Companion 7392" widget. A reply alone is insufficient. Installation is
   reported disabled on ≥1.0.5.5, while old stock may expose the runtime.
   This test does not clear all widgets or replace the todo list.
6. **Native raster, after a separate firmware decision.** A detected backend
   enables its pattern button. A forced attempt is behind confirmation and
   must still pass real open/ACK checks. Check native 540×220 identity output, thin lines, all edges and grey settings.
   FIT/STRETCH only apply when source and target sizes differ.
   TVP2 deduplicates whole frames, but always sends four strips when changing;
   Glassly uses aligned box deltas, with possible tearing across split boxes.
   Check pause/exit, ten-minute TVP2 session renewal, Glassly lease, crown,
   disconnect/reconnect, phone background and power cycle. Mirror updates only
   after successful writes/commits; matching phone pixels do not prove optical FPS.
7. **The decisive LifeLog test.** With baseline wake→audio→end working on stock,
   test each chosen display with LifeLog off, then armed and between rounds.
   Trigger ten speech/silence rounds, then 1/10/30-minute combined runs. Require
   actual audio/transcripts and VAD/VPU metadata; an enabled switch or visible
   raster alone is not a pass. Test calls/assistant interruption and resumption.
   Turbo's newer idle copies statically skip LifeLog's busy switch; Glassly's
   original gate is expected to refuse it. A refused/broken combination means
   retain stock/text or validate the separate continuous-stream + phone speech
   detection branch first. Use the app's Ambient capture "continuous" source for that.

Caption push-to-talk temporarily **closes raster** before starting its stock
audio session. Stopping capture can reopen raster. This is a deliberate page
ownership transition; it is not a claim that caption audio runs inside the
custom page. LifeLog's independent wake route must be validated separately.

Display preferences live in `glasses`: `display.mode` (AUTO default, TEXT,
RASTER), `display.fit` (STRETCH default, FIT), `display.levels` (16 default;
2/4/8/16), `display.blocks` (OFF default, HALF, QUADRANT). AUTO falls back to
text after a probe or session failure; forced RASTER stays without output and
reports the problem. Change mode or reconnect to retry a session failure; use a
setup forced try to bypass a cached capability result. A crown exit releases all
app display pages until the next controller press (`reclaim()`) in that connection.
A real disconnect/reconnect starts a fresh display session and clears that hold.
Native raster cap is 10 fps with one in-flight frame and one newest pending;
the existing stock text cap remains 8 Hz.

## Clock app hardware checklist (requirement 7)

Debug builds run any Clock op like the assistant would: `python android/tools/glassesctl.py raw /clock op=timer.start duration=10s label=Test`
(also `op=timer.list`, `op=alarm.set time=7:30am days=weekdays`, `op=alarm.dismiss`, ...). Nothing here was verified on hardware yet.

- [ ] Open Apps, page 3, Clock. Hub, Timer, Stopwatch, Alarms and the ringing card are legible at XL on the lens; repeat with the stock text display (26x7).
- [ ] 10 s timer from the debug endpoint, glasses on Home: the weather row counts down ("Timer 0:07"); at 0 the ringing card takes over, the phone plays the alarm sound and vibrates, and a heads-up "Timer done" notification shows. Y on the remote stops all three; repeat stopping from the phone notification (Stop / +1 min).
- [ ] Pocket test: 3-minute timer, lock the glasses (minus), phone screen off. It rings within about a second of the target (`glassesctl.py trace --last 50`, category Clock) and lights the dark lens; after Y the glasses lock again.
- [ ] Process death: 2-minute timer, `adb shell am kill io.github.rayneohud.companion` (or your `-PappId`), still rings (AlarmManager restarts the service). Reboot with a 5-minute timer running: it rings after boot, or shows silently ("since ...") if more than 10 minutes late.
- [ ] Stopwatch: start, lap twice, leave the app for a minute, return: still counting; X twice resets.
- [ ] Alarm two minutes ahead: status-bar alarm icon appears (`adb shell dumpsys alarm | grep -A3 "Next alarm clock"`); rings; B snoozes 9 min; second ring Y stops. Unanswered: rings 5 min, then snoozes by itself (up to 3 times).
- [ ] During a phone call a timer expiry shows the card after the ringing call card and only vibrates on the phone.
- [ ] Google Clock import: start a 1-minute timer in the phone's Clock app; it appears on the glasses Timer screen marked PHONE; Y pause / resume work through Clock's notification actions (if not, the glasses say "use the phone": capture `adb shell dumpsys notification --noredact` for `com.google.android.deskclock` and update `ClockNotifications` patterns). When it fires, the glasses card appears and Y stops Clock's ringing.
- [ ] Export (Clock settings, Timers to phone On): a glasses timer also appears in Google Clock with the phone locked; `adb logcat | grep -i "Background activity"` shows no block. Turn it Off again (default).
- [ ] The assistant: "set a 2 minute tea timer", "what timers are running", "cancel it" (tools `timer_start`, `timer_list`, `timer_cancel`).
- [ ] Phone alarms (EXPERIMENTAL, `clock/PhoneAlarms.kt`). Android has no API for Google Clock's alarms (its provider answers "No external queries"), so the accessibility service drives Clock's own screen. After installing, check RayNeo Companion is still on in Accessibility (the service now also reads window content; `adb shell dumpsys accessibility | grep -A1 "RayNeo"` shows capabilities 9, not 8). If it is off or still 8, turn it off and on again.
  - Phone unlocked, glasses on: Clock, Alarms, scroll to PHONE ALARMS, Y on "Read phone alarms". Google Clock opens for a second or two, scrolls through the list, then the previous app returns; the glasses list every alarm (time, Clock's repeat text, label, On/Off) and the row shows "Experimental - read <time>".
  - Y on an alarm that is Off: Clock opens, its switch flips, the row shows On, the flash says "Phone alarm ... on". Check in Clock that exactly that alarm changed. Y again turns it back off. Repeat for an alarm far down the list and for two alarms with the same time but different labels.
  - Lock the phone and press Y: the flash says "Phone is locked; unlock it to use phone alarms" and Clock does not open.
  - If rows are missing or labels are wrong: with Google Clock open on its Alarms tab, run `adb shell uiautomator dump /sdcard/clock.xml` then `adb pull /sdcard/clock.xml` and adjust `PhoneAlarmParser` (and its fake tree in `FakeGoogleClock.kt`).
  - The assistant via `glasses_run`: `alarm.phone_list {refresh:true}`, then `alarm.phone_toggle {time:"6:30 am", enabled:false}`.

## Phone-call device checklist (CALL-01 through CALL-10)

These require a real cellular phone and a second phone; host tests and fake calls do not verify Telecom access or call audio. Record phone model, Android version, SIM(s), default dialer, headset(s), glasses firmware and results.

- [ ] With no call permissions/association, ordinary glasses apps still work. Deny each optional permission separately; check setup explanations, Unknown caller and basic-mode limitations.
- [ ] Android 12+: associate the physical glasses or 8BitDo using Companion setup. Check call access, leave the default dialer unchanged, then receive a cellular call with the activity closed. Verify Telecom binds the non-UI service and reports calls. Confirm default-dialer ringing/UI continues normally.
- [ ] Known contact, unknown formatted number, private/withheld number, very long contact name, dual-SIM account label. Page all details on 26x7 and 20x5; verify no identity is lost. Test default-dialer notification-name fallback without contacts/call-log access.
- [ ] Lock/blank on a paged Beeper/T3 screen, then receive a call. Confirm the glasses wake, Y answers and B declines; previous focus/page and lock return after ending/hiding. X hides without stopping phone ringing; repeated state updates do not bring the card back. Home/Notifications reopens it. Hold B still goes Home.
- [ ] During a call, compare running timer, phone-side mute/unmute and glasses Y mute with the actual remote audio. X lists only available earpiece/speaker/wired/Bluetooth routes by name and marks the current one. Select each route and confirm sound/microphone at the remote phone. RayNeo must not appear as a speaker unless Android exposes it as such.
- [ ] Test both API 31-33 legacy audio routing and API 34+ CallEndpoint callbacks, multiple Bluetooth headsets, wired insertion/removal, headset disconnect mid-picker/request, rejected routing requests and timeout errors.
- [ ] B tap leaves without ending the call; Home/status shows CALL, Home's Phone row and Notifications reopen it. + tap ends, + hold does not. Phone-side/remote hang-up removes stale cards and restores the previous stack. Verify no pending microphone dictation continues after takeover and L2 does not take the call mic.
- [ ] While active, receive a second call: identify/decline it without ending the first. Answer it and verify Telecom hold behavior; end the second and return to the remaining call. Test phone-side reject, answer and disconnect.
- [ ] Unanswered calls create exactly one Phone notification; explicit decline/answered calls do not. Dismiss/clear works and missed notices survive process restart. Ordinary notifications never interrupt.
- [ ] On API 29/30 or after removing association: grant phone-state/answer permissions and test fallback answer/end; verify mute/route are phone-only and duration is labelled estimated. Verify aggregate waiting controls are disabled. Remove/revoke permissions and restart; no crash or false success.
- [ ] Run with activity closed, phone locked, battery optimization enabled/disabled, service restart and glasses reconnect. Note OEM background restrictions and any calls Telecom does not expose. No emergency-call testing is required for this checklist.

### Phone app and placing calls (CALL-11 through CALL-16)

- [ ] `glassesctl.py setup` prints `call access (MANAGE_ONGOING_CALLS): allow`; `glassesctl.py call` shows `access.place/contacts/callLog` true and, during any call, `companionBound` true (`adb shell dumpsys telecom | grep -A4 "InCalls registered"` lists `io.github.rayneohud.companion/io.lampyr.glasses.calls.CompanionCallsService`).
- [ ] Apps → Phone: Favorites lists the starred contacts then "Frequent"; ◀▶ switch tabs from every tab (also mid-list on Contacts); Recents matches the phone's call log (screened calls read "screened"); Contacts X jumps letters (wraps Z→A); B leaves from any tab.
- [ ] Favorites → Y → "Call <name>?" card → B cancels (nothing dialled) → Y dials: Google Phone shows its call UI, the glasses show "Calling" then a timer. Audio goes to the connected earbuds. + hangs up. Repeat from Recents (call back) and with hold L "call <name>" (and an ambiguous name: a short list appears).
- [ ] Incoming call from a saved contact takes over and wakes a dark lens; from an unsaved number it does not (Home row shows it, Recents lists it, no missed-call notice). Call Screen calls never show "Phone / null".
- [ ] During a placed call: X lists earbuds/handset/speaker/car by name; switching works; the lens may fade once connected and any button wakes it.
- [ ] The assistant from the glasses: hold L "call mom" → dials without a card (glasses toast "Hermes: Place a phone call") and the call screen appears. From another chat or `glassesctl.py call call.place '{"contact":"mom"}'` → the "<assistant> wants to call" card waits for Y; B returns `denied`. `call call.place '{"number":"911"}'` and `'{"number":"*#06#"}'` are refused and nothing dials.

## Ambient capture hardware checklist

Use phone setup → **Ambient audio capture**. Capture is off until manually enabled;
the phone settings and ongoing notification report source/state, retained duration,
conversations and storage. No new glasses screen or undocumented recording-LED
control is sent. The existing memo RECORDING/READY and dictation LISTENING screens
remain unchanged. Verify the real hardware LED before any always-on use.

The protocol specification distinguishes LifeLog A3 **mono** batches (240-byte
Opus frames, VAD/VPU masks) from prompt-page `0x17` **48 kHz stereo** (channel 0
wearer/bone; channel 1 forward/room), and assistant voice **16 kHz mono**. Ambient
never ingests assistant-voice packets or mislabels LifeLog as independent stereo.
Raw mono and stereo packets can share the two-channel Ogg master: mono decodes
to dual mono without re-encoding. VPU marks LifeLog wearer speech; channel-energy
ratio marks genuine stereo packets. These are tags, never automatic deletions.

Auto starts with A1 → A2/A8 → A3 → A5, and switches to prompt continuous/phone VAD
only after ten seconds with audio but no wake. With no audio or wake, select
Continuous manually to test it. Ambient setup waits for the display decision
before arming LifeLog or opening a prompt. Text, raster, caption mic and continuous prompt have exclusive page
ownership: continuous capture closes the display page, and caption capture closes
the prompt first. No simultaneous text grid and stereo prompt mic is promised.
LifeLog's switch is not a page: it may accompany stock text or TVP2's patched
busy gate, subject to the hardware checks below. Glassly keeps LifeLog paused
while its page owns the display; select Continuous to test the separate prompt
stream. A raster refusal with LifeLog armed is recorded in the experiment log as
"raster refused while LifeLog armed". A switch left armed by a dropped connection
is disarmed before making the next display decision.
A prompt page exit is reported and requires pause/resume to reopen. A7's request
schema and cache behavior remain unverified and are gated by the diagnostic flag;
A4 metadata is logged without importing cached audio or relying on it for coverage.

Record firmware, Android version, local/UTC time, duration, observed source and
battery/temperature before and after each test:

- [ ] Channel identity: speak wearer-only then room-only, inspect stereo TOCs,
      decoded channel energy, VPU masks and timeline wearer tags. Confirm A3's
      actual channel count and 240-byte framing on this firmware. Test occasional
      mono packets; do not mistake dual mono for separated microphones.
- [ ] Wake reliability: silence, quiet speech, external speech, wearer speech,
      head movements, background noise, repeated A1/A5 rounds and denied switches
      (mic permission, folded temples, low battery). Check task IDs, frame counts,
      VAD/VPU bits and AA55 sequence identity across BLE/RFCOMM.
- [ ] Coexistence: normal display/navigation with LifeLog; switch into/out of
      continuous prompt mode; press controller/crown and confirm whether page
      ownership stops audio. Dictation and tap-toggle memos must preempt ambient,
      retain separate memo WAVs, and leave exact timeline gaps before ambient resumes.
- [ ] Recording indicator: verify LED while armed, during speech/continuous mode,
      on pause/disable, after deliberate disconnect and after unexpected RF loss.
      A lost socket cannot deliver A6/disarm; never assume the LED went out.
- [ ] Drops/recovery: power-cycle glasses, go out of range and restore the link;
      check new conversation rows, no fake silence, original packet framing and
      playback across chained Ogg streams after phone process kill/local midnight.
- [ ] Cache experiment only: enable A7 probe after reconnect, inspect A4 frame
      availability/completion metadata. No pre-wake/disconnected coverage is claimed.
- [ ] Endurance: compare LifeLog and continuous battery drain, phone/glasses heat,
      storage/CPU and dropped packets at 1/10/30 minutes and a full local day.

Phone-device acceptance also needs real MediaCodec Opus→AAC/MediaMuxer playback
and export chooser/import tests (including continued capture during export), precise
location/FGS permission denial and background restart, reused/start/end fixes,
160.9344 m movement threshold, step/speed motion heuristics and OEM power limits.
The host's strict Ogg parser and optional FFmpeg test verify software container
behavior, not Bluetooth firmware, codec-vendor behavior or delivery-target compatibility.
See [README.md](README.md) for paths,
CSV schema, recovery, retention, sharing and privacy boundaries.

## Capture a Bluetooth HCI snoop log

1. Enable Android Developer options (tap Build number seven times), then enable
   **Bluetooth HCI snoop log**. OEMs may offer Disabled/Filtered/Full; use Full
   for a reproducible local diagnostic when available.
2. Toggle Bluetooth off/on if required by the phone. Reproduce one short failure:
   note wall-clock time, scan/device selection, the setup state/error, and whether
   a pairing dialog appeared. Keep other Bluetooth traffic minimal.
3. With USB debugging enabled and the computer authorized, run:

   ```powershell
   adb logcat -c
   # Reproduce once, then:
   adb logcat -d -v threadtime > rayneo-logcat.txt
   adb bugreport rayneo-bugreport.zip
   ```

   Some OEMs expose `btsnoop_hci.log` in shared storage; others put it inside the
   bugreport under a Bluetooth logs directory. On production Android `/data/misc/
   bluetooth/logs` usually cannot be pulled directly without root; use bugreport.
4. Open `btsnoop_hci.log` in Wireshark. Inspect BLE advertising manufacturer
   `0x5716`, ATT service `B81D`, CCCD, pairing characteristic, AA55/55AA data and
   SDP/RFCOMM for the all-9 UUID. Confirm actual frame boundaries/CRC and response
   SIDs/sequences. A link-key error after valid BLE auth points to the classic bond.
5. Disable snooping after the capture. Keep diagnostic files local or redact
   device addresses, notification text and microphone payloads before sharing.

Licensing: [NOTICE](NOTICE), [Apache-2.0 license](LICENSE-OpenRayneo).

## Independent-review phone acceptance

- [ ] On API 29/31/35, grant Microphone with Main/setup/pocket visible, confirm
      the armed notification, lock the phone and test phone PTT, memo and the assistant.
      Repeat after boot/background restart: unavailable capture shows the phone-app
      fix; reopening recovers. Deny/regrant permission and verify the red health row.
- [ ] With RECORD_AUDIO denied and no microphone foreground type, verify actual
      Bluetooth wearer speech in background dictation, memos and the live assistant. Test
      glasses disconnect and speaker/AEC fallback; unavailable phone capture must
      report the fix. A successful start is not evidence of audible speech.
- [ ] Enable optional ambient location, reboot, then resume Main/pocket. Check
      location status in Ambient notification/setup and actual coordinate rows;
      denial must leave glasses audio running. No background-location grant is assumed.
- [ ] Lose L2 release: at 120 seconds capture stops, audio submits and held state
      clears. Stall transcription: no indefinite wake; five-minute timeout ignores
      late responses. Brush L2 below 450 ms: no send or lost earlier preview.
      Genuine holds must retain initial speech. Inspect wake locks through cleanup.
- [ ] Hold GATT R2/arrows beyond 15 seconds; layer stays held. Toggle Bluetooth
      and revoke Nearby devices: user-action status stops retry churn. Re-enable,
      verify recovery, retained GATT reconnect and backoff on real link errors.
      Learn keys mid-link without disconnecting or releasing the layer.
- [ ] Secure-keyguard pocket notification action works without unlocking. One/two
      fingers or a palm cannot exit; three fingers held two seconds, phone volume
      and Back exit deliberately, with Pocket mode off confirmation.
- [ ] Ring a cellular call with companion stopped: verify startup/takeover, basic
      answer/decline during binding and notification recovery on rejection. Y/B
      inside 500 ms do nothing; globals cannot bury ringing; X deliberately hides.
      Answer while glasses-locked, leave with B/Home, retain controls until hangup
      restores the lock. Answer/end a waiting call, then Y resumes the held call.
      Verify basic controls recover at OFFHOOK and screened calls do not take over
      until simulated/real ringing. Test external permission grants and destruction.
- [ ] During the live assistant and voice-turn fallback test cellular interruption, local/
      remote hangup, focus loss, network loss and service destruction. Verify audio
      stop, focus/wake release, exclusion closure and no resume over a cellular call.
- [ ] Start audiobooks from locked background, existing player and cold player;
      rejected starts show the playback-specific fix. Accessibility/CDM/Telecom
      rejection posts recovery when notifications are allowed; verify logs and
      manual recovery with notification permission denied.

## Shizuku screen-off input hardware checklist

Record phone model/build, Micro firmware/mode, Shizuku version, learned
name/vendor/product, every node path, and PASS/FAIL/UNKNOWN with measured latency.
These are real-phone acceptance checks; emulator success does not establish them.

- [ ] Install/start Shizuku via Wireless debugging (shell uid 2000), approve the
      companion, and start Shizuku input. Verify live installed/running/permission/
      bound rows and the learned node name/IDs. Compare node count with
      `adb shell getevent -i`; all keyboard/consumer/gamepad nodes must be covered.
      Verify **exclusive** only when every matching node is open and grabbed.
- [ ] With the phone locked and truly screen-off (no pocket activity), press each
      of the 16 buttons once. The **first** down/up must reach the glasses and
      the screen must stay dark on **every** press when exclusive. Test unknown
      keys and another foreground text field: no key must leak into Android.
      Other keyboards and phone power/volume/touch must remain usable.
- [ ] Measure press-to-glasses latency immediately after screen-off and after
      idle suspend. Check taps, repeat value 2, B/plus/R2 holds, portrait arrows,
      R2 + diagonal hat/keys, and L2 PTT >15 seconds with normal release/submission.
      Confirm the runtime's partial wake lock is acquired at first Binder ingress,
      held during keys/dictation, and released about five seconds after idle.
- [ ] If the phone denies EVIOCGRAB, verify **shared: screen will still wake**,
      first-press delivery and exactly one action per tap with accessibility
      enabled, including rapid double taps and holds. A waking screen is expected
      in shared mode and fails the owner's dark-screen requirement. If evdev open
      itself fails, record the error and confirm accessibility fallback honestly
      retains the lost-first-wake-key limitation. Do not count permission as grab.
- [ ] Let the Micro sleep/disconnect, including during B/plus/R2/L2 holds. Ensure
      media/PTT/held state is abandoned without phantom taps. Wake/reconnect it:
      all nodes should be rediscovered/regrabbed (roughly one polling interval
      after Android creates them). Record whether the controller's reconnecting
      first press arrives; Bluetooth reconnection itself can lose a press.
- [ ] Stop input, switch sources, kill/force-stop the companion while holding a
      key, then kill Shizuku. Confirm controller keys immediately work in Android,
      no orphan shell reader/grab remains, and the app releases all held state.
      With the companion running, stop/restart Shizuku and deny/regrant its app
      permission: accessibility fallback and automatic return must match the
      notification and live rows. Test multiple matching nodes and node loss.
- [ ] Reboot: verify no stale grab, Shizuku unavailable/fallback shown, then start
      Shizuku again and reopen the companion. Verify binding, exclusive capture,
      and phone microphone eligibility recover. Do not assume wireless auto-start.
- [ ] Leave the locked, screen-off phone unplugged and stationary for **one hour**
      so Doze can engage; test the first press, latency, PTT and subsequent presses.
      Repeat after controller sleep and on the owner's usual Wi-Fi/battery policy.
      Record drain/temperature and any OEM process killing. Emulator/partial wake
      lock tests cannot prove a Bluetooth input IRQ will wake this real phone.
- [ ] With Shizuku active, re-learn known and **raw N** unknown keys. Bind all 16,
      test hat directions and confirm the JSON mapping survives restart. Finish
      test mode or leave setup and confirm normal actions resume. Run the same
      checks after any Micro K/D/S mode change and re-learn changed identities.

## Controller / locked-pocket hardware checklist

The accessibility/pocket and vendor BLE checks below use those sources explicitly;
disable Shizuku input first when checking Android's original wake-key behavior.

- [ ] Pair the Micro to the phone in K mode; learn all 16 keys. Verify descriptor
      identity survives reboot/reconnect; re-learn after changed pairing identity.
      With setup closed, exercise every key, repeats, 450 ms holds, portrait
      rotation, R2 + arrows, minus lock and L2 talk.
- [ ] With companion stopped, the learned pad's mapped **and unmapped** keys must
      be consumed and start the foreground service. No controller text should
      enter keyguard/foreground fields. A second keyboard (including one with the
      same display name) and phone keys must still work normally.
- [ ] Screen off mid-hold (B/plus/R2/L2): pending taps must not fire, the media layer
      clears, PTT stops/transcribes (the assistant sends; reply screens retain preview), and
      the next press works. Hold L2 beyond 15 seconds; it must not time out.
      Disconnect Bluetooth mid-hold and repeat.
- [ ] In each K/D/S mode verify that the first non-interactive HID press only wakes
      Android, never navigates or types, and its lost up leaves no held state.
      Confirm no mode is claimed to solve this by itself.
- [ ] Open black pocket mode manually and from the notification with phone locked.
      Verify black/minimum brightness, immersive bars, touch blocking, three-finger 2-second
      exit and phone volume/Back exit, secure keyguard still present afterward,
      and all 16 controller keys delivered during a long pocket session.
- [ ] Enable locked-wake pocket option with accessibility bound. Turn screen off,
      wake with controller, wait for black activity, then press again. Verify lost
      wake press is not replayed. Also try power/other wake (the broadcast has no
      origin), accessibility unbind, OEM background restrictions and notification
      denial; manual entry remains available when automatic launches fail.
- [ ] Run BLE probe in all three Micro modes; record whether it advertises, service
      UUIDs, non-HID notification availability and button byte changes. If none,
      show the no-usable-characteristic result; do not substitute restricted HID.
      If usable, learn all 16 press/release cycles, test simultaneous R2 + arrows,
      >15-second PTT, screen-off delivery, disconnect/reconnect, permission loss,
      Bluetooth off/on, process restart and coexistence with glasses BLE/RFCOMM.
- [ ] Compare keyboard and vendor BLE selection: switch while held, ensure exactly
      one source navigates, probe temporarily releases/pauses production input,
      malformed report lengths do not create keys, and closing setup stops scans.
- [ ] Associate controller optionally via CDM (classic and/or BLE); retain existing
      glasses association. API 31+ presence should start companion on appearance
      and release keys on disappearance. Verify API 29/30 association-only path,
      Android 15 background/FGS restrictions and re-pairing/address changes.
- [ ] Verify live health rows and foreground notification against real accessibility
      bind, controller presence/source, battery exemption, notification listener
      and glasses connection. Deny/regrant permissions and confirm honest status.
- [ ] Verify partial wake lock while held and through dictation, ~5-second idle
      release and cleanup on service destruction (`adb dumpsys power`). Measure
      30–60-minute battery drain/temperature in black pocket mode on this panel;
      screen-on/OLED-black is a mitigation with a real power cost.
