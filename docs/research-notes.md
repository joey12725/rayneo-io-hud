# Research notes

What we learned on 2026-11-11 and 2026-11-12 while planning the RayNeo iO software. These are findings and reasoning, not requirements; the requirements are in [`requirements.md`](requirements.md). Each finding is marked:

- **Verified**: from an official page, a project's own README/code, or our own source reading.
- **Reported**: from one third party (a review, one developer's testing) and not confirmed by us.
- **Inferred**: our own reasoning; treat as a hypothesis.

The glasses had not arrived when these notes were written, so nothing here had been tested on a real unit at the time.

---

## 1. RayNeo iO hardware

| Fact | Status | Source |
|---|---|---|
| Launched Sept 4, 2026; about $449–479 (standard case), $499–529 with charging case | Verified | RayNeo store listing; Digital Trends; Dymesty review |
| Monochrome green microLED + diffractive waveguide, 23.5° FOV, about 1,200–1,300 nits, 97% transparency | Verified | RayNeo spec via Skarred Ghost review; Digital Trends |
| 4-mic array + bone-conduction sensor | Verified | RayNeo spec; Wellbots listing; Hearing Review |
| No speaker (one outlet claimed bone-conduction audio output; RayNeo's own pages say no speaker) | Verified (no speaker) | RayNeo "microphone array explained" page; Engadget; The Decoder |
| No camera; hardwired LED lights when mic features are active | Verified | RayNeo; Gizmodo; Android Central |
| Controls: Smart Crown (rotate + click), head gestures (nod confirm, shake dismiss, head-up wakes dashboard), voice | Verified | RayNeo iO support page |
| 240 mAh, about 48 h typical, 18 h continuous recording, 6.2 h continuous translation | Verified | RayNeo launch materials |
| Bluetooth 5.4, Apple MFi, Android and iOS supported | Verified | RayNeo |
| Setup requires the RayNeo app **and a RayNeo account** | Verified | iDevice review; RayNeo support |
| Android needs a "background activity" permission for a stable connection | Verified | RayNeo support |
| Built-in features: dashboard widgets, live captions/translation (Timekettle BabelOS), voice memo, LifeLog, teleprompter, AI assistant (RayNeo AI + Gemini Flash Lite; $9.99/mo for other models), focus mode, to-dos | Verified | RayNeo pages and reviews |
| **LifeLog does not store original audio** (text records only) | Verified | RayNeo support page quote |
| Reviews flag weak third-party app support compared with Even G2 | Reported | Geeky Gadgets |

**Microphones correction:** the original transcript said the iO has no microphones. It has five audio sensors, and community testing exposes them as two usable channels (see 3.1).

## 2. Firmware and platform

- **Chip/OS correction:** NuttX on a Bestechnic embedded platform, not Android.
  The emulator says BES1700; Turbo and Glassly say BES2800. Physical marking,
  model identity and core count remain unverified; do not present either label
  as established hardware fact. See [firmware-decision.md](firmware-decision.md).
- **No official iO SDK or app platform** at launch (VR.org: "No SDK, no app platform"). RayNeo's own open-source article tells people who want to modify firmware to pick other hardware. RayNeo's Android/Unity ARDK covers the X-series only.
- **Firmware downloads correction:** iO updates arrive through the RayNeo app;
  Turbo now archives stock iO OTAs for 1.0.4.12, 1.0.5.5 and 1.0.6.11 with
  hashes/provenance. Unpacked newer images were read locally. They are OTA
  payloads, not a full flash backup or a proven recovery method.
- **Three different companies publish RayNeo-branded iOS apps** (RayNeo AI = iO companion; RayNeo XR; RayNeo AR). RayNeo AI 1.0.6 added downloadable offline translation/transcription models and a phone-mic option.

### 2.1 Options considered

| Option | Verdict | Why |
|---|---|---|
| Stock firmware + official app | Not enough | No assistant, Beeper actions, T3 or raw audio |
| **Stock firmware + our own Android app** (OpenRayneo approach) | **Chosen** | Works today, no flashing risk, keeps the brain on the phone where all the integrations live |
| Root the phone | Not needed | Notification listener, accessibility key capture and media sessions cover v1. Root only helps for hooking the official app, pulling its files, or stopping battery kills |
| Custom firmware | Conditional, after stock baseline | Prefer exact-version Turbo TVP2; LifeLog acceptance and installer availability decide. Glassly/Spare have different limitations; see the decision document |

## 3. Community projects

### 3.1 OpenRayneo (github.com/Tobi1chi/OpenRayneo), Apache 2.0

- Direct connection, no phone relay or modified official app: **BLE authentication, then RFCOMM channel 26**; writes segmented to the negotiated RFCOMM MTU. *Verified (README).*
- Drives the glasses' **existing** surfaces: notification, teleprompter, caption, prompt, to-do; also home and city weather updates. Does **not** draw arbitrary graphics, mirror the screen or do custom layouts. *Verified.*
- Display: a **26 × 7 grid of full-width characters**, refreshed 5–10 times a second in everyday use; pushed a 120-line, 16,319-byte teleprompter script; plays local video as character animation (greyscale, block, half-block modes). *Verified.*
- Audio: Opus over the link. v0.2.0 exposes **two mic channels** as separate macOS inputs: **channel 1 = wearer (bone conduction), channel 2 = forward/other speakers** (the owner's own experiment). *Reported.*
- **LifeLog API**: `/v1/lifelog/observe, /switch, /record, /stop` plus a status endpoint returning wake/exit events and **audio/VPU/VAD counters**. Device tests: LifeLog wakes on wearer and external speech; quiet and head-up trials produced no wake. **Cached-audio retrieval unverified or unimplemented.** *Verified (PR #1).*
- Continuous stereo WAV recording; local ASR via Apple's recogniser on the Mac. *Verified.*
- v1.0 adds an **Android companion app** (follows Gaode navigation notifications; connects to a mobile model or Codex). An English Android fork exists (ruchirlives/OpenRayneo). *Verified.*
- Tested on **one pair**, macOS 15.6.1, firmware version not recorded. *Verified.*

### 3.2 Spare io (owenc1122/spare-io, spare.cool)

Replacement launcher/system; open repo is UI/Workshop SDK only (PolyForm
Noncommercial), without runtime/link/updater. The earlier iPhone-only/unshipped
description is stale: [Spare Nova's page](https://spare.cool/nova/) checked Nov 12
advertises Android and iPhone, firmware 2.1.20 stable / 2.1.21 preview. The supplied
2.1.39 firmware number is not supported by that page. No verified stock rollback
or independently auditable LifeLog arbitration was found. Workshop app rollback
is distinct from firmware rollback; images are ≤128×128, even width.

### 3.3 rayneo-io-on-mac emulator (owenc1122/rayneo-io-on-mac), MIT

- Renode board model of the BES1700 plus a Swift viewer; "bring your own firmware" (none distributed).
- MIXED (Oct 2026) reported the core was private. The project page now says the repo contains the board model, C# peripheral models, viewer and boot scripts (that is, the core is public).
- Status per project page: boots stock firmware into NuttX, mounts storage, **shows the boot logo; does not reach the home screen yet**; next milestone is the Bluetooth subsystem.
- Voice wake runs through the **"stock BTH VAD"**. This is our evidence that speech detection is on the glasses.
- **Not usable for app development** (no Bluetooth or phone link yet). Useful later with a firmware image to look for an image/bitmap command safely.

### 3.4 Turbo IO (Turbo1123/Turbo-IO)

Chinese unofficial SDK and version-pinned AP patches. Its TVP2 native 540×220
raster release has an Oct 23 single-user acceptance report; 1.0.6.11 VIDEO-R1
has weaker hardware evidence. TLC1 and later `ce_extension_idle` copies omit the
LifeLog-enabled rejection for selected extensions without replacing VAD. This
makes it the conditional custom-raster preference, not a blanket safety claim.
Original interoperability code can use wire facts without incorporating its
PolyForm code or GPL AirPlay receiver. See [decision/evidence](firmware-decision.md).

## 4. Display, graphics and bandwidth

- **540×220 is now the native simulator and Android UI master**, 1-bit green.
  Firmware evidence remains 540×280 4-bit green per eye, 540×220 normal UI,
  and a 544×220 RGB565 aligned LVGL buffer. Supported raster output is 1:1;
  FIT/STRETCH remain available in the fitter for other dimensions. Character
  grid counts alone cannot establish pixel resolution or exact font advance.
- **Stock arbitrary bitmaps:** no general command found, high (~90%) confidence.
  Stock **does** select notification app/category icons, QWeather-style weather
  icons, todo/suggestion cards, and has A2UI structured components. The phone
  widget-install path is reported disabled from 1.0.5.5. [Protocol corrections](rayneo-protocol.md#5-bitmaps-images-and-icons).
- **Bandwidth math for images:** a 1-bit 200 × 200 frame is 40,000 bits ≈ 5 KB; two frames ≈ 10 KB. Icon art compresses to about 1–3 KB. Flipping 2–4 times a second ≈ 10–20 KB/s, well within what OpenRayneo already pushes. Image slots (upload once, flip by ID) would be cheaper on battery than streaming. *Inferred.*
- **One asset bank, multiple renderers:** native grey fitting plus experimental
  half/quadrant conversion now exist. Quadrants would give 52×14 samples;
  availability/spacing remain unknown. Braille was reported unusable upstream
  and is retained only as a hardware probe, not a supported image fallback.
- Avatar frames are 192 × 288 cells at 1 bit (6,912 bytes per frame when packed); see `docs/avatar-packs.md` for the bring-your-own pack format.

## 5. Controller (8BitDo Micro)

- 16 buttons; modes: Switch, D-input (Android), **Keyboard** (Android, iOS, macOS, Windows). Mapping in 8BitDo Ultimate Software (mobile) in keyboard mode only. *Verified.*
- A reviewer notes the mapping software doesn't expose every key (for example arrow keys missing from key combos at the time). *Reported.*
- Ergonomics decided in review: **portrait, D-pad up**. Y (top face button) becomes select. L2 is physically larger than L, so it is the mic button. The four centre buttons (−, +, ★, ♥) are least likely to be bumped, and − is the easiest to find, so it is the lock button.

## 6. Third-party integrations

### Beeper
- **Android content provider** `content://com.beeper.api/` with READ/SEND permissions: list chats (filters like `isUnread`, `protocol`, `isArchived`), list and search messages, send a message (`roomId`, `text`), contacts. *Verified (docs).*
- **Beeper Desktop API** on `localhost:23373`: list messages (with reactions), send (with `replyToMessageID`), **add/remove reactions** (`reactionKey`). *Verified (docs).* Reactions on the Android provider were not confirmed.

### T3 Code (read from source, pingdotgg/t3code, 2026-11-11)
- Sidebar thread statuses (`resolveSidebarThreadStatus`): **approval, input, request, preparing, working, waiting, failed, limited, ready**. Row labels: Setting up / Goal / Working, Waiting, Approval, Input, Request, Limited, Failed, Woke, **Done** (= ready with an unseen completion).
- Sidebar sections: pinned, active, working, snoozed, settled. "Working" threads are deliberately lower priority (greyed). Messaging a settled thread un-settles it. Settled are sorted by settle time. Auto-settle after inactivity (default 3 days), merged/closed PRs settle. Snooze options: 1 hour, tomorrow 9:00, next Monday 9:00.
- Usage limits: per provider (Codex, Claude Code, Grok Build, Cursor, OpenCode, Antigravity, Pi, Muse Code). Each window has `kind` (session/weekly/monthly/other), `label`, `usedPercent`, `resetsAt`, `windowDurationMins`. Pace: ahead / on / under. Window labels seen in source: "Session", "Weekly", "Weekly · Fable", "Monthly", "Gemini · 5-hour", "Claude & GPT (shared) · 5-hour/Weekly". **Banked reset credits** with options such as "Weekly reset", "Fable reset", "5-hour reset", "Full reset".
- Answered questions show in the thread timeline (open PR #4506).

### YouTube
- **Watch Later and History cannot be read through any official API** (checked 2026-11-12). The YouTube Data API revision history records that requests for the `WL` and `HL` playlists "will return an empty list after September 12, 2016" and that both were "fully deprecated" on 2020-09-09; nothing newer reverses it ([revision history](https://developers.google.com/youtube/v3/revision_history)). Google's Data Portability API has no watch-later group, exports only CSV archives at most once a day, needs re-consent every 180 days, and its history export is limited to EEA/UK/Swiss accounts ([schema](https://developers.google.com/data-portability/schema-reference/youtube), [time-based access](https://developers.google.com/data-portability/user-guide/time-based)). Composio's YouTube toolkit wraps the same Data API.
- The only working route is unofficial: `yt-dlp` with exported login cookies (`:ythistory`, `playlist?list=WL`). It needs a manual cookie export every few weeks to months, and yt-dlp's own wiki warns the account can be banned "temporarily or permanently" ([Extractors wiki](https://github.com/yt-dlp/yt-dlp/wiki/Extractors)). TV-client OAuth was removed from yt-dlp in December 2024.
- **Decision (2026-11-12):** the YouTube app is a single page showing one curated list (the gateway's `youtube` settings; default name "Picks"), with no playlist chooser. A zero-risk alternative if a queue is wanted later: save videos to an ordinary private playlist instead of Watch Later; the official API reads those.

### Spotify
- Spotify Web API supports listing devices and transferring playback (Spotify Connect). Requirement: start playlists on the currently active device. *From prior knowledge; verify scopes.*

### Audiobookshelf
- REST API for libraries, progress and playback sessions; playback control on the phone through Android media sessions. *From prior knowledge; verify.*

## 7. Audio capture findings

- Speech detection is **on the glasses** (emulator: stock BTH VAD; OpenRayneo: LifeLog wake on speech, none in silence). Using it saves battery versus streaming all day.
- Our app owning the connection means RayNeo's LifeLog pipeline never sees the audio.
- Opus frames are fixed-length (often 20 ms), so file offsets can be computed exactly from frame counts.
- The bone-conduction channel is the cleanest signal for "the wearer is speaking", useful both for dictation quality and for tagging the wearer's own speech in ambient audio.

## 8. Design decisions log (how the design evolved)

| Round | Decision | Reason |
|---|---|---|
| 1 | Stock firmware + Android companion | See 2.1 |
| 2 | Emulator not a dev platform; build our own simulator | Emulator didn't reach home screen / had no BT |
| 3 | Native 540 × 220 1-bit rendering in the simulator | v1 text-grid sim showed text far sharper than reality |
| 3 | Home as glance screen, separate Apps grid with icons | Old home was a text list; "hideous" |
| 3 | Inverted full-width focus bar | Small dot cursor hard to see |
| 3 | Portrait grip with D-pad up; select on the face button nearest the D-pad | One-handed TV-remote use; A was too far |
| 3 | Monochrome emoji, 😜 as the wink | Colour emoji won't exist on the display |
| 3 | Links summarised; reels special-cased | Full URLs waste the screen; reels are common |
| 3 | No interrupting notifications | Interruptions break the low-attention goal |
| 3 | T3 uses real T3 vocabulary; Done above Working; settled = 25 recent and openable | Matches the tool users already know; working threads are low priority |
| 3 | Health split from Status | Different purpose |
| 4 | Y select, B back, X per-app action, L2 mic, L quiet wait, − lock | Consistency and accidental-press safety |
| 4 | Voice call moved into the assistant's menu; Morning report its own app | Voice call too rare for a global button; report was hidden |
| 4 | Assistant removed from Home | Home should be pure glance info |
| 4 | Full-width T3 thread screens + history mode | Threads are text-heavy; need to scroll back |
| 4 | People quick view on ♥ (◀▶ people, ▲▼ pages) | Fast, discreet lookup mid-conversation |
| 4 | Spotify plays on the current device; output in X menu | Avoid a device prompt every time |
| 4 | Notifications: Y opens + dismisses, X dismisses, hold + clears; classifier importance × decay | Single dismissal was missing; clear-all was too easy to hit |
| 5 | Transcribing… / Enhancing… phases; queue/steer on working T3 threads | Clearer state; matches agent workflow |
| 5 | Assistant replies after leaving the assistant screen go to notifications | Don't lose answers |
| 5 | No timestamps on messages | Context makes them unnecessary |

## 9. Limits of this research

- Nothing has been tested on real hardware; protocol details come from one person's pair.
- The workspace could not reach YouTube, so the video background in the simulator is user-loaded.
- Fonts other than the repo's 5 × 7 were not visually verified in our headless browser (Google Fonts were blocked there).

## Sources

- RayNeo iO support: https://www.rayneo.com/en-ca/pages/support-rayneo-io-ai-glasses
- RayNeo, mic array explained: https://www.rayneo.com/blogs/news/rayneo-io-microphone-array-explained
- RayNeo, AI on the glasses: https://www.rayneo.com/blogs/news/smart-glasses-ai-on-the-glasses
- RayNeo, open-source smart glasses: https://www.rayneo.com/blogs/news/open-source-smart-glasses
- RayNeo Air PC firmware guide: https://www.rayneo.com/pages/air-series-glasses-firmware-update-guide-using-a-windows-pc
- Skarred Ghost review: https://skarredghost.com/2026/09/10/rayneo-io-smart-glasses-review/
- Dymesty review: https://dymesty.com/blogs/articles/rayneo-io-review
- iDevice review: https://idevice.com/smart-glasses/rayneo-io/roadmap/reviews
- VR.org launch analysis: https://vr.org/articles/rayneo-io-gt-max-two-devices-september-4-2026
- OpenRayneo: https://github.com/Tobi1chi/OpenRayneo · PR #1: https://github.com/Tobi1chi/OpenRayneo/pull/1
- MIXED on OpenRayneo: https://mixed-news.com/en/openrayneo-0-2-0-rayneo-io-two-microphones-mac-core-audio-inputs/
- MIXED on Spare io: https://mixed-news.com/en/spare-io-rayneo-io-glasses-firmware-update-over-bluetooth/
- MIXED on the emulator: https://mixed-news.com/en/rayneo-io-on-mac-emulator-stock-firmware-open-shell-closed-core/
- Emulator project page: https://spare.cool/en/io/mac · repo: https://github.com/owenc1122/rayneo-io-on-mac
- MIXED on RayNeo AI 1.0.6: https://mixed-news.com/en/rayneo-ai-1-0-6-offline-translation-models-phone-mic/
- Turbo IO: https://github.com/Turbo1123/Turbo-IO
- 8BitDo Micro: https://8bitdo.com/micro
- Beeper Android content providers: https://developers.beeper.com/android/content-providers/api-reference
- Beeper Desktop API reactions: https://developers.beeper.com/desktop-api-reference/resources/chats/subresources/messages/subresources/reactions/methods/add
- T3 Code: https://github.com/pingdotgg/t3code (files read: `apps/web/src/components/Sidebar.logic.ts`, `sidebar/SidebarThreadRow.tsx`, `usage/UsageLimits.tsx`, `usage/usageProviders.ts`, `packages/contracts/src/providerUsageLimits.ts`)
- T3 Code Sidebar V2 summary: https://openclawdatabase.com/news/videos/2026-07-27-t3-code-sidebar-v2/
