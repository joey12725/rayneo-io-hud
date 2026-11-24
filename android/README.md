# Android companion foundation

Native Kotlin/Canvas port of `simulator/index.html`, package `io.lampyr.glasses`. The app selects `LiveDataStore` when a gateway token exists and otherwise uses the offline `FakeDataStore`. The native master is 540×220 4-bit green (PRIMARY=15, SECONDARY=10, TERTIARY=6; black=0); connected preview follows the text actually written or the grey raster acknowledged by custom firmware. No WebView or Compose.

## Phone app layout

The phone app has four tabs (`MainActivity` hosting the pages in `phone/Pages.kt`): **Home** (lens preview, glasses/controller/data state, setup progress), **Remote** (an on-screen copy of the 16-button controller with a live legend), **Tools** (pairing, controller, pocket mode, passive listening, calls, display lab, activity log, setup guide) and **Settings** (gateway, assistant and owner names, integrations, notification policy, avatar pack, behaviour, app order). Every screen is built from the small View kit in `phone/Kit.kt` and the line icons in `phone/Glyph.kt`; use those rather than raw `Button`/`TextView` so screens stay consistent. Older text below that says "Companion setup" refers to what is now split across the Settings tab, **Tools → Controller** and **Tools → Phone calls**.

## Build and run

Requires JDK 17 and Android SDK platform/build-tools 35. The Gradle 8.10.2 wrapper is checked in, including its JAR. `local.properties` is local and ignored.

No submodules or private modules are needed. A plain `./gradlew assembleDebug` needs no secrets: the app starts in
demo mode with synthetic data, and the gateway address, access token, assistant name and integrations are entered
on the phone's **Settings** tab (or loaded from the gateway with **Load from gateway**).

Build properties (pass with `-P`):

| Property | Default | Effect |
|---|---|---|
| `appId` | `io.github.rayneohud.companion` | `applicationId`; install a fork side by side. `tools/glassesctl.py` reads the same id from `APP_ID`. |
| `embedToken` | `false` | `true` compiles `GATEWAY_TOKEN` from `secrets.properties` into BuildConfig (private builds only). |
| `requireGateway` | unset | Fail `assemble`/`install` when `GATEWAY_URL` is missing. |

`android/secrets.properties` (gitignored, see `secrets.properties.example`) may hold a default `GATEWAY_URL` and,
with `-PembedToken=true`, `GATEWAY_TOKEN`.

From the repository root:

```sh
export JAVA_HOME=/path/to/jdk-17 ANDROID_HOME=/path/to/android-sdk
echo "sdk.dir=$ANDROID_HOME" > android/local.properties
./android/gradlew -p android testDebugUnitTest assembleDebug
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.github.rayneohud.companion/io.lampyr.glasses.MainActivity
```

On Unix, use `android/gradlew -p android testDebugUnitTest assembleDebug` with the SDK location configured for that machine. The APK is `app/build/outputs/apk/debug/app-debug.apk` relative to this directory. Android 10/API 29 is the minimum.

The activity has a nearest-neighbour mirror, all 16 touch buttons (including press/release/hold), a contextual bindings legend, a grip switch, and a shortcut to Android's accessibility settings. Enable **RayNeo iO HUD** there for global keyboard capture. Hardware D-pad input is physically rotated for the portrait grip; touch arrows are already logical display directions. Key defaults match architecture §2.4; `KeyMap.bind()` persists JSON overrides in SharedPreferences and the phone setup screen includes a 16-button learn-keys wizard.

## Raster output and first-week experiments

Read [the firmware decision](../docs/firmware-decision.md) before accepting an
OTA update. **Record the shipped OsVersion and decline updates initially.**
The setup screen has AUTO / TEXT / RASTER, 2/4/8/16 grey levels,
and an experimental HALF/QUADRANT option defaulting OFF. AUTO requires a
TVP2 HELLO capability or Glassly IDENT bit4; firmware version alone is not a
capability. Stock falls back to the verified caption grid. Forced RASTER
reports unsupported/failure and does not silently transmit text. A confirmed
"Force try" sends only that protocol's normal handshake; it never flashes.

The original `RasterTransport` implementations are in `glasses/raster/`:
TVP2 uses raw launcher business 0x0F over the authenticated RFCOMM link;
Glassly uses raw business 0xF0 over the existing authenticated BLE write queue.
No modified companion or AirPlay SDK is linked. Guest gating, throughput,
physical output and audio coexistence are unverified. [Wire contract](../docs/raster-wire.md)
records CRCs, packing, session rules and source pins; [NOTICE](NOTICE) records
interoperability provenance. No firmware binaries/updater are bundled.

`RasterFit` is Android-free Kotlin. The 540×220 master and both supported
540×220 raster targets take its identity path, preserving every source pixel.
FIT/STRETCH apply only to other dimensions and are hidden in current setup.
`RasterFit.fromMaster` quantizes the native 0–15 brightness codes to 2/4/8/16
levels without re-encoding gamma. Four levels map black/tertiary/secondary/primary
to 0/85/170/255; eight map to 0/109/182/255; sixteen to 0/102/170/255. Two levels
map every positive code to 255, with no dithering or disappearing detail.
The legacy 1-bit fitter remains available for other dimensions and experiments;
it area-averages coverage before gamma encoding when resampling is needed. TVP2 requires all four strips on a changed frame; Glassly
uses aligned changed boxes. One frame is active and the newest pending frame
wins, at ≤10 fps. The mirror waits for raster ACKs, or a successful stock text
write; those are not optical-refresh measurements. Caption mic capture closes
raster first; the in-app Ambient capture (continuous / auto source) supplies continuous
capture/phone speech detection fallback.

Run these from **Real glasses setup → Display experiments**, one step at a
time, with LifeLog initially off; repeat the relevant tests with it armed:

1. Read firmware and save the version; unknown means no response, not success.
2. Page through raw glyph rulers (blocks/quadrants, box drawing, arrows,
   symbols, Braille, emoji and U+E000), padded to 26 cells with U+3000.
   Confirm widths before enabling block graphics; font-2 fallback pages use
   its returned dimensions. Half blocks give 26×14, quadrants 52×14 at font1.
3. Step notification app IDs, then categories 0..33. Record distinct resident
   icons; these tests do not upload artwork.
4. Step weather 100,101,104,150,151,300,302,305,310,400,501,2001. Observe the
   home/dashboard page; record grouped/unknown mappings. This changes weather.
5. Install then uninstall the minimal A2UI widget (frame ≤509 bytes). Expect
   type19/code0 **and a visible widget** on supported old stock. Newer stock
   may ignore/refuse the install; absence of reply proves no support here.
6. On an already patched unit only, send the enabled raster pattern; forced
   attempts need the screen's confirmation. Check fitted edges, thin strokes,
   greys, pause/exit, delta correctness, reconnect and power cycle.
7. Verify LifeLog wake→audio→end rounds during display, then 1/10/30-minute
   combined runs. A busy page with LifeLog armed decides whether custom raster
   requires the separate continuous-audio fallback. Do not flash until the
   version-specific plan and go/no-go checks in the decision document pass.

