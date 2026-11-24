# RayNeo iO software system: requirements

Status: v1 product specification. It describes what RayNeo iO HUD does and why, screen by screen. Requirement IDs are referenced from code comments and tests, so they stay stable. Dates in parentheses mark when a requirement was added or changed.

Companion documents:

- [`research-notes.md`](research-notes.md): what we learned while researching the hardware, firmware, community projects and third-party APIs. It explains *why* several requirements look the way they do.
- [`../simulator/`](../simulator/): the interactive HTML simulator. It is the visual and interaction reference for every screen described here. When this document and the simulator disagree, raise it; neither is automatically right, but the simulator reflects the latest design review.

## Conventions

- **MUST** = required for the feature to count as done. **SHOULD** = expected unless there's a good reason. **MAY** = optional or later.
- Requirement IDs are `AREA-NN` (for example `BEEP-04`). Keep IDs stable when editing; append new ones rather than renumbering.
- "The assistant" is the user's personal agent, a [Hermes](https://github.com/NousResearch/hermes-agent) agent reached through the gateway. Its display name is configuration (gateway `assistant.name`, default `Hermes`); the glasses show that name everywhere. Code and wire IDs still call the assistant app `joy`.
- "The owner" or "the user" is the person wearing the glasses (gateway `owner.name` and `owner.aliases`).
- "The classifier" is the gateway's notification classifier (`POST /glasses/v1/notify/route`); its persona name is configuration (gateway `assistant.workerName`, default `Triage`).
- "The phone app" means the Android companion app we are building, not RayNeo's official app.
- Button names refer to the labels printed on the 8BitDo Micro (Y, B, X, A, L, L2, R, R2, −, +, ★, ♥). See section 5 for where they sit in the chosen grip.

---

## 1. Goal and principles

**Goal.** Keep roughly 80–90% of what the user does on their phone available through the glasses, for roughly 10–15% of the attention. This is an aspiration, not a measured target.

Principles:

- **P-1 Glance, decide, continue.** Show compact information that can be read at a glance. Avoid pulling the wearer into long phone-like sessions.
- **P-2 Useful notifications, rare interruptions.** Notifications are routed (rules first, then the classifier) to suppress, the list the user opens on purpose (stream), or a short pop-up (popup) for the few things that need the user now (NOT-01).
- **P-3 One entry point per job.** The assistant for personal and general tasks, Beeper for messaging, T3 Code for coding agents.
- **P-4 Long text always paginates.** Anywhere long text can appear, it pages; it is never silently cut off.
- **P-5 Personality without waste.** Animated icons and the assistant's avatar give the UI character, but text-heavy screens get maximum reading room.
- **P-6 Accidental input is a real risk.** The controller lives in a pocket. Global buttons must be few, predictable and lockable.
- **P-7 Configurable, not generic.** Names, hosts, policies and integrations are configuration; the core (T3 Code, Beeper, Hermes) is opinionated and first-class. Prefer simple, modular pieces that are easy to extend over abstraction for its own sake.

---

## 2. Hardware and platform baseline

### 2.1 Glasses (RayNeo iO)

| Item | Requirement or assumption |
|---|---|
| HW-01 Firmware | MUST retain the **stock RayNeo text path**. Optional native raster requires an explicitly selected, version-matched custom build and the firmware-decision.md go/no-go checks; the app MUST NOT flash or auto-update firmware. |
| HW-02 Root | Bestechnic/NuttX embedded firmware; BES1700 vs BES2800 naming remains unresolved. The phone MUST NOT require root for any v1 feature. |
| HW-03 Display | Monochrome green waveguide, 23.5° FOV. Use the native **540×220 4-bit green UI master**. Static evidence: 540×280 4-bit panel buffers, 540×220 UI, aligned 544×220 RGB565 source. Send matching targets 1:1; fit only other sizes. Verify visible geometry and cell advances in week one. See firmware-decision.md. |
| HW-04 Microphones | The glasses have a 4-mic array plus a bone-conduction sensor. Community testing exposes two channels: channel 1 = wearer (bone conduction), channel 2 = forward/room. Designs MUST use the glasses' microphones (not the phone's) for dictation. |
| HW-05 Audio output | No speakers. All audio (media, voice calls) goes to headphones paired with the phone. |
| HW-06 Built-in input | Smart Crown (rotate + click) and head gestures (nod, shake, head-up). Treated as secondary inputs; see 5.8. |
| HW-07 Connection | Bluetooth to the phone. The phone app talks to the glasses directly over the protocol documented by OpenRayneo (BLE authentication, then RFCOMM channel 26). |
| HW-08 Firmware updates | Official updates arrive through the RayNeo app. Automatic updates SHOULD be turned off once a working firmware version is known, because an update could break the reverse-engineered link. |

### 2.2 Controller (8BitDo Micro)

- **CTL-01** MUST be used in **keyboard mode**, paired to the **phone** (not the glasses).
- **CTL-02** All 16 buttons are mapped to keys in 8BitDo Ultimate Software; the phone app interprets the keys. One 8BitDo profile MUST work for every grip; the app (not the profile) remaps D-pad directions for orientation.
- **CTL-03** Key events MUST be captured globally (Android accessibility service with key-event filtering), so the controller works while the phone app is in the background.

### 2.3 Phone