Use Record PASS/FAIL/UNKNOWN and notes after each observation, then Share
experiment log. A bounded private `files/display-experiments.log` records
requests, capability/page failures and relevant replies; it does not record
microphone data or gateway secrets. Stop experiments restores app output;
leaving setup also releases the experiment. Detailed interpretation is in
[GLASSES.md](GLASSES.md#display-experiments-in-decision-order).

Raster tests include independent decoders (not production-code round trips),
CRC vectors, full/box packets, per-eye ACKs, flow control, capability parsing,
mode selection, block conversion, and fitted hashes of the existing home/assistant/
approval PNG goldens. `tools/raster_reference.py` independently generates the
fit hashes with Python pixel-footprint intersections; Pillow is a developer
tool only and is not needed by the Android build. `secrets.properties` is
optional: an absent file builds with empty gateway defaults and demo data.

## Source tree

```text
app/src/main/
  assets/                    avatar/default placeholder pack + extracted simulator fixtures (demo.json)
  java/io/lampyr/glasses/
    MainActivity.kt          Mirror and controller
    render/                  Gfx/P, bitmap font, icons/emoji, rich text, avatar/ (AvatarPack, manifest, loader), OneBit, TextGrid
    ui/                      View contract, stack/context, list/reader, chrome
    ui/screens/              Home, Apps, assistant/menu/call, Report, Dictation, Beeper family,
                             Notifications, T3 family, Media family, People, Health,
                             Calendar, Status, HA, Docs, Teleprompter, Memo, Settings/order
    data/                    Serializable immutable models, repository contracts, FakeDataStore
    input/                   KeyMap, v3 InputEngine, accessibility service
    audio/                   Dictation events/interface and simulator implementation
    glasses/                 Frame, GlassesTransport, PreviewTransport
    service/                 Foreground service and render scheduler
app/src/test/                Unit/flow/native rendering tests + 69 reference PNGs
tools/                       Fixture extraction and deterministic Python golden capture
```

The service limits rendering to 10 fps. Changes request a frame immediately subject to that cap; animation schedules subsequent frames. Static readers, quiet-wait blanking, paused teleprompter, memo readiness and dictation preview can stop animation scheduling. Locked frames contain black pixels and an empty text grid.

## DataStore integration contract

`DataStore.kt` is the source of truth. All properties and lookup methods read cached memory only. Screens call refresh hooks on open. Every side effect is a suspending function returning `ActionResult(ok, message, id)`. `Ui.perform()` displays errors and confirmations and keeps failed dictation previews available. The implementation must move network/disk work off the main dispatcher, publish immutable snapshots, then notify registered listeners on the main dispatcher. `addChangeListener()` returns an unsubscribe function. `onScreenChanged(kind)` supplies the assistant's visibility context. `Ui` and the store use a child scope owned by the service; saving phone setup replaces that scope without disconnecting the glasses transport.

Models use the gateway names (`network`, `sender`, `mine`, `text`, `reactions`, `machine`, `id`, `project`, `provider`, `status`, `usedPercent`, `resetsAt`, `availableCount`, `positionSec`, `durationSec`, etc.). T3 identity always includes **machine and id**. `Step.since` is a monotonic baseline and `elapsedSec` is the elapsed value at that baseline. A live adapter should derive them from `startedAt`; derive `quietSec` from `lastOutputAt`. Usage `elapsedFraction` is the bar's time-position tick; derive it from `resetsAt` and `windowDurationMins`. Gateway times are ISO UTC; fixture labels such as `7:41 PM` remain display strings in the fake store.

| Repository | Cached reads | Async methods |
|---|---|---|
| Store | `home` | `refreshHome`; lifecycle: `addChangeListener`, `onScreenChanged`, `close`; fake audio samples: `dictationSample` |
| Assistant (`joy`) | `snapshot` (status, anim, since, prompt, text, sessionId), `call` (active, mode, muted, endedReason) | `refresh`, `setListening`, `send`, `newConversation`, `startVoiceCall`, `muteVoiceCall`, `endVoiceCall` |
| Beeper | `chats`, `messages(chatId)` | `refreshChats`, `refreshMessages`, `markRead`, `send(chatId,text,replyTo)`, `react(chatId,messageId,key,remove)` |
| T3 | `threads`, `settled`, `usage`, `environments`, `projects`, `detail(machine,id)` | `refreshThreads`, `refreshThread`, `refreshSettled`, `refreshUsage`, `refreshEnvironments`, `refreshProjects`, `visit`, `send(machine,id,text,mode)`, `respond(machine,id,requestId,decision,answers)`, `newThread`, `resetUsage` |
| Media | `nowPlaying`, `books`, `playlists`, `devices`, `youtubeLists`, `videos(key)` | `refresh`, `refreshVideos`, `control(action,value)`, `play(source,id)`, `transfer(deviceId)` |
| Calendar | `agenda`, optional `dateLabel` | `refresh` |
| People | `snapshot` (heard, recent, muted on cards) | `refresh`, `refreshPerson`, `remind`, `mute(personId,muted)` |
| Health | `metrics`, optional `updatedLabel` | `refresh` |
| Home Assistant | `panel` | `refresh`, `scene(id)`, `alarm(time,style)` |
| Docs | `documents` | `refresh`, `refreshDocument`, `delete` |
| Report | `snapshot` | `refresh` |
| Notifications | `items` | `refresh`, `dismiss`, `clear` |
| Memos | `snapshot`, `script` | `refresh`, `toggle` |
| Status | `snapshot` | `refresh` |
| Settings | `snapshot` | `refresh`, `update` |

Beeper priority (BEEP-PRI, `data/BeeperPriority.kt`): `Ui.beeperPrefs` holds `beeper.order` (`PRIORITY` default, or `RECENT`) in the `glasses` SharedPreferences file. `BeeperPriority.group` builds the list from the gateway's row tiers (`priority`, `importance`, `pinned`, `glassesPinned`; older gateways fall back to the same local rules) plus a phone-only classifier lift (a Beeper notification rated 0.7+ for that chat in the last 6 h): pins, important, unread, the rest, then one `Quiet (N)` row that Y expands. The repository adds `useOrder`, `setPin` (gateway `beeper-pins`), `find` (local fuzzy match with phone-contact nicknames merged with gateway `/beeper/find`) and `chat(id)` (also finds chats beyond the loaded page). Hold L on the list dictates with the raw `nav` target and `TalkTarget.autoSend`, so a clear match opens without pressing Y.

T3 `respond.answers` is `Map<String,List<String>>` so one model covers single and multi-select; the gateway adapter should send a scalar for a single answer when required. Decisions are `accept`, `acceptForSession`, `decline`. Message modes are `send`, `queue`, `steer`; the gateway appends `/glasses`. Media control actions are `toggle`, `play`, `pause`, `next`, `previous`, `volume` (0–15), `seek` (relative seconds), `speed`. Sources are `spotify`, `abs`, `youtube`.

To install a live store, set `GlassesService.storeFactory = { context, scope -> ... }` before starting the service. Add real transports to `service.transports`. `GlassesTransport` exposes `connectionState: StateFlow<ConnectionState>`, suspending `show(Frame)`, and optional `connect()/disconnect()` hooks. An input `Frame` carries **14,850 bytes**, 540×220 pixels packed continuously row-major/MSB-first without row padding (540 is not byte-aligned; adjacent rows share a byte), the grey master bitmap (green channel = 0–15 code ×17), and a 26×7 default text grid. The packed bits are the >0 compatibility mask; `GreyFrame` holds one byte per 4-bit pixel during rendering and reconstructs it for the raster hand-off. The phone already draws the grey bitmap, and the acknowledged raster mirror uses the same gamma-1 code mapping. Sent-only mirror frames may have empty bits and a fitted bitmap. `PreviewTransport.masterFrame` separately retains input for reconnect seeding; never feed the sent mirror into a fitter. A transport must not block the UI dispatcher. Frames are shared and immutable; do not recycle their bitmaps in a transport.

Real audio implements `Dictation.start(target,onEvent)`, `stop()`, `cancel()`. Events are level, transcribing, enhancing, done(text), error(message). `start(target, context, onEvent)` (default: ignores context) carries the destination to `/voice/dictate`: `TalkTarget.context` is `chatId` for Beeper and `machine`/`thread` (or `project`) for T3; `ControllerRuntime.dictation()` forwards it. `done(text, mentions)` carries spoken Beeper mentions; the preview underlines each `@Name` and Y sends through `TalkTarget.sendRich` so the gateway can turn them into real mentions. PTT starts on L2 down as in simulator v3; memo consumes L2 as a tap. Back, plus, and R2 holds use 450 ms and never also fire their tap. R2 opens its layer on down and releases without play/pause if a direction was used or the hold threshold passed.

## Validation and golden provenance

`RenderingGoldenTest` uses Robolectric API 35 with `@GraphicsMode(NATIVE)`, fixed `t=0`, and **2026-11-11 7:45 PM** in a fixed test time zone (`America/Chicago`, chosen only so the fixtures never move). References are lossless exports of the approved simulator's actual 540 × 220 canvas, driven with its keyboard bindings, with Date/performance and media progression frozen. CSS preview brightness is excluded. References are regenerated directly with `tools/capture_goldens.py` using Python/Playwright, frozen time, keyboard sequences, and deterministic direct screen fixtures.

```powershell
python -m http.server 8765 --directory simulator
# In another terminal:
python android/tools/capture_goldens.py
python android/tools/raster_reference.py
python android/tools/contact_sheet.py
```

Set `CHROME` if needed; the script defaults to the installed Chrome path. `python -m pip install playwright` installs its Python dependency if absent. The browser closes at completion. `node android/tools/extract_seed.cjs` regenerates the fake-data and bitmap-font fixture JSON from the simulator. `Font5.kt` is the native bitmap-font table; update it deliberately if the simulator's font changes.

Tests compare all 118,800 **brightness codes quantized to 16 levels** per screen and fail at ≥0.5% mismatch. Actual frames, red difference PNGs, and `mismatch.md` are written to `app/build/outputs/golden/`. The checked-in result table is `golden-mismatches.md`. Original bitmap references are preserved byte-for-byte in `golden-1bit/`; every grey golden must threshold at >0 to exactly that reference. Capture also checks the live 2-level selector. Text goldens remain byte-identical.

The existing renderer/input suite includes a test covering all 69 reference screens, link normalization, wrapping/pagination/graphemes, text-grid clipping, notification decay sorting, MSB-first threshold packing, hold/tap exclusion, lock, media-layer consumption, physical grip rotation, memo routing, streamed assistant statuses, append/reply flow, and machine-scoped approvals.

Residual differences are recorded in `golden-mismatches.md`. All 68 native
references use the unchanged ≥0.5% failure threshold. Avatar portraits and the assistant
menu rail crop use identical OR pooling in both renderers (see [avatar packs](../docs/avatar-packs.md)).
Launcher/picker/badge icons use the neutral `assistant-orb` vector glyph.
Text goldens must remain byte-identical.

## First-run setup

1. Install the debug APK, open **Settings**, and enter the gateway address and access token, then turn on **Live data**. The address is the gateway's glasses API base, for example `https://gateway.example.com/glasses/v1`. Without them the app shows synthetic demo data.
2. Press **Load from gateway** under Notifications to seed the assistant and owner names, integrations and notification policy from `GET /glasses/v1/config` (the app also does this once on first contact). Edit any of them on the same page.
3. Optional: `android/secrets.properties` may hold a default `GATEWAY_URL`; `GATEWAY_TOKEN` is compiled in only with `-PembedToken=true`. Never distribute a credential-bearing APK. Saved tokens are AES-GCM ciphertext wrapped with a non-exportable Android Keystore key; backups are disabled.
3. Grant microphone permission, notification/media-session access, notification permission, and accessibility access using the setup shortcuts. Grant battery-optimisation exemption for reliable background use. Optional Start on boot enables the boot/package-update receiver. Android still controls foreground-service restarts and microphone eligibility.
4. Pair the **8BitDo Micro in keyboard mode to the PHONE**. Open **Tools → Controller** and tap **Start** under **Teach the buttons**, pressing each requested physical button once. The complete key map is saved as JSON when the last button is learned. The accessibility service reads current saved mappings and forwards keys directly to the running service even with the activity closed.
5. Notification policy: Beeper, T3 Code and the assistant reach the glasses by default; every other app stays on the phone. The policy JSON on the Settings page (same schema as the gateway's `notify-policy.default.json`) adds apps (`"apps": {"com.example.app": "popup"}`), turns kinds off, or enables the dating preset.
6. Use **Test connectivity** for a read-only `/home` request. 401/403 shows `Check token`; offline refreshes retain cached screens. Configure the real glasses using the separate glasses connection screen. Glasses-side Settings → App order persists app order, brightness, and head-gesture preferences.

## Live-layer architecture

- `data/GatewayClient.kt`: cancellable OkHttp calls, bearer auth, bounded timeouts, UTF-8 NDJSON, and long polling with durable cursor/backoff. No credentials or response content are logged. `GatewayMappings.kt` handles nullable/numeric wire values, Beeper preview IDs, assistant states, and T3 timing/send modes.
- `data/LiveDataStore.kt`: implements every `DataStore` repository. Reads use memory snapshots; private small JSON files in `filesDir/gateway-cache` retain last good responses across restarts. Screens refresh on open. While unlocked, home and badge sources refresh every 60 seconds; T3 screens refresh every five seconds. Gateway `open`, `media`, `doc`, `reminder`, `report`, `notification`, and `joy_reply` events update the corresponding phone actions or lists.
- The assistant screen preserves the previous reply in the model until a result arrives, retains compressed session IDs, and maps gateway status records to the existing animation atlas. Away replies create a `<assistant> replied` entry at importance 0.85 opening assistant. Stream and event deliveries are paired to avoid duplicate notifications. Live calls use the bearer-authenticated `/assistant/call` WebSocket after checking `/assistant/call/status`. `audio/JoyCallSession` owns 20ms PCM framing/pacing, 80ms downlink jitter buffering, playback-head-based drained, ping, mute and barge-in flush behind fakeable socket/audio/exclusion interfaces. `AndroidJoyCallAudio` owns focus, microphone FGS eligibility, wake lock, streaming AudioTrack and current media routing; it never forces SCO/HFP. Phone capture uses VOICE_COMMUNICATION with platform AEC/NS when available. Glasses capture is preferred for headphones; routing to the phone loudspeaker switches to phone capture so platform AEC has a capture session. Test acoustic echo with real output routes. Status unavailable, upgrade failure or an unavailable hangup selects the clearly labelled six-second **Voice turns** dictation fallback (text replies, no synthesized output). Calls end on B, server hangup, connection/audio loss, cellular calls and service teardown; no redial. X mutes live uplink by replacing frames with silence. Ambient exclusions cover the whole call, including fallback. YouTube opens directly to the gateway's curated list (`youtube.listName`, default Picks).
- `data/PhoneNotifications.kt` classifies allowed phone notifications through `/notify/classify`. `MediaHub.kt` observes phone media sessions through that listener's component. Active phone playback takes precedence; Spotify Connect device/volume remain gateway-backed. R2 uses the existing `StoreMediaControl` adapter into the live media repository. Volume is translated between the UI's 0–15 range and Android/Spotify ranges.
- `data/AbsPlayer.kt` owns an Android MediaPlayer, MediaSession, audio focus, and a foreground media notification. It plays returned public stream URLs, honours multi-track offsets, supports speed/seek, and syncs progress every 30 seconds and on pause. In-app playback can be controlled before notification-listener access is granted. YouTube launches `vnd.youtube:<id>` with a web fallback.
- `audio/PhoneMic.kt` captures VOICE_RECOGNITION mono PCM at 16 kHz. `GatewayDictation.kt` prefers `MicRegistry.glasses`, emits RMS levels, writes WAV in memory, and streams transcription/enhancement phases. Assistant transcripts are submitted by InputEngine to `/assistant/chat` and appear as the thinking prompt. Memos retain WAV files plus local transcript/title metadata; pending transcription/title jobs retry when the memo screen opens. Capture is bounded below the gateway's 25 MiB upload limit.
- `settings/AppSettings.kt` handles encrypted credentials, source selection, grip, notification packages, and boot preference. `phone/Pages.kt` (Settings tab) and `input/ControllerActivity.kt` supplies plain Android Views for setup, key learning, permissions, and connectivity testing.
- `GlassesService` remains START_STICKY, owns live/fake selection and real/fake dictation, and promotes its foreground type at phone microphone capture. `GlassesBatterySource` isolates live status from the transport; the service adapts RayNeo battery/connection flows. Earbuds and Oura batteries show an em dash until a provider exists.

## Gateway contract details

The synthetic fixtures in `src/test/resources/gateway/responses.json` follow route source shapes and contain no personal data or credentials. Tests cover all repository reads, action payloads, NDJSON/split lines, cursor advancement/retry, disk restoration, assistant visibility/duplicate delivery, T3 modes/answers, approval reactions, PCM levels and WAV headers. No MockWebServer dependency is used; an OkHttp interceptor supplies responses.

Differences from the prose contract handled by the app:

- `/voice/dictate?target=joy` (alias `assistant`) only transcribes; it does not submit an assistant turn. InputEngine explicitly sends its Done transcript to `/assistant/chat`. `target=raw` returns the exact words for lookups (Beeper find, Phone "call who?", alarm times).
- `/spotify/now.volume` is 0–100; the glasses model displays 0–15. Both directions are scaled.
- `/abs/play/:id` can include `tracks` with `offsetSec` and `durationSec`, plus total duration/chapter. Multi-file playback must use those offsets rather than seeking an absolute book position into one track.
- `/health/overview` values/breakdown values may be numbers and missing measurements are null; `/people/recent.seenAt`, T3 environment fields, reset timestamps, and step timestamps may be null. The adapter supplies empty/unavailable values.
- T3 ageSec/durSec/usedPercent may be fractional; the UI's integer fields are truncated while working timers retain precise elapsed time.
- `/youtube/lists` exposes one list (the gateway's `YOUTUBE_LIST_NAME`, default "Picks"). YouTube opens that video list directly; Watch Later and History are unavailable through the official API.

## Device verification remaining

JVM unit tests, native rendering goldens, synthetic gateway tests, and APK assembly run on the host. A read-only gateway smoke test with the app's OkHttp user agent returned 200 for home, Beeper chats, environments, health, Spotify now, ABS recent, YouTube lists, docs, people, and HA panel. Cloudflare rejected Python's default user agent with HTML 403; OkHttp's normal user agent succeeded.

On a real phone verify notification-listener permissions and session callbacks; R2/media keys with Spotify, YouTube, and ABS; multi-track audiobook transitions and 30-second/pause progress; Keystore persistence; all 16 learned keys with the activity closed; microphone capture/permission and foreground-service promotion; turn-based assistant; memo audio/transcription; boot/process-death recovery and OEM battery controls; background YouTube intents; and RayNeo battery/audio/display through the transport worker's integration. No device installation was performed here. Renderer layout remains unchanged; live badges, meter amplitude, unavailable battery text, and version labels use existing layout positions.

## Optional phone calls

Open **Companion setup** and choose **Associate glasses / 8BitDo for call controls** on Android 12+. Put the actual RayNeo/8BitDo in discoverable mode and accept Android's companion chooser. This creates a CDM association, separate from the existing Bluetooth connection; it never changes the default Phone app. **Check call access** checks `TelecomManager.hasManageOngoingCallsPermission()`. The manifest declares `MANAGE_ONGOING_CALLS`; this is app-op/association access, not a normal runtime permission dialog.

The separate optional permission buttons explain phone state (`READ_PHONE_STATE`), answer/end (`ANSWER_PHONE_CALLS`), saved contact names (`READ_CONTACTS`), basic-mode caller numbers (`READ_CALL_LOG`), and Bluetooth device names/routing (`BLUETOOTH_CONNECT`, already available in glasses setup). Android/installer policy can prevent granting the restricted call-log permission. Caller identity then falls back to the current default-dialer call notification's person/title, or Unknown caller. Notification access is optional. No caller details go to the gateway or its classifier. Missed-call notices are kept in private app preferences (at most 30), with Notifications dismiss/clear support.

`calls/Calls.kt` owns platform-independent snapshots, missed-call classification, route kinds, identity precedence, monotonic duration formatting, and the fake backend. `CompanionCallsService` is non-UI/non-ringing, so the default dialer retains its UI and ringtone. Android supplies the call list and individual `Call.answer/reject/disconnect`, `InCallService.setMuted`, and audio controls. API 31-33 uses available `CallAudioState` route masks/Bluetooth devices with `setAudioRoute` / `requestBluetoothAudio`; API 34+ uses only endpoints delivered by Telecom with `requestCallEndpointChange`, observes the result/current endpoint, and reports failures/timeouts. No synthetic glasses route is offered: RayNeo iO has no speakers.

`AndroidCalls` also observes `TelephonyCallback.CallStateListener` on 31+ or `PhoneStateListener` on 29/30. The deprecated, still-public `TelecomManager.acceptRingingCall/endCall` APIs supply basic controls when permission permits. Basic mode cannot observe/control mute or audio, and OFFHOOK duration includes possible dialing time. Aggregate call waiting is shown as phone-only, with ambiguous answer/end disabled. Companion mode identifies each call independently. OEM behavior, restricted permission grants and emergency/self-managed calls remain subject to Telecom policy.

Ringing calls are the explicit CALL-01 exception to NOT-01: they wake the glasses, preserve the previous screen stack/lock, and show **Y answer / B decline / X hide**. X hides only the glasses card; the phone keeps ringing. During a call: **Y mute / X audio / B back / + end**. B hold still goes Home; + hold does nothing. Home's Phone row and Notifications reopen the current call. L2 dictation is blocked during calls so it cannot take the call microphone. A ringing takeover cancels an in-progress InputEngine dictation.

Offline demo: the phone preview has **Demo incoming call / call waiting** and **Demo remote hang up / missed call** buttons. Keep Live data off. The simulator offers the same fake controls. The fake supports a second ringing call while another is active. Both bitmap and 26x7/20x5 text renderers paginate identity/details and keep call actions available across pages.

Regenerate bitmap references with the existing `python android/tools/capture_goldens.py`. `SIMULATOR_URL` can point to a local file URI (the tool enables local asset reads) or the usual served simulator. Text fixtures use `UPDATE_TEXT_GOLDENS=1`; clear that variable before the final normal test run. Tests include call-state transitions, lock/stack restoration, controller actions, missing-permission backends on APIs 29/31/35, all concrete text views, and simulator/native bitmap comparisons. See [GLASSES.md](GLASSES.md) for required device tests.

### Phone app (requirement 6)

`ui/screens/Phone.kt` is the glasses Phone app (last in the Apps menu): Favorites (starred, padded with frequent
callees), Recents (call log; Y calls back) and Contacts (A-Z, X jumps to the next letter and wraps); ◀▶ switch tab on
every tab (X too on Favorites/Recents) and B always leaves; hold L and say a name. `calls/PhoneBook.kt` holds the platform-free matching ("call mom" via name, nickname or relationship), favourites
and refusals (emergency numbers, `*`/`#` codes); `calls/AndroidPhoneBook.kt` reads ContactsContract and CallLog on the
phone only. Every placement from the glasses takes one Y on the "Call Mom?" card (`PhoneConfirm`), then
`TelecomManager.placeCall` (CALL_PHONE) dials and the call screen shows "Calling". Only saved contacts take over when
they ring (`callAlert` in `calls/Calls.kt`); unknown numbers and suspected spam stay on Home's Phone row and in
Recents. The assistant's ops are in `agent/ops/CallOps.kt` (`contacts.search`, `call.place`, `call.answer`, `call.decline`,
`call.hangup`, `call.mute`, `call.routes`, `call.route`, `call.recents`, `call.state`). Fixtures: `FakePhoneBook`
and the simulator's `PHONEBOOK`; goldens `phone-*` and `call-dialing` (bitmap, 26x7 and 20x5 text).

API evidence checked for this implementation:

- [AOSP API 31 InCallService: wearable companion access](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/android-31/+/refs/heads/androidx-main/android/telecom/InCallService.java): association + MANAGE_ONGOING_CALLS + BIND_INCALL_SERVICE; default-dialer role is a separate path.
- [AOSP InCallController](https://android.googlesource.com/platform/packages/services/Telecomm/+/refs/heads/main/src/com/android/server/telecom/InCallController.java): app-op permission check and non-UI service binding.
- [InCallService audio APIs](https://developer.android.com/reference/android/telecom/InCallService), [Call control APIs](https://developer.android.com/reference/android/telecom/Call), [TelecomManager fallback APIs](https://developer.android.com/reference/android/telecom/TelecomManager), and [companion association flow](https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing).
## Controller modes and background use

Android's **non-interactive screen-off state drops external controller/keyboard
keys before apps and accessibility**. The first key down wakes the screen; its
matching up is dropped too. K (keyboard), D (D-input), and S (Switch) HID modes
cannot fix this without privileged access. Bluetooth pairing, a foreground
service, battery exemption and a partial wake lock do not change input policy.
See AOSP Android 15 [PhoneWindowManager](https://github.com/aosp-mirror/platform_frameworks_base/blob/android15-release/services/core/java/com/android/server/policy/PhoneWindowManager.java)
and [AccessibilityInputFilter](https://github.com/aosp-mirror/platform_frameworks_base/blob/android15-release/services/accessibility/java/com/android/server/accessibility/AccessibilityInputFilter.java).

Choose the input source in **Companion setup**:

- **Shizuku (works with screen off)** is the recommended route for the owner's
  8BitDo Micro. A shell-uid UserService reads the controller's kernel input nodes,
  before Android can swallow the first wake key. It attempts `EVIOCGRAB` on every
  matching node. **Exclusive** means all nodes were grabbed: presses never reach
  Android, wake the display, or type into another app. **Shared: screen will still
  wake** means raw input works but at least one grab failed; the first raw press
  is still delivered and accessibility duplicates are suppressed. No root is used.
  If Shizuku is absent, stopped, unauthorized, or has no readable controller node,
  the selected Shizuku source automatically uses accessibility instead. It switches
  back when the binder/permission/reader becomes available. The notification names
  the source actually delivering input. See setup and limits below.
- **Learned keyboard** uses accessibility. Teach the buttons (**Tools → Controller → Start**)
  using a single external device. Mappings and descriptor/vendor/product identity
  are cached and refreshed when preferences change. Re-learn after upgrading from
  name-only capture, or if re-pairing changes the descriptor. Names and the old
  `captureAnyKeyboard` setting no longer grant global capture. Every key from the
  learned device is consumed, including unmapped keys and service cold-start
  keys. Unrelated keyboards pass through normally. Cold-start keys are not replayed.
- **Vendor BLE profile** owns a `connectGatt(autoConnect=true, TRANSPORT_LE)`
  connection, subscribes to the saved non-HID characteristic, and diffs reports
  into button transitions. Select it only after probing a usable mode. It has
  reconnect and release-on-disconnect handling; it never silently falls back to
  keyboard input or processes both sources. BLE availability on the Micro is
  **unknown until tested**, in each of K/D/S modes. HID service `0x1812` and
  protected report characteristics `2A4A–2A4D` are never accessed. Third-party
  apps lack the `BLUETOOTH_PRIVILEGED` permission those reports need.

### Shizuku setup (recommended for a locked, screen-off phone)

1. Install [Shizuku from Play or GitHub](https://shizuku.rikka.app/download/).
   On Android 11+, enable Developer options, USB debugging and Wireless debugging
   while connected to Wi-Fi. In Shizuku, follow the pairing instructions using
   Wireless debugging's **Pair device with pairing code**, then **Start** Shizuku.
   Pairing normally only happens once. Android 10/API 29 needs a computer and adb
   to start Shizuku; the same input reader works afterward.
2. Pair the Micro to the **phone**, initially in keyboard mode. Keep the screen
   awake while teaching the buttons (**Tools → Controller → Start**); this stores
   the Android descriptor, name, vendor/product and existing Android key map.
   Enable accessibility for fallback. Switching controller modes can change these
   identities and mappings, so re-learn after a mode change.
3. Select **Shizuku (works with screen off)**, tap **Request Shizuku permission**
   and approve RayNeo Companion, then **Start Shizuku input**. If permission was
   permanently denied, allow this app in Shizuku's authorized-apps screen. The card
   shows installed/running/permission/bound, controller name and node paths, and
   grab status. **Open Shizuku / install** opens its app or official download page.
4. Tap **Test: press a button** to pause actions and watch raw key/hat events.
   Tap again to finish. Existing Android key bindings use the AOSP `Generic.kl`
   translation. OEM-specific layouts may need re-learning while Shizuku is active.
   Unknown codes appear as **raw N** and the same Learn wizard can bind them
   directly (stored in a separate negative-key namespace in the existing JSON).
   Finish testing, lock the phone, and verify the very first press on the glasses.
5. **After every reboot, start Shizuku again**, then open the companion to restore
   foreground/microphone eligibility. The [official setup guide](https://shizuku.rikka.app/guide/setup/)
   requires restarting after reboot; this app does not promise wireless auto-start
   on Android 13+ or trusted Wi-Fi. Shizuku and the companion may need OEM battery
   exemptions. Stop input or select another source to release all grabs.

Shared mode cannot keep the screen dark: Android still sees its copy of the key.
It is a diagnostic/fallback mode, not fulfillment of the screen-off/no-wake goal.
An OEM can deny opening evdev entirely; that produces an explicit error and
accessibility fallback, with its original lost-first-press limitation. The card
reports actual reader/grab state, not merely Shizuku permission. No helper can
promise Bluetooth/kernel wakeup behavior on every phone; run the
[hardware checklist](GLASSES.md#shizuku-screen-off-input-hardware-checklist).

### Black pocket mode (keyboard mitigation)

Start **Black pocket mode** from the app, setup, or the foreground notification's
**Pocket mode** action before pocketing the phone. This opaque black activity
shows above the secure keyguard, turns the screen on, keeps it interactive,
uses minimum window brightness, and hides system bars. It does not unlock or
dismiss the keyguard. Hold **three fingers** on the screen for **2 seconds**, use a
phone volume key, or system Back to exit. A brief **Pocket mode off** toast and
three-second notification confirm exit. A one-finger palm press cannot exit.
Android emergency/system gestures and system bars are still OS-owned.

The optional **Pocket mode on locked screen wake** setting uses the **bound
accessibility service** to launch this activity at `SCREEN_ON` while a learned
controller is attached and the keyguard is locked. Android 15's
[AccessibilityServiceConnection](https://github.com/aosp-mirror/platform_frameworks_base/blob/android15-release/services/accessibility/java/com/android/server/accessibility/AccessibilityServiceConnection.java)
binds with `BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS`. The launch is made directly
from that service; it does not depend on full-screen-intent permission, which
is restricted on recent Android. The notification action is a direct activity
PendingIntent and remains the explicit fallback. A generic foreground service
cannot reliably launch pocket mode just because a controller connects or the
screen turns off on Android 10+.

`SCREEN_ON` cannot identify which device caused the wake: this option applies to
**any locked-screen wake while the learned pad is attached**. The wake press is
lost by design and is never replayed. Wait for the black activity, then press
again. Test the launch on the actual OEM/Android version; if it is blocked or
accessibility is unbound, use the notification action/manual entry. Disabling
the option stops automatic launches; it does not stop a manually opened activity.

Black OLED pixels reduce panel power, but **the phone is still screen-on**.
CPU, display electronics, non-OLED backlights, Bluetooth and rendering still use
battery. Measure drain and temperature during a real pocket session. Pocket
mode is optional; it is not a screen-off input bypass.

### Two-minute BLE probe + learn

1. Open **Companion setup → BLE controller probe + learn**. Grant Nearby devices;
   Android 10/11 needs Location permission and system Location enabled. Scan for
   15 seconds, pick the controller. Try each physical mode separately. A classic
   HID-only mode may advertise no BLE device at all.
2. The probe lists every discovered service/characteristic and subscribes to every
   non-HID Notify/Indicate characteristic with a usable CCCD, one at a time.
   The production BLE source is paused while the probe owns the connection.
   Press and release buttons; each candidate shows its latest bytes and changed
   zero-based offsets. No vendor control commands, HID reads, bond changes, or
   traffic uploads are performed.
3. Tap the characteristic whose bytes follow buttons. Release all buttons, then
   tap **All buttons released: start / restart guided learn**. If no neutral
   report exists yet, press/release once first. For each of the 16 prompts,
   press **only that button**, then fully release it. The release must return
   to the same neutral report before the next prompt.
4. The conservative learner supports fixed-length reports (1–512 bytes), bit
   fields (including active-low), and one-byte enumerated states. It checks all
   learned samples for ambiguity before saving. Multi-byte changes, counters,
   changing/variable reports and duplicate states are rejected with an explanation.
   A notifying characteristic alone does not prove button access. If no usable
   characteristic or stable complete mapping appears, **this mode cannot be
   used by the implemented vendor BLE source**. Do not infer a bypass from HID
   pairing or service discovery alone.
5. Successful learning saves an app-private JSON profile: device address,
   service/characteristic UUIDs, report length, and fields `{offset, mask, value,
   letter}`. It selects vendor BLE input. Close the probe to reconnect the source.
   Exercise chords (especially R2 + directions), reconnect, PTT, and screen-off
   operation. A full-byte enumerated state may not represent simultaneous buttons;
   verify rather than assuming. Re-learn after mode/firmware/address changes.

The optional **controller presence association** uses Android's system chooser.
Choose classic for the paired keyboard or BLE for a vendor controller. On API 31+
`startObservingDevicePresence` and `ControllerCompanionService` can bring the
foreground companion up when that controller appears; API 29/30 only associates.
This stores its controller address separately, preserves other associations,
and does not disassociate the glasses. Background-start exemptions do not grant
microphone permission/eligibility or change screen-off HID policy. Removing the
app/association, revoked Bluetooth permission, force-stop, and OEM restrictions
still need recovery on the actual phone. See Android's
[CompanionDeviceManager contract](https://developer.android.com/reference/android/companion/CompanionDeviceManager#startObservingDevicePresence(java.lang.String)).

Input cleanup runs on screen-off, device disappearance/disconnect, source switch,
accessibility interruption/unbind and service destruction. It cancels pending
hold/tap actions and uses normal PTT stop: the assistant submits its transcript; other
screens keep their normal preview/send confirmation. Non-L2 presses expire after
15 seconds with no source events/confirmation on the keyboard source. Change-only
GATT reports remain held until release/disconnect. L2 pre-arms immediately,
requires a 450 ms hold and force-stops/submits at 120 seconds if release is lost.
Dictations containing less than 200 ms of PCM are discarded. A partial wake lock covers held keys, dictation
through its terminal result, and five seconds after input. It keeps hold timers
and rendering from stalling in CPU suspend but cannot make screen-off HID events
arrive. Process death/service destruction cannot guarantee completion of a
pending network transcription; durable dictation delivery is not implemented.

Setup shows live enabled/bound accessibility, controller/source, battery exemption,
notification permission/listener access and pocket activity state. Its existing
battery button opens Android's exemption request. The foreground notification
shows actual controller and glasses connection state instead of claiming both
are active. Exemption remains a user choice and is not a guarantee against OEM
process killing.

### Shizuku implementation and limits

Only `dev.rikka.shizuku:api` and `:provider`, pinned to Maven Central's stable
13.1.5, are added. The provider is protected with
`android.permission.INTERACT_ACROSS_USERS_FULL`; permission and UserService
lifecycle follow the [official API README](https://github.com/RikkaApps/Shizuku-API).
No root, hidden Bluetooth HID profile, NDK, or native library is needed. The reader
refuses a root-started Shizuku backend: start it via adb/Wireless debugging as
uid 2000. All Shizuku calls are isolated behind `ShizukuInputFacade` for JVM tests.

Discovery first parses `/proc/bus/input/devices`. When SELinux denies it (as on
the `glasses35` Android 15 emulator), it runs the system's finite `getevent -i`
enumeration with a two-second timeout. AOSP
[getevent](https://android.googlesource.com/platform/system/core/+/android-15.0.0_r1/toolbox/getevent.c)
uses `EVIOCGNAME` and `EVIOCGID` directly, avoiding denied proc/sysfs files.
Discovery polls once a second, matches the exact learned name and nonzero
vendor/product on Bluetooth/USB buses, and opens **all** matching event nodes.
Sibling HID applications with a different name join only when their IDs and
nonempty kernel unique/physical identity match an exact-name anchor.
Android's descriptor is a framework hash, unavailable from proc/getevent; it
remains the strict identity for accessibility. Two identical pads with the same
kernel name/IDs cannot be distinguished here; connect only the learned pad.
Older mappings acquire the name when that learned Android device reconnects.

Readers use `Os.open/read/poll`, decode native-endian 16-byte (32-bit process) or
24-byte (64-bit process) records, merge node/hat edges, and send key transitions
through an oneway AIDL callback. `EV_KEY` value 2 confirms a held key without
re-triggering actions. Unknown keys are learnable; other axes are ignored.
Shared mode deduplicates accessibility against raw key/value/timestamp pairs
within 100 ms, in either arrival order. Exclusive mode ignores accessibility
delivery for the learned pad. The app acquires its partial wake lock on Binder
ingress **before posting to the main thread**; the existing held-key/dictation
renewal and idle timeout then apply. There is no permanent wake lock. Raw and
GATT sources have explicit disconnect cleanup instead of keyboard stale timers.

Only the shell UserService reflects hidden `Os.ioctlInt` (permitted there by
Shizuku). Android 10's three-argument and newer two-argument libcore forms both
pass a non-null int pointer. Linux `EVIOCGRAB` tests that argument for nonzero,
so this grabs without pointer manipulation. Closing the fd ungrabs. Failure
never disables reading. ENODEV/EOF/poll errors and SYN_DROPPED abandon held keys;
the one-second rescan reopens reconnecting nodes. Source change, stop and service
death call `releaseAll`, avoiding manufactured hold-button taps. Callback-binder
death closes fds and exits the shell process; explicit unbind invokes Shizuku's
reserved destroy transaction and exits too. A 15-second startup watchdog covers
death before the callback is attached. No controller stays grabbed by design
after the listening app dies; verify OEM behavior with the checklist.

Optional shell diagnostic after installing the debug APK (no root):

```sh
adb shell 'CLASSPATH=$(pm path io.github.rayneohud.companion | cut -d: -f2) app_process /system/bin io.lampyr.glasses.input.shizuku.EvdevShellProbe /dev/input/eventN'
```

Choose an external keyboard/controller node from `adb shell getevent -i`; the
probe attempts a grab, prints raw records for five seconds, then closes it.
Do not use a phone power/touch node. JVM tests cover both layouts, translation,
hat edges, multi-node merge, shared dedupe, fake-facade fallback/recovery, and
release-on-loss. Real Bluetooth suspend wakeup, SELinux and OEM lifecycle remain
phone tests, even when an emulator shell probe succeeds.

Implementation validation on `glasses35` (Android 15): the packaged Kotlin probe
ran through `app_process` as uid 2000, opened the keyboard event node, reported
`grab=exclusive`, and closed/exited cleanly. Proc and sysfs discovery were denied;
`getevent -i` ioctl discovery worked. Emulator-console event injection produced
no records on that node, and shell `sendevent` was denied by SELinux, so this
verified open/grab/cleanup, **not** live event decoding or physical wake behavior.

## Android independent-review behavior

Phone capture requires RECORD_AUDIO and a microphone foreground type acquired
while a phone activity is resumed. Main, setup and pocket entry retry promotion,
including cold starts. A successful type remains for the service lifetime;
the persistent notification says **Phone microphone armed for background recording**.
This reserves eligibility, rather than continuously running AudioRecord.
Failures show **Mic unavailable - open the phone app**, a red setup health row
with the permission fix, and exception logs. Bluetooth wearer PCM bypasses this
phone-mic gate for dictation, memos and live assistant. Phone-speaker/AEC fallback still
requires phone eligibility. Audio-focus denial releases acquired resources.

CDM presence allows eligible background service starts; association alone is not
a while-in-use microphone exemption. See [Android's separate exemption lists](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).
After boot/background restart, reopen the phone app to arm phone capture.
Optional ambient location retries while visible, preserves other foreground types,
and calls the existing `AmbientRuntime.refresh()` hook. The notification's Ambient
line and setup health report missing location access. Application lifecycle
callbacks also observe existing setup activities without modifying their packages.

Rejected accessibility/CDM/Telecom starts log and post **Tap to start the glasses
service**, when notifications are allowed. Audiobooks reuse an existing player;
otherwise they start from the companion context when present, subject to Android's
exemptions. Rejection reports **Open the phone app to start playback**.

Controller wake acquisitions have a 125-second timeout refreshed by activity.
The 120-second PTT cap stops/submits capture and clears its wake-hold token.
Stalled dictation is cancelled after five minutes and late results are ignored.
Source/profile changes restart input; key learning only reloads mappings.
BLE checks Nearby devices and adapter state before connecting, stops retries for
user-action failures, and backs off link failures from 5 seconds to 5 minutes.
Auto-connect disconnects retain the GATT owner and retry with `connect()`.
Bluetooth changes, user-present and visible setup allow recovery after user action.
The pocket notification action requires no keyguard authentication on API 31+.

Telecom binding starts the companion defensively; the live store reads existing
calls on startup. Basic ringing controls work before `onCallAdded`. Ringing
takeover ignores Y/B for 500 ms; globals cannot bury the card and X still hides it.
Y resumes a held call. Answered/held calls keep the display unlocked after B/Home,
restoring its prior lock at final hangup. Basic waiting controls recover on
RINGING to OFFHOOK. Simulated ringing maps to Ringing; AUDIO_PROCESSING maps to
Screening without takeover. User-present/service/activity starts refresh phone
state registration; destruction clears all call maps. Cellular interruption and
assistant focus/wake/exclusion teardown already existed and remain regression-tested.

Finding 10 required no new grip: INP-01's portrait TV-remote (`cw`) and landscape
(`land`) rotations were already implemented for either hand. No hand-dependent
mirror or upside-down grip is specified. Tests cover all four directions in both
grips. Finding 11 is confirmed by INP-12/16: L2 is a 450 ms hold outside memos;
the memo app's tap toggle remains unchanged. Held-call bitmap and both text-grid
goldens now explicitly cover the **Y resume** hint.

## Ambient capture, people and conversations

Passive listening is on by default. Hold minus on the unlocked remote, or pause/disable on the phone, to stop ambient recording and speaker matching together. Home and People retain their Off states. Explicit dictation, memos and calls are separate. Obtain consent and verify the glasses recording LED on hardware.

LifeLog retains its original 48 kHz mono Opus packets when the per-packet VAD flag is set; the continuous fallback uses stereo 48 kHz with the existing energy/zero-crossing VAD and 600 ms hangover. Both are stored as two-channel mapping-family-0 Ogg (mono packets decode as dual mono). No RayNeo cloud processing, local transcription, or unknown-voice enrolment runs. Dictation/memo/microphone-test exclusions drop whole overlapping packets.

Each conversation starts with speech and closes after ten minutes without it, on mute/disable, service stop, a capture discontinuity, or before 89,000,000 bytes. Midnight has no special meaning. The gap is configurable from 1 to 60 minutes; the old default of three becomes ten and other stored choices are retained. Files are private:

```text
files/ambient/conversations/<clientId>/audio.ogg
files/ambient/conversations/<clientId>/record.json
files/ambient/conversations/backoff
files/people/voices-heard.json
files/speaker-model/wespeaker.onnx
```

The durable record contains the upload manifest and queue progress. Segment offsets and speech duration count original Opus samples at 48 kHz, including 2.5 ms frames. Runs separated by less than 300 ms merge; silence is never padded into the file. Each anchor has its real UTC time; the manifest also has the device's IANA timezone. Audio sync precedes atomic JSON replacement about once a second. Restart validates the committed prefix, truncates only uncommitted tail bytes and seals the conversation. Missing, short or inconsistent audio stays intact with a failed state for manual recovery. Ingress remains bounded to two seconds. Storage failures retain the existing bounded recovery backoff.

Under 30 seconds of speech is marked skipped. Other closed conversations enter the durable queue: POST manifest, persist server ID, PUT unchanged Ogg, persist receipt, then poll state. Interrupted requests can repeat safely using stable clientId and unchanged audio bytes. HTTP 400/413/404 becomes failed, 422 skipped, 429 persists a global pause of at least 30 minutes, and audio 409 counts as already accepted. Transient failures retry from five seconds up to six hours. Audio upload defaults to any network; the phone can require Wi-Fi. The existing foreground service owns queue processing; no WorkManager dependency was added. Reboot resumes when Android permits the existing service to restart (enable Start on boot). Uploaded/transcribed and skipped conversation folders, including location manifests and local receipts, follow retention (default 30 days); files not yet successfully uploaded are preserved, including rejected uploads. Server processing failures after a successful audio upload still expire. Removed records cannot re-enter the queue, and late HTTP replies cannot recreate them. Legacy daily files are left untouched and are not automatically migrated or uploaded.

Location uses the existing optional permission/foreground gate: one bounded request at start, then every five minutes. A fix under two minutes old with accuracy at most 50 m can be reused. No continuous GPS, step polling, end fix or geocoder runs. Missing access produces no manifest fixes.

The phone ambient screen lists time, speech duration and recording/waiting/uploading/uploaded/transcribed/skipped/failed states, with a reason on failure. Transcribed means the gateway reports `timed`. It also shows model download state, last voice sync, muted profiles with unmute, capture gap, score threshold and separate Wi-Fi switches for model and uploads. Daily CSV and export/share controls were removed. Conversation M4A export was removed with that workflow; memo WAV storage and its existing transcription flow remain. Memos now upload by themselves (`data/MemoUploads.kt`): each memo of 1 s or more is POSTed to `/ambient/conversations` with `kind:"memo"`, its audio PUT as Ogg/Opus (platform encoder; WAV fallback), and followed until the gateway reports `timed`; the gateway transcribes it and delivers it to its configured targets, and the memo row then reads "sent to <target name>" (`Memo.delivered`, `deliveredTo`, `deliveredAt`, `deliveryError`). Retries run every minute from the data store whether or not the screen is open; memos already on the phone (and WAV files missing from the list) are backfilled. LifeLog audio arrives in ~20 s bursts: the capture source stamps it with a stream clock, the ingress queue holds 60 s and the recognition queue 1024 packets, and lost audio or a gap adds a time anchor instead of ending the conversation. The speaker model downloads by itself (Wi-Fi only by default) and People says why it is empty while it is missing. A one-time migration re-stitches the short "skipped" fragments left by the old capture into conversations of 30 s or more where the audio is still on the phone.

Speaker recognition uses `onnxruntime-android:1.32.0`, the only new runtime dependency. The JVM ONNX package is test-runtime-only. The model is downloaded on demand from the public WeSpeaker Hugging Face repository, with partial-download resume, bounded retry and SHA-256 verification:

```text
7bb2f06e9df17cdf1ef14ee8a15ab08ed28e8d0ef5054ee135741560df2ec068
```

No model is committed. Until it is ready, recognition remains off. On voiced audio, a dedicated worker takes 1.5 to 6 seconds about every three seconds; busy ticks are dropped. Stereo downmix and windowed-sinc anti-alias resampling produce 16 kHz mono. The Kotlin frontend matches the WeSpeaker recipe: int16 units, 80 Kaldi mel bins, 25 ms Hamming frames, 10 ms shift, no dither, DC removal, 0.97 pre-emphasis, 512-point FFT, mel 20 Hz to Nyquist, log floor and per-utterance mean subtraction. Output is an L2-normalised 256-vector. ONNX telemetry is disabled.

Profiles sync at service attachment, every 30 minutes, and after mute changes, with ETag/304 and private offline persistence. Decisions use the maximum profile score per person, threshold 0.55 (setting 0.30 to 0.90), margin 0.08 against the second person, and two consecutive wins. Owner and muted profiles still compete, suppressing mistaken other-person matches, but never produce a name or heard event. Unlinked profiles use their people-source name without notes. Unknown embeddings and PCM windows are never persisted.

The heard log publishes People immediately and merges with cached server recents. Events are limited to one per person per minute, optionally with a fix under ten minutes old. An outbox sends at most 200 per batch. Invalid/future/nonfinite events are excluded; HTTP 400 drops that batch with a content-free diagnostic. Cards use the server's cached glasses blurb; X opens count/don't-count actions with confirmation. Mute changes are durable locally before HTTP and retry offline. A server 404 removes an invalid pending mute. Both 26x7 and 20x5 text grids retain paged notes and the existing greyscale tiers.

## Speaker and conversation validation

Default JVM tests use synthetic audio and fake gateways; no model, personal enrolments or service credentials are required. They cover the Kaldi fixture, decision policy, resampling, sync/ETag/persistence, heard batches, mute, conversation boundaries/checkpoint recovery, exact manifests, upload stages/backoff/network policy and People screens. The existing Ogg/container, VAD, exclusions and source tests remain; tests for removed daily export/CSV/movement polling were replaced.

Reference generation uses a temporary Python 3.12 venv outside the repository with `kaldi-native-fbank==1.23.1`, `numpy==2.5.5`, `onnxruntime==1.32.0`:

```powershell
& <venv>/Scripts/python.exe android/tools/speaker_reference.py --model <model.onnx>
$env:SPEAKER_MODEL_PATH = '<model.onnx>'
$env:SPEAKER_REFERENCE_PYTHON = '<venv>/Scripts/python.exe'
$env:JAVA_HOME = '<jdk-17>'
$env:ANDROID_HOME = '<android-sdk>'
./android/gradlew.bat -p android testDebugUnitTest assembleDebug
```

Measured on the checked-in synthetic fixture: fbank maximum absolute error `0.00023794174` (limit `0.001`); embedding cosine `0.9999999999831494` (minimum `0.999`). Embedding parity is opt-in via `SPEAKER_MODEL_PATH`; it runs the reference Python frontend and model in a temporary output directory before comparing Kotlin. `SPEAKER_REFERENCE_PYTHON` selects the temporary venv (otherwise `py -3.12`). The normal run skips it. `AMBIENT_FFMPEG_TEST=1` separately enables independent FFmpeg validation of original packets, chained streams and decoded sample counts.

Only the changed People card raster and two People text goldens were updated. Text fixtures regenerate with `UPDATE_TEXT_GOLDENS=1`; retain only intended fixture diffs. The person-card raster adds X actions in the existing footer: run `RenderingGoldenTest.screensMatchApprovedSimulator`, inspect `app/build/outputs/golden/people-card.png`, copy that reviewed native frame to `src/test/resources/golden/people-card.png`, and threshold green >0 to update its `golden-1bit` geometry. The simulator stays unchanged; other raster references retain the simulator capture procedure above.

Hardware checks still required: LifeLog/continuous VAD and channel layout; model download/resume on Wi-Fi and cellular transitions; recogniser speed, thermal/battery impact and real-room accuracy; no events/audio capture after remote mute or explicit mic exclusions; Opus playback/convergence at stream boundaries; exact transcript timing after real gateway upload; 30-second/gap/size boundaries; offline/reboot recovery and OEM foreground restrictions; location permission/revocation and five-minute cadence; count/unmute controls and note paging on both text grids and raster. The debug APK stays in its build output and must not be copied or shared.

The phone mirror uses the native 540:220 aspect ratio. `render/DisplaySize` owns W/H, pixel count (118,800), and packed size (14,850 bytes). Reader capacity is 43×8 full width, 33×8 beside the rail, or 31×9 beside assistant; stock remains 26×7 with a 26×5 reader body.

### Restrained brightness hierarchy

Tune `render/Tone.kt` and the matching three simulator tokens only: primary 15,
secondary 10, tertiary 6. Keep at least four codes between tiers and none below 5.
Primary carries focus, body text, values, actionable labels, icons, portraits,
filled bars and active dots. Secondary carries supporting subtitles/tags/ages,
rail/header context, page counters and section captions. Tertiary carries existing
rules, dotted tracks, bar outlines/ticks, inactive dots, unchecked outlines,
non-focused reactions and unavailable rows. Selection remains full-brightness
inversion with black text. Hint words are secondary with primary key glyphs;
complete call controls and approval content remain primary. No geometry, font,
icon or position changes, output antialiasing, gradients, dithering or shadows.

The Canvas work surface keeps coverage and tone in separate colour channels;
the final pass thresholds the original green coverage and resolves the tone to
one of the three flat codes. The private work-surface colours are never sent or
mirrored. This preserves thin strokes independently of their brightness.

Visual QA covered all four native-pixel contact sheets: selection dominance,
unselected subtitles, rules/tracks, page dots, reaction focus, unchanged assistant
portraits, call names and controls, approval commands/choices, health values and
filled bars. Actual lens brightness separation remains unmeasured.

## Hold-to-talk sessions and the recording mark

Hold-to-talk capture is one session owned by `InputEngine`, not by a screen. The
release of the L press that started it ends it on any screen (with a popup, after a
lock, mid-navigation); a second L press while L still looks held is treated as the
lost release and stops it. Closing the dictation screen (B, hold B to Home, a cleared
stack) cancels its live capture or pending transcription; lock and an assistant call cancel a
live capture wherever its screen is (an empty listening screen is removed, earlier
text stays as a preview). When the microphone closes the screen switches to
"Transcribing" at once, so no timer runs without a live mic. Covered by
`DictationCaptureTest`, including the capture exclusion that pauses LifeLog.

`Ui.recordingBadge` draws a blinking dot, `REC` and the elapsed time over the clock on
every screen while a voice memo records (text grids: `*REC 0:42` on row 0), and `MIC`
while a dictation captures away from its own screen. Stopping a memo flashes
`Memo saved 0:42`. `power.memoKeepAwake` (Display & sleep > Memo keeps lit, default
off) holds the lens lit for the whole memo; when off, the mark shows whenever the lens
is lit. Golden: `memo-recording-hud` (simulator `recMark`), text fixtures
`memo-recording-hud.txt` and `memo-recording-hud-20x5.txt`.