- **PH-01** Android only. The phone app is the "brain": it holds all state, talks to every service, renders every screen and pushes the result to the glasses.
- **PH-02** No root. System-wide notification access uses Android's notification listener permission; media control uses Android media sessions; controller input uses an accessibility service.
- **PH-03** The app MUST survive Android battery optimisation (foreground service plus the background-activity exemption RayNeo's own app also asks for).

---

## 3. Architecture

- **ARC-01 Stock firmware + own companion app.** Start from OpenRayneo's Android code (Apache 2.0) for the Bluetooth link. RayNeo's official app is kept installed only for setup and firmware, and does not run alongside ours if the two fight over the link (to be verified).
- **ARC-01a Reconnect never gives up (2026-11-17).** After the protocol's three fast attempts (5/10/15 s) the link keeps retrying in the background forever, backoff 20/30/45 s then capped at 60 s. Bonded glasses are connected directly (no scan; passive autoConnect in the background), since a sleeping or already-linked unit may not advertise. The selected glasses' Bluetooth link coming up or pairing completing, Bluetooth switching on, phone screen on/unlock, a controller key and Connect each trigger an immediate attempt. Only auth refusal, account binding, lost pairing and a missing Bluetooth permission are terminal ("… Press Connect to retry"); background retries never open Android's pairing dialog. The phone shows "Searching" with the next try and last error while retrying, and warns (Home "This phone", Glasses setup) when RayNeo's official app can use Bluetooth. Every attempt is traced with its reason (`Link` category).
- **ARC-02 Transport abstraction.** All glasses I/O goes through one interface (`GlassesTransport`) with at least two implementations: the real Bluetooth link and a simulator. Everything above the transport MUST be testable without hardware.
- **ARC-03 Screens are data.** Each screen is built as structured content (list rows, cards, meters, icons, avatar state, paged text) and then rendered. This lets one screen render at several fidelity levels (ARC-04).
- **ARC-04 Rendering levels.** The renderer MUST support degrading gracefully:
  1. *Text grid*: the stock text surfaces (notification, teleprompter, caption, prompt, todo), roughly 26 × 7 full-width characters.
  2. *Character graphics*: block/half-block/Braille characters pushed as frames at 5–10 fps to draw bars, icons and a coarse avatar.
  3. *Firmware components*: built-in icons, progress bars and cards, if they can be driven (Turbo IO claims this; unverified).
  4. *Own bitmaps*: arbitrary 1-bit images, only if the stock firmware exposes an image command (unknown; must be investigated).
  The simulator renders at level 4 to define the target design. The shipped app uses the highest level the hardware supports, and each screen MUST still work at the lowest one.
- **ARC-05 Assistant integration.** The glasses are a Hermes client using Hermes' native session and streaming API, through the gateway (`/assistant/*`). The model router stays backend-owned.
- **ARC-06 Agent tools.** The assistant gets a set of glasses-specific tools when the request comes from the glasses: open an app or screen, push cards or a document, start media, change supported settings, create notifications and condition-based reminders. Unsupported actions MUST be reported clearly, not simulated.
- **ARC-07 Model routing.** Requests from the glasses SHOULD favour a fast model (the agent's own routing decides; the gateway marks glasses turns), while honouring explicit overrides in the request ("use the bigger model for this"). The glasses UI MUST NOT show model names.
- **ARC-08 Extensibility.** Apps are modules registered with the launcher (name, icon, entry screen, optional badge, optional X action). Adding an app MUST NOT require touching other apps.

---

## 4. Display and rendering system

### 4.1 Canvas and typography

- **DSP-01** The image display uses a native 540×220 4-bit green master (brightness codes 0–15). Use exactly three lit tiers, defined once in `simulator/index.html` and once in Android `render/Tone.kt`: **PRIMARY=15**, **SECONDARY=10**, **TERTIARY=6**; black=0. Primary carries titles, body text, values, icons, the avatar, focused elements and full-brightness inverted selection bars with black text. Secondary carries unselected subtitles/tags/ages, rail captions, header context, page counters, section labels and supporting captions. Tertiary carries rules/dividers, dotted scroll tracks, inactive page dots, existing bar outlines/ticks, unchecked outlines, non-focused reactions and unavailable rows. Filled progress and active dots stay primary. Preserve all geometry and glyph masks; no gradients, dithering, shadows or anti-aliased output. Internal coverage rasterization MUST finish in flat tiers. Keep tiers at least four codes apart and none below 5; tuning should stay within ±1–2 of the initial values. Lens distinction remains unverified.
- **DSP-01a** Two-level output MUST map every positive master code to ON. Thresholding each grey golden at >0 MUST exactly reproduce its saved `golden-1bit/` reference. 4/8/16-level output quantizes the master codes; four levels must distinguish black and all three tiers. Already encoded codes map to mirror green as `round(level / 15 * 255)` (gamma 1), matching the acknowledged raster mirror. Stock 26×7 text output and its byte-exact goldens are unchanged.
- **DSP-01b** Actionable labels, approval commands/reasons, caller identity and Deny/Approve stay primary. Hint words are secondary with physical key glyphs primary; complete phone/assistant call controls stay primary for safe operation. Selection contents never dim. Do not add pixels to formerly empty bar interiors; the existing unfilled track outline steps down.
- **DSP-02** Fonts are bitmap or 1-bit-rasterised. The repository's 5 × 7 font at 2× is the baseline. Candidate replacements (VT323, Pixelify Sans, Inter rendered 1-bit, and others such as Spleen, Terminus or Cozette) MUST be compared on the real display before choosing.
- **DSP-03** Text sizes: small (labels, hints), medium (body), large (titles, values), extra-large (clock, timers).
- **DSP-04** Unsupported characters MUST render visibly (for example as "?") rather than disappear.

### 4.2 Layout primitives

- **DSP-10 App frame.** Most app screens have a 104 px left rail containing a 72 px animated app icon plus up to four short caption lines, and a right content area with a header (title left, time or context right) and a rule.
- **DSP-11 Full-width frame.** Text-heavy screens (T3 thread views, people cards, message reading where needed) drop the rail and use the full width. Status information moves to a bottom row: status on the left, page indicator in the centre, source or provider on the right.
- **DSP-12 Lists.** The focused row is a full-width **inverted bar** (green fill, black text), never a small dot. Icons on a focused row sit on a black chip so they stay visible. Rows may be one line or two (title plus subtitle). Lists show a scroll indicator when they overflow. At the default font, plain lists fit five 34 px rows or three 50 px two-line rows; reserving a hint row leaves four or two respectively. Keep glyph sizes; reduce row count first.
- **DSP-13 Section headers** in lists are small labels with a rule (for example NEEDS YOU, DONE, WORKING).
- **DSP-14 Pagination.** Paged text shows `< 1/3 >` (or a compact `1/3`) at the bottom; single pages show no footer. D-pad ▲▼ (and ◀▶ where free) change pages.
- **DSP-15 Hint row.** Screens with non-obvious actions show a one-line hint at the bottom (for example `Y read · X actions · L talk`).
- **DSP-16 Toasts.** Short confirmations (Sent, Queued, Dismissed) appear as a small inverted pill near the bottom for about 1.7 s. They are confirmations only, never notifications.
- **DSP-17 Marquee.** Single-line text that doesn't fit (for example song and artist on Home) scrolls sideways after a short pause.
- **DSP-18 Animated icons.** Each app has an animated icon (bouncing chat dots, blinking terminal cursor, equaliser bars, pulsing mic, spinner for working). Icons animate only where they communicate state. Icons SHOULD come from a shared inventory that the assistant can also reference (see REP-03).

### 4.3 Emoji and inline icons

- **DSP-20** Emoji MUST retain their monochrome binary glyph masks, using the applicable flat brightness tier, never colour. At least these 25 MUST be supported:
  😂 👀 😭 ❤️ ❗ ☝️ 😅 🥰 😜 😁 🖕 💀 ✅ ❌ 👍 👎 🔥 🙏 😮 😢 🎉 💯 🤔 😍 🙄
  The wink in the reaction palette is 😜 (wink with tongue out).
- **DSP-21** Unknown emoji render as a small placeholder box.
- **DSP-22** Inline icons are supported inside text (link chain, reel/play box).

### 4.4 Links in messages

- **DSP-30** Full URLs are never shown. A URL is replaced by a link icon and `link to <name>`, where `<name>` is the part of the host between the scheme (and `www.`) and the top-level domain (for example `nytimes`). Known short hosts map to a proper name (for example `youtu.be` → YouTube).
- **DSP-31** A message that is only an Instagram reel link renders as `<reel icon> sent an Instagram reel`. A reel link inside other text renders as `<reel icon> Instagram reel`.

---

## 5. Input and control scheme

### 5.1 Grip

- **INP-01** Default grip is **portrait with the D-pad on top** ("TV-remote" grip), usable one-handed in either hand, mostly left. Landscape MUST also work with the same bindings; the app un-rotates the D-pad.
- In the portrait grip: Y is the top face button (next to the D-pad), X right, B left, A bottom. L2, L, R, R2 run down the right edge (L2 at the top). − and + sit centre-right, ★ and ♥ centre-left.

### 5.2 Global rules

| Button | Tap | Hold (≥ 450 ms) |
|---|---|---|
| D-pad | Move focus / change page | — |
| **Y** | **Select**, always | — |
| **B** | **Back**, always | Home |
| **X** | Current app action | — |
| **L** | Voice memo app only: start/stop recording | **Microphone.** Dictate into the current screen. On screens with nothing to dictate into (Home, Apps…), talk to the assistant |
| **L2** | In the assistant: quiet wait on/off | Assignable shortcut (INP-19) |
| **A** | Now playing | — |
| **R** | Notifications | — |
| **R2** | Play / pause | Media layer: ▲▼ volume, ◀▶ skip |
| **−** | Lock (screen off) / unlock | **Passive listening on/off** (AMB-14); while locked, − only unlocks |
| **+** | App-specific secondary action (rare) | App-specific (for example clear all notifications) |
| **★** | Apps (shortcut; optional, since the Micro sends nothing for ★ in keyboard mode) | — |
| **♥** | People heard in the last 10 minutes (quick view) | — |

Requirements behind the table:

- **INP-10** Y is always select and B is always back, on every screen.
- **INP-11** X is the per-app button. Every screen where X does something MUST say so in a hint row or the bindings legend.
- **INP-12** L is the one microphone button. Push-to-talk (hold) everywhere except the Voice memo app, where it toggles recording.
- **INP-13** Voice call with the assistant MUST NOT be on a global button. It lives in the assistant's X menu.
- **INP-14** Status and Now playing are not important enough for prominent global buttons. Now playing gets the least reachable button (A). Status has no button.
- **INP-15 Lock.** − locks the controller and turns the screen off. While locked, every button except − is ignored. − again unlocks and shows a brief "Unlocked" toast. The lock toggles on release so that holding − can mean something else (AMB-14).
- **INP-16** Hold actions fire at 450 ms. A hold MUST NOT also fire the tap action. A screen may claim a button as momentary (down/up, no hold delay; INP-20).
- **INP-17 Media layer.** While R2 is held, the D-pad controls volume and skip without leaving the current screen. Releasing R2 without using the layer toggles play/pause.
- **INP-18** Buttons that are easy to bump (shoulders) MUST NOT trigger destructive actions. Destructive actions (clear all) use a hold on a hard-to-hit button.

### 5.3 Crown and head gestures

- **INP-30** Basic crown navigation SHOULD exist as a fallback: rotate = move, click = select. It SHOULD be possible to send an assistant message or a simple Beeper reply with the crown alone. Lower priority.
- **INP-31** Crown behaviour is not a "setting" screen; there are no crown settings rows.
- **INP-32** Head gestures (nod/shake/head-up) MAY be used as they are in stock firmware. Whether the phone receives these events is unverified.

---

## 6. Voice input and transcription

- **VOX-01** Dictation uses the glasses' microphones (wearer channel), streamed to the phone.
- **VOX-02** Transcription uses the gateway's OpenAI-compatible transcription endpoint (`llm.baseUrl`, `models.transcribe`). The transcript is then cleaned up by the same endpoint's cleanup model (`models.cleanup`; Wispr Flow-style cleanup with an optional vocabulary file). This applies to every dictation: Beeper messages, T3, reminders and voice memos. Assistant messages skip cleanup.
- **VOX-03 Phases.** While processing, the screen shows a single large status word: **Transcribing…** for the speech-to-text half, then **Enhancing…** for the cleanup half. No subtitles.
- **VOX-04 Preview.** After processing, the cleaned text is shown (paginated if long) before sending. Default actions: **Y send · B discard · hold L add more** (appends another dictation). No "make shorter" option.
- **VOX-05 Queue / steer.** When the target is a T3 thread that is currently working, the preview offers **Y queue** (send after the current turn), **X steer** (interrupt now) and **B discard**.
- **VOX-06** While listening, the screen shows LISTENING, elapsed time and a live level meter, with "release L to stop".
- **VOX-07 The assistant is different.** Talking to the assistant does not use the preview screen. As in the web HUD (`hud/`), releasing L sends immediately, the avatar switches to listening while you speak, and the transcribed prompt is shown while the assistant thinks.

---

## 7. Home screen

- **HOME-01** Home is a glance screen, shown when the display wakes. No avatar on Home.
- **HOME-02** Top: large clock with AM/PM, full date, weather icon plus temperature (°F) plus a short condition (for example "rain after 4 PM").
- **HOME-03** Below: five full-width badge rows, each with an icon. Something is always selected (no "nothing selected" state). In order:
  1. Beeper: unread count and who.
  2. T3 Code: "N need you · M done".
  3. Now playing: song and artist (Spotify), book title (audiobooks) or video title; "Paused ·" prefix when paused; marquee when long.
  4. Notifications count.
  5. Next calendar event (last, not first).
- **HOME-04** Y opens the selected badge's app. B on Home opens Apps (Home is the root, so B has nothing else to do); B in Apps returns Home. ★ also opens Apps when the controller reports it. There is no "press right for apps" shortcut (it can't be reversed the same way).
- **HOME-05** Home layout still needs refinement; treat the current design as v1.

## 8. Apps launcher

- **APP-01** Opened with B from Home (hold B, then press B, from anywhere), or ★ when available. Grid of 4 × 2 large animated icons per page with labels, with page dots at the bottom. The focused app has a thick rounded outline, an animated icon and an inverted label.
- **APP-02** Default order: Assistant, Beeper, Media, T3 Code, Calendar, Notifications, People, Morning report, Health, Home Assistant, Docs, Teleprompter, Voice memo, Status, Settings, Phone, Clock. Apps whose integration is switched off in the phone settings are hidden.
- **APP-03** The order MUST be user-arrangeable in the phone app. It SHOULD also be arrangeable on the glasses (Settings → App order: Y picks an app up, ▲▼ moves it, Y drops it).
- **APP-04** Labels that don't fit are shortened in the grid only (for example "Notifs").

---

## 9. Assistant app

Layout follows the web HUD (`hud/`):

- **JOY-01** The avatar pack's native 192×288 cell is OR/max-pooled at 2/3 to 128×192, positioned at (8,10), without distortion. Reply text starts at (150,14), with 31 columns × 9 lines in the existing 5×7 font at 2×. Status occupies y=176 and pagination y=196. The 3/4 alternative (144×216) leaves too little breathing room. No model name, emoticon, debug labels, or "Hermes".
- **JOY-02** Animations come from an avatar pack ([avatar-packs.md](avatar-packs.md)): 50 named states, per-state frame timing and playback policy (loop, once, once-then-hold, subtle loop, bounded loop), and fallbacks for states a pack leaves out. A CC0 placeholder pack ships with the app; users can import their own.
- **JOY-03** Activity events map to animations: idle, listening, thinking, working, searching, remembering, saving, speaking, waiting, delegating, error. Emotes (happy, laughing, curious, and so on) can be triggered by the backend. Lifecycle events MUST come from real execution hooks, never be faked.
- **JOY-04** **Hold L** (from the assistant or from any screen with nothing to dictate) records a message. The avatar shows the listening animation and a small mic marker. Release sends immediately; the transcribed prompt is shown while the assistant thinks; the previous reply stays readable until the new one arrives.
- **JOY-05 Quiet wait (L2).** When on, sending a message blanks the entire display (only a small listening marker while speaking) until the reply arrives, then the avatar reappears normally. For long-running questions mid-conversation. A small "QUIET" marker shows when quiet wait is armed.
- **JOY-06 X menu:** Voice call, New conversation, Call layout (Compact/Full).
- **JOY-07 Voice call** streams two-way audio over `/assistant/call`, with the avatar animating from bridge listening / thinking / speaking states, a call timer and state text. B hangs up; X toggles mute (silence frames preserve stream timing). The hint row names both actions. If the bridge is unavailable, fall back to the existing dictation loop and label it **VOICE TURNS**, with paginated replies and B hang up. A cellular call ends the assistant call. Ended calls show a reason and return to the assistant screen; never redial a call that ended, but a live call whose link drops reconnects transparently for up to 60 s (state "Reconnecting…").
- **JOY-08 Long calls (2026-11-17):** calls may last hours (4 h runaway caps on phone and gateway). A voice bridge that supports resume (`X-Assistant-Call-Resume`, `call_start.resume`) can roll into a fresh realtime session with a compacted transcript without ending the call. Locking the lens never ends or interrupts an assistant call: the lens goes dark, the call continues, any button unlocks and lights it without acting, and only B on the lit call screen hangs up. LifeLog capture stays excluded for the whole call, reconnects included, and resumes after it.
- **JOY-09 Compact call layout (default):** a corner card (small avatar, a smaller worker portrait while the main agent works, "Voice call · m:ss", state, mute, hint) at the bottom left; the rest of the lens stays dark. Full is the old layout and stays a setting (assistant X menu, pref `power.joyCallLayout`). Turn-based calls use the full layout.
- **JOY-10 Call animations:** every call and agent state maps to a canonical avatar state (render/CallAnimations.kt, mirrored in the simulator; see docs/avatar-packs.md): voice connecting, listening, thinking, speaking, delegating, waiting, asking, interrupted, compacting, reconnecting, muted, error; the worker (main agent) started, working, tool families (searching, remembering, saving, checking the time, delegating, running code, creating, summarising, asking, sending), approval, done, error, late. All 16 activity states are used.
- **JOY-08** If the user leaves the assistant before a reply arrives, the reply MUST also be added to Notifications ("<assistant> replied", high importance). Opening it returns to the assistant.
- **JOY-09** The assistant has glasses tools (ARC-06), for example "put on an interesting video" picks from the five most recent videos of the curated YouTube list. This must be natural-language driven, not hard-coded commands.
- **JOY-10** The built-in RayNeo assistant SHOULD be disabled or ignored. Everything it can do, the assistant should be able to do.

## 10. Morning report app

- **REP-01** Its own app (not inside the assistant). Opens the latest morning report.
- **REP-02** The gateway produces the report as JSON (from files in `REPORT_SOURCE_DIR`, or generated by the smart model): an ordered array of typed cards (weather, calendar, T3, news, local, events, health, to-do, …), each with a heading, short body and an icon reference. The report has personality and flavour; keep it.
- **REP-03** Card icons come from a shared icon inventory that the assistant can choose from when generating cards.
- **REP-04** One card at a time, with four large text lines per body page and ▲▼ for continuation, left rail with the card's (animated) icon and heading, large body text, page dots. ◀▶ move between cards; ▲▼ read body continuation pages.

---

## 11. Beeper (messaging)

Beeper is the only messaging surface. Support must be first-class.

### 11.1 Chat list

- **BEEP-01** Two-line rows: chat name, plus last message preview (with sender prefix in groups, emoji rendered, links summarised). Unread count on the right ("3 new"). No timestamps.
- **BEEP-02** Rail shows the total unread count.
- **BEEP-03** Chat order (setting `beeper.order`, default `PRIORITY`; X → Order switches it). Priority: pinned chats (Beeper pin or favourite label, or pinned on the glasses) show "pin" on the right and come first; then important unread chats (an @mention, a question in a DM, a direct chat with a person, the owner writes there often or answers fast, or the classifier rated a recent notification 0.7+); then other unread; then the rest by recency. Unread muted, spam and low-priority chats fold into one "Quiet (N)" row at the bottom; Y shows or hides them. `RECENT` lists the pins, then the BEEP-02 order with nothing folded. The rail count never includes muted or spam chats.
- **BEEP-04** Find a chat: hold L on the list (or X → Find chat), say a name. A clear match opens at once; otherwise the candidates are listed. Matching covers chat titles, participant names and phone-contact nicknames, and tolerates misheard spellings.
- **BEEP-05** X on a chat row: Find chat, Pin on glasses / Unpin, Mark spam / Not spam, Order. Glasses pins are stored on the gateway (the assistant can read them) and never change Beeper itself.

### 11.2 Thread view

- **BEEP-10** Opens scrolled to the newest message. ▲ scrolls back through older messages, ▼ forward.
- **BEEP-11** Each message shows the sender (no time or date), up to 4 lines of text (with an ellipsis if longer) and its reactions. The user's own reaction is outlined.
- **BEEP-12** The focused message is marked with a bar on its left edge. (A full-width highlight is the alternative; keep it adjustable.)
- **BEEP-13** **Hold L = send a new message to the chat.** This is the main action. It is a normal message, not a reply.
- **BEEP-14** **Y = read the full message** (paginated).
- **BEEP-15** **X = message actions:** React, Reply, Read full message.
- **BEEP-16** Hint row: `Y read · X react / reply · L message`.

### 11.3 Reading, replying, reacting

- **BEEP-20** Read full message: paginated, header shows the sender and network; X opens message actions; hold L replies to that message.
- **BEEP-21** Reply: shows the quoted message (paginated with ◀▶ if long) and "Hold L and say your reply". After sending, return to the thread.
- **BEEP-22 Reaction picker.** A horizontal carousel of 7 visible emoji (44 px). Reactions already on the message come first, then the 25-emoji palette (DSP-20).
  - The selection box moves until it reaches the second position, then the row scrolls under it until the end of the list.
  - A small ▲ marker sits under each visible emoji that is already on the message, plus the label "already on message".
  - Arrows show when more emoji are off-screen. A counter shows the position (`4/25`).
  - Y sends the reaction.
- **BEEP-23 Approvals.** The assistant's approval requests arrive as Beeper DMs (from a bot named like the configured assistant) with ✅ and ❌ already on them. Reacting ✅ approves and ❌ cancels; the toast confirms "Approved · <assistant> notified". Approvals can also arrive in Discord channels; those must be handled the same way where Beeper exposes them.

### 11.4 Integration

- **BEEP-30** Use Beeper's supported APIs: the Android content provider (`content://com.beeper.api/…`) for reading chats/messages and sending, and the Beeper Desktop API (local HTTP) for reactions if the Android provider lacks them. No UI scripting.
- **BEEP-31** The assistant keeps a dedicated glasses channel, but personal messaging goes through Beeper, not a separate Discord integration.

---

## 12. Notifications

- **NOT-01 Three routes (2026-11 policy).** Every notification is routed **suppress** (never on the glasses), **stream** (Notifications list only) or **popup** (list plus a card that lights a dark lens for a few seconds from any screen, then lets it go dark again; Settings > Display & sleep: popups on/off and time). Pop-ups are held (list only) during a phone or assistant call and on busy screens (dictation, teleprompter). Contract: `POST /glasses/v1/notify/route` ([gateway README](../server/gateway/README.md)); rules: `lib/notify-policy.mjs` with the policy data in `notify-policy.default.json`, mirrored by `data/NotificationRules.kt` and `data/NotifyPolicy.kt`, one shared fixture. Ringing calls from contacts still take over (CALL-01).
- **NOT-02 Rules, then the classifier.** Deterministic rules decide first (default deny); the classifier (versioned prompt `prompts/notify-route.md`, templated with the owner and assistant names) decides only semantic cases (Beeper messages, ambiguous app text, T3 progress text). When the classifier is unavailable the fallback never pops up (Beeper: list at low importance; anything else: suppress).
- **NOT-03 Sources (default policy, `notify-policy.default.json`; every kind can be switched off and other apps allowed per package):** Beeper (muted and spam chats suppressed, Reel/TikTok shares, reactions and group banter suppressed, the classifier for the rest); T3 only for threads watched from the glasses (X in a thread > Watch: needs you = popup, finished = list; threads created, messaged or answered from the glasses are watched automatically for 24 h; "Notify all needing me" in the T3 menu, default on, also pops up needs-you from any thread), detected by the gateway's own T3 notifier, not the T3 Code Android app; the assistant's intentional notifications (`/assistant/notify {popup}`) and Hermes conversations (the glasses-notify plugin); calls from contacts (missed: list; unknown or verification failed: suppress); an optional dating-app preset, off by default (match and message: popup; like: list; promotions: suppress); apps the user allows by package (`apps`). Everything else is suppressed on the phone with no network call: other messaging apps (messaging goes through Beeper), email, promotions, YouTube, Home Assistant, deliveries.
- **NOT-04 Importance.** The classifier also scores each notification's importance (0–1). The list is ordered by importance × time decay (current prototype: `importance × e^(−age_minutes / 120)`). Importance shows as 1–3 dots next to the age.
- **NOT-05 Actions:** **Y closes the list and opens the owning app at the target** (Beeper thread, resolved through `/beeper/resolve`; T3 thread; calendar day; phone recents; the assistant), then clears it; a row with no glasses app opens a reader and stays. **X dismisses** it (never reads, archives or hides the source conversation). On a pop-up: Y opens, B hides the card, X dismisses. **Hold + clears all** (deliberately hard to hit by accident). Hint row shows all three.
- **NOT-06 Condition-based notifications.** The assistant can create notifications that fire on a condition: "next time I'm at <place>", "next time <person> is recognised". These need expiry, cancellation and a way to see what's armed.
- **NOT-07** Assistant replies that arrive after the user leaves the assistant become notifications (JOY-08).

### 12.1 Phone calls

Goal: see who is calling, pick up, choose the audio device, hang up and mute, all from the glasses.

- **CALL-01 Incoming takeover.** A ringing call that matters MUST wake a locked/blank glasses display and show a full-screen call card (Y answer, B decline): a number the owner has had real calls with (an answered call from it or a call they placed to it, from the phone's call log; this overrides every other signal), a saved contact, or a recognisable local business by caller ID (food delivery, a restaurant or pizza place, package delivery, an apartment complex and similar local services; survey companies and call centres are not businesses). Withheld/private numbers (never a takeover, whatever the history) and other unknown numbers ring quietly: Home's Phone row, Recents and a missed-call notice. Suspected spam (carrier "Scam Likely"/"Spam Risk" caller name) and failed STIR/SHAKEN verification MUST NOT take over or leave a notice: Recents only (2026-11-18). The rule is deterministic, evaluated on the phone (never the classifier), and is the "call" kind of the shared routing table (NotificationRules / notify-policy.mjs). When the phone cannot tell (contacts permission off, or no number in basic mode) the takeover applies. This is the only exception to NOT-01, P-2 and the lock rule INP-15. The previous screen objects, focus/page positions and lock state MUST be restored after hiding or ending the call. Ordinary notifications remain non-interrupting.
- **CALL-02 Identity.** Show a phone icon, large contact name (when known), otherwise formatted number, otherwise "Unknown caller". Show number and SIM/account label where available. Long identity, device names and capability explanations MUST paginate in bitmap and both text grids; no silent truncation.
- **CALL-03 Incoming controls.** Y answers; B declines (the explicit ringing-call exception to INP-10); X silences/hides the glasses card only. The phone continues ringing. The card MUST NOT reopen on repeated state/identity updates, but MUST remain reachable from Home's Phone row and Notifications. A later waiting call MAY take over again.
- **CALL-04 In-call controls.** Show caller, running duration, observed mute state and current audio route. Y mutes/unmutes, X opens the available audio-device list (Y selects), B tap leaves the screen with the call continuing, and + tap hangs up. B hold retains global Home; + hold does not hang up. This uses InputEngine's existing + secondary action; hanging up never depends on a slow B hold. Every call screen MUST show the relevant hint row; text uses compact hints plus paged action descriptions.
- **CALL-05 Audio.** Offer only Telecom's available endpoints: Bluetooth/headsets by name, earpiece, speaker or wired headset when present. Show the current route. Failed or disconnected route requests MUST report failure; never pretend a route changed. The glasses have no speakers (HW-05): they appear only if Android actually exposes an audio endpoint.
- **CALL-06 Access.** Calls MUST be optional and MUST NOT require changing the default dialer. Android 12+ SHOULD use a non-UI InCallService with MANAGE_ONGOING_CALLS and a real CompanionDeviceManager association to the glasses/controller. Setup MUST explain/request optional phone-state, answer/end, contacts and incoming-number permissions separately. An association is distinct from an ordinary Bluetooth bond.
- **CALL-07 Basic mode.** Android 10/11 or missing companion access MUST fall back to phone-state observation and ANSWER_PHONE_CALLS answer/end APIs where granted. Caller number/name may be unavailable; READ_CALL_LOG may be restricted by Android. Mute/audio routing MUST be explicitly marked as phone-only. Aggregate OFFHOOK timing is an estimate, including possible dialing time. Aggregate call waiting MUST show a phone-only warning and disable ambiguous controls rather than risk ending the first call.
- **CALL-08 Multiple calls.** Companion mode MUST track calls independently. At minimum, show and allow declining a second ringing call without ending the first; answering delegates hold behavior to Telecom. Remote end, local decline, phone-side answer/end and permission/device loss MUST not crash or leave a stale card.
- **CALL-09 Return and missed calls.** Ongoing calls MUST have an indicator in Home/status chrome and a route back through Home/Notifications. Unanswered calls MUST become local Notifications entries with source "Phone" (suspected spam and failed verification appear only in Recents); explicit declines/answered calls MUST NOT be misclassified. Caller information MUST stay out of the gateway's notification classification. (2026-11-17) The assistant MAY see contact names, numbers and call history through the agent operations (`contacts.search`, `call.recents`, `call.state`, CALL-15); numbers are masked to the last four digits unless the assistant says the owner asked for them. Retain up to 30 missed-call notices privately across process restarts; dismiss/clear removes them.
- **CALL-10 Verification.** Pure call state, route selection, identity fallback and duration MUST be unit-testable without Android. FakeDataStore and the hollow simulator MUST provide incoming, active, audio-picker and waiting fixtures, bitmap goldens and text goldens. Real association, Telecom binding/control and audio routing MUST be tested on a phone; emulator/fake success is not hardware verification.
- **CALL-11 Phone app.** The Apps menu MUST have a Phone app with three tabs, switched with X (and ◀▶ outside Contacts): **Favorites** (starred contacts A-Z, padded under a "Frequent" heading with the people the owner calls most), **Recents** (the phone's call log: name or number, kind and age; Y calls back; calls that never rang because Call Screen handled them read "screened"), **Contacts** (A-Z with letter headings; ◀▶ jumps a letter). Hold L to say a name: the transcript is matched on the phone (name, nickname, relationship such as "mom", misspellings) and opens the confirm card, or a short list when several people match. The microphone is refused while a call exists. Without the contacts or call-log permission the tab says so.
- **CALL-12 Placing calls.** Calls are placed with TelecomManager.placeCall (CALL_PHONE; the default dialer is unchanged). Every placement from the glasses MUST take one deliberate Y on a "Call Mom?" card that shows the number label and number; B cancels. The card holds the lens lit for at most 30 s, then the normal idle fade applies. An assistant card whose wait ended (timeout or cancel) MUST NOT place a call even if it reappears after an incoming-call takeover. Emergency numbers and codes containing * or # MUST be refused (use the phone). A contact with several numbers and no primary/single mobile number asks which number first.
- **CALL-13 Dialing and active call.** After placement the glasses show the call screen ("Calling", name, number label and number, mute state, audio route; no timer until connected), then the in-call controls of CALL-04. Ringing, screening and dialing keep the lens lit; a connected call may fade like any screen and any button wakes it. The audio route picker lists Telecom's endpoints dynamically (earbuds and car units by name, handset/earpiece, speaker, wired). Calls placed from the glasses or by the assistant default to the connected Bluetooth earbuds when Telecom lists them.
- **CALL-14 Out of scope this pass.** No DTMF keypad. Self-managed VoIP calls (WhatsApp, Meet, Discord) are not shown on the glasses.
- **CALL-15 Agent control.** The assistant controls calls through agent operations (android/AGENT_OPS.md): `contacts.search`, `call.place` (contact name, nickname/relationship or id, or number; optional label and route; ambiguous names return candidates instead of calling), `call.answer`, `call.decline`, `call.hangup`, `call.mute`, `call.routes`, `call.route`, `call.recents`, `call.state`. `call.place` follows the agent confirm policy: from the glasses conversation it dials at once; otherwise (or with policy "always") the glasses show the same "Call Mom?" card and the assistant gets `denied`/`timeout` unless the owner presses Y. After the assistant places a call the glasses show the call screen.
- **CALL-16 Setup and diagnostics.** `glassesctl setup` MUST grant every call permission and the MANAGE_ONGOING_CALLS app-op and read the app-op back; `doctor` reports it. The debug bridge `/state` has a `calls` block (phases, routes, access; no names or numbers) and `/call/op` runs one call op.

---

## 13. T3 Code

T3 Code is the interface for managing coding agents across the user's machines. Vocabulary follows T3 Code itself.

### 13.1 Thread list (root)

- **T3-01** Root screen keeps the app rail (with the terminal icon). Sections, in order:
  1. **NEEDS YOU**: threads with status Input, Approval, Request, Limited or Failed.
  2. **DONE**: finished threads with unseen results.
  3. **WORKING**: running threads. Each row's subtitle shows the current step and how long it has been running (for example `Running command: pnpm test · 12:07`).
  4. **MORE**: New thread, Usage limits, Environments, Settled.
- **T3-02** Rows show a status tag (INPUT, APPROVAL, LIMITED, FAILED, DONE, WORKING, WAITING), the title, project · provider (Claude Code, Codex, Cursor…), and age.
- **T3-03 Settled** opens the 25 most recently settled threads (there will be thousands). Each MUST be openable and readable, and messaging it un-settles it.

### 13.2 Thread screens (full width)

- **T3-10** All thread screens use the full-width frame (no rail): title and project in the header, and a bottom row with status (with icon) on the left, page indicator centred and provider on the right.
- **T3-11 Done / limited / failed / settled.** Shows the latest agent message, paginated, at full width. Show the message as returned; no "Done:" prefix, no bracketed project names.
- **T3-12 Working.** Shows NOW plus the step kind (for example Running command), what it is doing (the command), a large live timer for how long the current step has been running, and a warning when it is long-running or has produced no output for a long time (for example "no output for 1h 40m"). Below that, RECENT steps with durations. Y opens the latest agent message.
- **T3-13 Thread history (X).** On every thread screen, X opens history mode: a list of **user and agent turns only** (no tool calls), newest at the bottom and focused first. ▲ scrolls back. Y opens any entry as a full paginated message. Most of the time the latest message is enough; history is for "what was that value earlier?".
- **T3-14 Messaging a thread.** Hold L. Every message sent from the glasses gets the `/glasses` skill appended (13.4). On a working thread the preview offers Queue (Y) / Steer (X) / Discard (B). On other threads, Y sends, the thread becomes Working, and the screen switches to the Working view.

### 13.3 Input requests

- **T3-20** Support every T3 input type:
  - single-choice questions, plus "Other…", answered by holding L
  - multi-select questions with checkboxes and a Submit row
  - free-text answers by dictation
  - command or file-change approvals: Approve once, Approve for this thread, Deny
  - synchronous and asynchronous input
- **T3-21** Long questions paginate: the question area shows 2 lines with ◀▶ pages, and options are listed below.
- **T3-22** Approvals show the command in a box and the reason underneath.

### 13.4 `/glasses` skill

- **T3-30** Every prompt sent from the glasses carries a skill (invoked as `/glasses`) telling the agent to answer in a glasses-friendly format. The agent knows the page size and pagination: one page if the meaning survives, two is fine, three is tolerable, four or more should be avoided.
- **T3-31** The format must also read well on a computer: very concise, not cryptic.
- **T3-32** The glasses MUST still read replies of any length (for example ones not written for the glasses).

### 13.5 Usage limits and environments

- **T3-40** Usage limits are shown **per provider** (two windows per page; ◀▶ or ▲▼ step through continuation pages and providers): Claude Code, Codex, Cursor and others as configured. Cost and token totals are not shown.
- **T3-41** Each provider shows its windows, for example Claude Code: Session (5-hour), Weekly, Weekly · Fable. Each window has a usage bar with a tick at the elapsed-time position, a percentage, the reset time, and pace (ahead of pace / on pace / under pace).
- **T3-42** Banked resets (for example Claude Code "Weekly reset", "Fable reset") are listed with counts; X uses one (pick which).
- **T3-43** Environments list each machine with online/offline status, OS and last seen.
- **T3-44** New thread: pick a project (creating projects is out of scope), then dictate the first prompt.
- **T3-45** Before building the client, the live T3 Code API and data model MUST be checked. Status vocabulary in this section comes from the T3 Code source as of 2026-11-11 and may change.

---

## 14. Media

- **MED-01 Now playing** (A from anywhere, or from Home): left rail with an animated source icon (equaliser, book, play) and PAUSED when paused. Shows title (2 lines max), subtitle (artist · playlist, or author · chapter, or channel), a progress bar with elapsed and total time, and either the Spotify output device or the playback speed, plus volume. A clear "PAUSED" label; no ambiguous play-triangle glyph.
- **MED-02 Controls:** Y play/pause. ◀▶ = −/+ 30 s for audiobooks and YouTube, previous/next track for Spotify. ▲▼ volume. R2 play/pause and the R2 media layer work everywhere.
- **MED-03 X menu:** first item is **Output device** when Spotify is playing, or **Playback speed** (1×, 1.25×, 1.5×, 1.75×, 2×) for audiobooks and YouTube. Then CHANGE MEDIA: Audiobooks, Spotify, YouTube.
- **MED-10 Audiobookshelf:** the **5 most recently played** unfinished books (libraries can hold thousands). Shows author, chapter and progress. A book counts as finished when ABS marks it finished or hidden from Continue Listening, or at 97% or more (books that stopped in the closing credits). Nothing is written back to ABS.
- **MED-20 Spotify:** Discover Weekly, Currently playing, Liked Songs, then recent playlists. (The order of the first three follows the 11 PM review notes: "underneath Discover, above Liked Songs"; confirm.)
- **MED-21** Starting Spotify playback goes to **whichever device Spotify is already using** (TV, phone, desktop…). No device prompt. The device is changed from the X menu. When nothing is active: the phone if it is the only available device, otherwise a device picker (gateway `SPOTIFY_COLD_START=off` restores the plain error).
- **MED-22** Now playing shows song and artist for Spotify, not the playlist name.
- **MED-30 YouTube:** opening YouTube goes directly to the gateway's **curated list** (`youtube.listName`, default "Picks"), one page with a scrollable list of up to **25** videos, without a playlist chooser. Each row shows title and **channel name plus length**, not date added. B returns to the screen YouTube was opened from. No "25 latest" labels. Watch Later and History are excluded because neither can be read through the official YouTube API; no scraping.
- **MED-31** Output device selection applies to Spotify only.
- **MED-40** Headphones are paired to the phone; all media audio goes there or to the chosen Spotify device.

## 15. Calendar

- **CAL-01** Agenda grouped by day (TODAY, TOMORROW, dates), two-line rows: event title, plus time · location. Times shown as `8:00 PM` (never `8:00p`).
- **CAL-02** Y opens the event detail. The assistant reminds before events.

## 16. People

People come from the gateway's people source (`people.json`, `PEOPLE_FILE`; see `server/gateway/people.example.json`) with optional voice embeddings. Unknown voices are never named. Without a people file the People app is empty.

- **PPL-01 Quick view (heart).** Shows recognised people heard within ten minutes, immediately from the phone's persistent log merged with server recents. Left/right cycles people; up/down pages the blurb. The owner's voice and muted people never count. Hold L arms a person reminder. Passive listening off keeps the existing Off explanation.
- **PPL-02 People app.** Recent people show name, last-heard time and coordinates when available. Cards page the server's glasses blurb on raster and both text grids. X opens "Don't count this person" or "Count this person again", followed by confirmation. Changes apply locally at once and retry on the gateway when offline. The phone ambient screen lists muted profiles with an unmute control.
- **PPL-03 Cards.** Notes are the server's glasses blurb (up to 500 characters), refreshed on opening a card and with the voice list. Offline cards use the last cached notes.
- **PPL-04** Enrolment and identity linking happen outside the glasses. Low-confidence or ambiguous matches never display a name.

## 17. Health

A separate app. Health data is not shown in Status.

- **HLT-01** Overview list: Weight, Readiness, Sleep, HRV, Resting HR, shown three at a time with scrolling and a page count. Each row shows the value and a 14-day sparkline. The focused row shows when the value was measured (for example "6:41 AM today") so stale data isn't mistaken for live.
- **HLT-02** Y opens a metric: a bar chart of history with ◀▶ switching 7 / 30 days, low/high/average, and (▼) breakdown pages of four rows (▲ returns toward the chart):
  - Readiness contributors (resting HR, HRV balance, body temperature, recovery index, sleep, previous-day activity, activity balance, sleep balance) as bars.
  - Sleep components (total, efficiency, restfulness, REM, deep, latency, timing).
  - Weight details (7-day average, 30-day change, body composition if available).
  - HRV and resting HR summary values.
- **HLT-03** Sources (optional): Oura for readiness, sleep, HRV and RHR; Weight Gurus for weight.

## 18. Status

- **STA-01** Batteries with bars: glasses, phone, Oura ring, earbuds. Phone Wi-Fi network and cell signal. Display brightness.
- **STA-02** No health data here, and no global button (reach it from Apps).

## 19. Home Assistant

- **HA-01** Small, curated set from the gateway configuration (`homeAssistant.scenes`, `homeAssistant.alarm`, `homeAssistant.printers`); the assistant handles everything else:
  - Scenes, each with a name, icon and entity (each asks for confirmation).
  - Alarm time: toggle between the configured times (for example 7:00 AM / 8:30 AM).
  - Alarm style: toggle between the configured styles (for example Gentle / Loud).
  - 3D printers, with progress, time left or layer count, and a detail page.
- **HA-02** Adding scenes, alarm times and printers is configuration only.

## 20. Docs and teleprompter

- **DOC-01** A flat library of documents (no categories) that the assistant or another approved sender can push to the glasses: meeting notes, articles, reports. The reader is paginated.
- **DOC-02** Kavita/e-reader integration MAY come later (low priority).
- **TEL-01** Teleprompter: large text, Y toggles auto-scroll, ▲▼ moves one line, position counter. (Conversational "Conversate"-style overlays are explicitly not wanted.)

## 21. Voice memo

- **MEMO-01** In the Voice memo app, **tap L to start, tap L again to stop.** Shows RECORDING / READY, a large timer and today's memos.
- **MEMO-02** A memo forces recording (bypassing speech detection) and is saved as its own recording, excluded from ambient conversation audio, with its time range recorded as a capture exclusion.
- **MEMO-03** Memos are transcribed with the same transcription route (VOX-02), and a title is generated by the gateway's smart model (`models.smart`). The list shows titles (with time and duration), not just times. Titles show "Transcribing…" until ready.
- **MEMO-04** Every memo of 1 s or more uploads automatically through the gateway conversation route (`kind:"memo"`) and is delivered to the targets routed for memos ([targets.md](targets.md)); the row shows "sent to <target name>".
- **MEMO-05** Many memo-style notes can just be assistant messages instead; the memo app exists for recordings.

## 22. Settings

- **SET-01** Brightness, App order (APP-03), head gestures, display position, About.
- **SET-02** No built-in-assistant toggle, no crown settings.

---

## 23. Ambient audio capture and conversations

- **AMB-01** LifeLog wake events provide speech capture without RayNeo cloud processing or summaries.
- **AMB-02** Retain received Opus packets unchanged in a two-channel Ogg stream. Mono LifeLog packets decode as dual mono; the continuous source has two channels.
- **AMB-03** Continuous fallback uses the phone's existing energy/zero-crossing speech detector with hangover. It is not a learned VAD.
- **AMB-04** Cached LifeLog audio remains an availability probe only; automatic import is not built.
- **AMB-05** Dictation, memo and microphone-test exclusions drop overlapping packets in full. These explicit recordings keep their separate flows.
- **AMB-06 Storage.** One private speech-only Ogg/Opus file and durable record per conversation. Midnight does not split a conversation. Retention removes uploaded or skipped conversation files, including metadata, after the configured period; files not yet successfully uploaded are preserved.
- **AMB-07 Upload.** Closed conversations with at least 30 seconds of speech go to the existing gateway: manifest first, then raw Ogg audio. The gateway transcribes them and delivers them to the targets routed for ambient recordings ([targets.md](targets.md)).
- **AMB-08 Conversations.** Start with speech; close after ten minutes without speech (configurable), on mute/disable, service stop, discontinuity, or before the file reaches 89 MB. Explicit older non-default gap settings are retained; the old three-minute default becomes ten minutes.
- **AMB-09 Location.** Request one bounded fix at conversation start, then every five minutes while open. Reuse a fix under two minutes old with accuracy at most 50 metres. Missing permission or foreground eligibility sends no fixes. No live geocoding.
- **AMB-10 Manifest.** Send stable clientId, device, UTC start/end, speech seconds, IANA timezone, segments and optional fixes. Each segment maps an exact retained Opus sample offset to UTC; runs separated by less than 300 ms merge. The first offset is zero and subsequent offsets strictly increase. No daily CSV.
- **AMB-11 Recovery.** Sync audio before atomically checkpointing its record. Restart trims only the uncommitted tail and seals the last conversation; inconsistent audio is preserved with a failure reason. Stable IDs and staged upload receipts make retries idempotent. Retry transient failures exponentially; 429 waits at least 30 minutes, 400 is terminal, and audio 409 counts as already uploaded.
- **AMB-12 Processing and display.** The foreground service resumes the private upload queue, using any network by default or Wi-Fi only when selected. The phone lists recording, waiting, uploading, uploaded, transcribed, skipped and failed conversations. It polls the server after audio upload; transcribed means the server reports timed. No local transcription is implemented.
- **AMB-13 Privacy.** Obtain consent and verify the hardware recording LED. Files use app isolation and device storage encryption; backups remain disabled. Passive listening is on by default; retention defaults to 30 days.
- **AMB-14 Passive listening mute.** Hold minus on the unlocked remote to toggle ambient recording and voice matching together. Phone pause/disable controls the same persisted switch. Locked minus only unlocks. While off, no audio enters recognition; Home shows LISTEN OFF, People shows Off, and the quick view explains how to resume. Explicit dictation, memos and calls are unaffected.

## 24. Speaker recognition

- **SPK-01** The phone downloads the SHA-256-pinned WeSpeaker ResNet34-LM ONNX model on demand, over Wi-Fi by default. Recognition stays off until verification succeeds. Voice profiles and ETags are cached privately and refreshed at start, every 30 minutes and after mute changes.
- **SPK-02** With passive listening on and speech present, identify recent voiced audio about every three seconds on a separate worker, dropping busy ticks. Use 1.5 to 6 seconds, mono 16 kHz, an 80-bin Kaldi fbank frontend and 256-dimensional normalised embeddings. Accept only scores at least 0.55 (setting 0.30 to 0.90), a best-versus-second-person margin of at least 0.08, and the same winner in two consecutive windows. Otherwise show nothing. Own and muted profiles compete but never produce a recognition event.
- **SPK-03** Persist at most one heard event per person per minute, including the matching profile, UTC time, score and a location fix less than ten minutes old when available. Publish locally immediately, then retry batches of at most 200 to the gateway. Malformed events are excluded; a rejected 400 batch is dropped and logged.
- **SPK-04** Recognition sends no audio off the phone and stores no unknown embeddings. Python reference fixtures verify fbank parity; an opt-in test verifies model embedding parity. Enrolment, transcription and identity correction remain outside this local recogniser.

## 25. Other features (open)

- **NAV-01** Navigation with route guidance. Source and presentation to be decided after testing (OpenRayneo's Android app already follows navigation notifications).
- **WX-01** Weather on Home: number °F plus a small icon plus a short condition.

---

## 26. Privacy and safety

- **PRV-01** No camera on the device. Microphone use is signalled by the hardware LED.
- **PRV-02** People data is personal knowledge about others: keep it minimal, correctable and deletable.
- **PRV-03** API keys and tokens live in the Android Keystore; nothing sensitive goes in logs.

## 27. Simulator

The simulator (`simulator/index.html`) is the design reference. It MUST stay hollow: fake data only, no real APIs.

- **SIM-01** Real controller photo with clickable buttons; portrait (D-pad up) and landscape grips; optional hit-area overlay; keyboard fallback.
- **SIM-02** 540 × 220 three-tier 4-bit display with a Greys 16 / 2 (1-bit) comparison selector with font choices (5 × 7 repo, VT323, Pixelify Sans, Inter 1-bit) and zoom (fit / 1:1).
- **SIM-03** Backgrounds: black, or a user-loaded video looped behind the display (additive "screen" blend, like a waveguide).
- **SIM-04** Live bindings table showing what every button does on the current screen (screen-specific rows highlighted).
- **SIM-05** Simulated incoming notifications showing the classifier's notify/suppress decision.
- **SIM-06** Draws the avatar from the same avatar pack format as the app (`simulator/avatar/default/`, or `?avatar=<url>` / the file picker; see `docs/avatar-packs.md`).

## 28. Phases

1. **Now (no hardware):** finish the screens in the simulator. Build the phone app against a simulated transport. Port OpenRayneo's Android link code.
2. **Week one with hardware:**
   - Bluetooth link on the actual firmware version.
   - Coexistence with the official app.
   - Real pixel resolution and characters per line.
   - Page-change speed.
   - Crown and head events.
   - Mic streaming duration and battery.
   - LifeLog wake behaviour and cached audio.
   - Whether an image/bitmap command exists.
   - Whether the built-in assistant can be disabled.
3. **Core:** navigation shell, Home, Apps, Assistant, Beeper, Notifications, Media, Status.
4. **Work:** T3 Code (with live API), Calendar, Docs, Teleprompter, Morning report.
5. **Context:** classifier scoring, conditional notifications, People, Health, Home Assistant.
6. **Ambient:** capture, delivery targets, location timeline, speaker recognition.

## 29. Open questions

- Real display resolution, Latin characters per line, refresh behaviour, and whether arbitrary bitmaps are possible (governs ARC-04 level).
- Which font reads best on the real display.
- Can our app and RayNeo's app share the Bluetooth link, or must ours replace it?
- Are crown/nod/head-up events delivered to the phone?
- Do LifeLog wake events and cached audio behave on our firmware as OpenRayneo reported? Battery cost of each capture mode.
- Does the Android Beeper provider support reactions, or is the Desktop API needed (on an always-on machine)?
- Classifier: how decisions can be inspected and overridden (see `server/notify-eval/`).
- Spotify list order (MED-20) and whether YouTube ever needs an output device.
- Final Home layout.
- How app-specific X actions are discovered (hints vs a help overlay).
- Crown-only operation scope.

## Appendix A. Glasses emoji set (25)

| Glyph | Name | | Glyph | Name |
|---|---|---|---|---|
| 😂 | tears of joy | | 👍 | thumbs up |
| 👀 | eyes | | 👎 | thumbs down |
| 😭 | loudly crying | | 🔥 | fire |
| ❤️ | heart | | 🙏 | folded hands |
| ❗ | exclamation | | 😮 | open mouth |
| ☝️ | pointing up | | 😢 | crying |
| 😅 | sweat smile | | 🎉 | party popper |
| 🥰 | smiling with hearts | | 💯 | hundred |
| 😜 | winking, tongue out | | 🤔 | thinking |
| 😁 | grinning | | 😍 | heart eyes |
| 🖕 | middle finger | | 🙄 | eye roll |
| 💀 | skull | | ✅ | check (approve) |
| | | | ❌ | cross (cancel) |

## Appendix B. T3 status mapping

| T3 Code status | Glasses tag | Section |
|---|---|---|
| input | INPUT | Needs you |
| approval | APPROVAL | Needs you |
| request (guest message waiting) | REQUEST | Needs you |
| limited (usage limit hit) | LIMITED | Needs you |
| failed | FAILED | Needs you |
| ready + unseen completion | DONE | Done |
| preparing / working | WORKING | Working |
| waiting | WAITING | Working |
| settled | SETTLED | Settled (25 most recent) |

Native layout details: header at y=8, rule y=32..33, body begins y=42, hint rule y=190, hint text y=196, bottom margin 8 px. Home retains all five badges with a large clock; Apps retains 4×2 icons. Health shows three full-size metrics at a time. Status uses six 28 px rows; memo shows one history item at a time with ▲▼. See simulator/README.md for the screen composition inventory.

## Appendix C. App shortcuts and button overrides

- **INP-19 App shortcuts.** Apps on the Apps list can offer named global actions. Settings > Shortcuts on glasses and phone assigns an action or None to hold A, hold R, hold Apps (star), hold People (heart), or tap/hold L2 (the assistant keeps the L2 tap). Nothing is assigned by default. Unknown/removed action ids resolve to None. Hold B (Home), minus (passive listening), L (microphone), R2 (media), app-specific + and in-app X/Y/D-pad are reserved.
- A hold assignment delays that button's tap until release; without an assignment, existing tap timing is unchanged. One action fires per physical press, never while locked or during consumed text entry/dictation capture. L2 retains its quiet-wait action in the assistant. Only actions marked safe for shoulder buttons are offered on L2/R (INP-18); the voice memo toggle is safe (stopping keeps the memo).
- **INP-20 Per-app button overrides.** A screen declares the buttons it takes over (`View.overrides()`: a `ButtonClaim` for the tap, the hold, or a momentary down/up) and the input engine routes them to it before any global action or assigned shortcut. Only A, R, star, heart and L2 can be claimed; B (back, hold Home) can never be overridden, and L, R2, minus keep their global meaning. The assistant claims the L2 tap (quiet wait) but not the hold. Settings > Shortcuts lists only gestures on buttons the learned controller has (no Hold Apps on the 8BitDo Micro), with plain labels (Tap L2, Hold L2) and a note naming the apps that keep that gesture. Saved gesture ids never change, so l2.hold = memo.toggle keeps working.
