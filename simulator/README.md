# Glasses + controller simulator

A hollow, interactive HTML prototype of the RayNeo iO glasses software driven by the 8BitDo Micro. Everything is fake data; nothing talks to real services. It is the visual and interaction reference for [`../docs/requirements.md`](../docs/requirements.md).

## Run locally

The page fetches its avatar pack (`avatar/default/manifest.json`), so it must be served (a `file://` URL won't work):

```sh
cd simulator
python -m http.server 8765
# open http://localhost:8765/
```

Click the controller photo or use the keyboard: arrows = D-pad, Enter = Y (select), Backspace = B (back), I = X, O = A, Space or 1 = L2 (hold to talk), U = L, E = R, 3 = R2, `-` = minus (tap: lock; hold: passive listening on/off), `=` = plus, S = star, H = heart.

## Files

| File | What it is |
|---|---|
| `index.html` | The whole simulator: rendering core, icons, emoji, screens, input engine, legend. Single file, no build step. |
| `micro.png` | Controller photo used for the clickable remote. |
| `avatar/default/` | The bundled CC0 placeholder avatar pack (`manifest.json` + `atlas.png`), byte-identical to the app's `assets/avatar/default/`. Regenerate both with `node tools/avatar/make_placeholder_pack.mjs`. |
| `tools/screen_tour.py` | Playwright script that drives the main flows and saves screenshots of the display. |

## How `index.html` is organised

1. **Display core:** a 540 × 220 canvas. Drawing retains the original coverage mask, then emits flat 4-bit green tiers: PRIMARY=15, SECONDARY=10, TERTIARY=6 (black=0). These three tokens are defined once in the display core. Supporting text uses secondary and existing structure uses tertiary; focus, actionable labels, values, icons and the avatar stay primary. Fonts: the repo's 5 × 7 bitmap font, or Google Fonts rasterised to 1-bit.
2. **Icons and emoji:** procedural 1-bit drawings (`icon()`, `EMO`), animated where useful.
3. **Rich text:** grapheme-aware wrapping with inline emoji and icons, pagination, and link summarising (`summarizeLinks`).
4. **Avatar:** frames come from the avatar pack's atlas; playback, fallbacks and OR-pooled scaling match the app. Load another pack with `?avatar=<pack folder, manifest.json or .zip URL>` or the **load avatar pack…** picker (a `.zip`, or `manifest.json` plus its atlas). See [`../docs/avatar-packs.md`](../docs/avatar-packs.md).
5. **Building blocks:** `frame` (rail layout), `frameW` (full width), `List` (inverted focus rows, sections, scroll bar, hint row), `Reader` (paged text).
6. **Screens:** one factory function per screen (`homeV`, `appsV`, `joyV`, `threadV`, `t3ThreadV`, `nowPlayingV`, …), pushed on a view stack.
7. **Input engine:** physical letters map to logical actions (`BIND`), with 450 ms holds, the R2 media layer, the lock, push-to-talk, and the D-pad remap per grip.

To re-run the screenshot tour: serve the folder as above, then `python simulator/tools/screen_tour.py` (set `CHROME` if Playwright's bundled browser isn't installed).

## Native surface and vertical grid

The design canvas and Android master are **540×220**, matching the exposed UI
surface in stock and both patched firmwares. The physical panel remains
540×280, 4-bit green. This is a native layout; glyphs are never resampled.
The selector offers **Design (540×220)** and the **Stock text (26×7)** note.
The **Greys: 16 / 2 (1-bit)** selector compares the grey master with the exact original lit-pixel mask. Brightness codes use `level * 17` green (gamma 1), matching Android’s mirror. There is no separate stretched panel preview. Stock text is rendered by Android;
its 26×7 grid and 26×5 reader body are unchanged.

| Element | Native coordinates / budget |
|---|---|
| Outer margins | 12 px horizontally; at least 8 px at right/bottom |
| Header | text y=8; rule y=32..33 |
| Body | starts y=42; ends before y=190 when a hint is present |
| Hint/footer | rule y=190; text y=196; bottom bound y=212 |
| Rail | width 104; icon 72×72 at (16,18), 6 px main strokes |
| Rail captions | start y=102; at most four 18 px lines, wrap within 100 px |
| Plain list | 34 px rows: five, or four with hint/footer |
| Two-line list | 50 px rows: three, or two with hint/footer |
| Section separator | 24 px; counts toward available list height |
| Rail reader | 402 px: 33 columns × 8 medium lines at the default font |
| Full-width reader | 516 px: 43 columns × 8 medium lines |
| Context reader | message/people heading leaves six medium lines |
| Large reader | report four lines; teleprompter five lines |

Body glyphs retain the existing s/m/l/xl sizes (bitmap scales 2/2/3/7). Home
keeps the XL clock and uses five 28 px badge rows starting at y=72, with a
shortened weather summary to the right (for example “rain 4 PM”). Apps keeps four columns by two rows.
Lists scroll their selected row into view; no data rows are deleted.

The avatar uses **128×192 at (8,10)**: an exact 2/3 reduction of 192×288 pack cells
(any cell size works; 2:3 is recommended). Both JavaScript and Kotlin OR/max-pool every
source pixel intersecting each destination footprint, thresholding source green at the
pack's `threshold` (default 100). Thin strokes survive and the output is strictly binary.
The portrait icon crops the idle frame with the pack's `portraitCrop` and uses the same pooling,
avoiding platform image-filter differences.
Reply text starts at (150,14): 378 px / 31 columns × 9 medium lines, status y=176,
pagination y=196.

## Screen composition inventory

Counts below use the default pixel font. Alternative font choices retain their
existing sizes and use line-height-derived reader budgets.

| Screen | Composition decision for 540×220 |
|---|---|
| Home | Keep XL clock, date/weather and all five badges; weather fits available width, badges use 28 px rows. |
| Apps, both pages | Keep 4×2 grid; cells 130×88, icons 48 px, labels full size; use “Prompter” to fit and retain “Voice memo”. |
| Assistant reply / thinking / listening / quiet | 128×192 proportionate portrait; 31×9 reply pages instead of 31×14, separate status and page rows. |
| Assistant menu | Same two choices, 72 px rail icon; no row lost. |
| Assistant call, listening | Portrait plus large title/timer, state at y=110, complete hint at y=196. |
| Assistant call, thinking | Same call composition with thinking animation; no status overlap. |
| Assistant call, speaking | Same call composition with speaking animation; reply states paginate three lines at a time. |
| Assistant call, muted | Same call composition; full microphone-muted state and unmute hint retained. |
| Assistant call, voice turns | Turn-based label fits between title and timer; three reply lines per page. |
| Morning report | Four large lines instead of six; body continuation counter above the card dots. |
| Dictation listening | Full-size listening/timer text; narrower waveform spacing, waveform raised above footer. |
| Dictation transcribing / enhancing | Same large text and spinner, kept clear of the footer. |
| Dictation preview | Six medium lines per page; page count above concise send/discard/add hint. |
| Beeper chat list | Three two-line rows instead of five; same selection bar and scrolling. |
| Beeper thread | Shorter message viewport; retain full-size previews/reactions and scroll focused message; concise action hint. |
| Message actions | All three plain choices still fit. |
| Message reader | Six medium lines after context heading instead of eleven; counter above action hint. |
| Message reply | Six quote lines; shorter “Hold L2 to reply” instruction, counter above it. |
| Reaction picker | Five visible emoji instead of seven; keep 44 px emoji and 64 px cells; move carousel upward. |
| Notifications | Two two-line rows instead of four with hint; shorten footer to “Y open · X clear · + armed”. |
| Armed reminders / empty | Two two-line rows with hint; expiry and condition remain in their row, scrolling retained. |
| T3 root | Two two-line threads plus section header; fit tag-offset subtitle to actual remaining width. |
| T3 working | Keep current step, timer and recent work; fewer recent rows, full history still opens with X. |
| T3 approval | Command/reason kept above one scrolling choice at a time; reserve status footer. |
| T3 choice / multi | Two question lines instead of three; paged question and scrolling options above reserved status footer. |
| T3 reader / done / limited / failed / settled detail | Eight full-width lines instead of twelve; status/source/page footer retained. |
| T3 history | Shorter scrolling list; entry readers use eight lines per page. |
| T3 usage | Two windows per page instead of three; continuation pages preserve provider dots and X reset access. |
| T3 resets / environments | Three two-line rows; labels/subtitles remain full size and scroll. |
| T3 settled list | Three two-line rows; all 25 items remain reachable. |
| New thread / first prompt | Five plain project rows; prompt reader uses eight lines per page. |
| Now playing | Keep two large title lines; reduce gaps around progress/time/volume and keep pause status in rail. |
| Media menu | Four choices plus section still fit; unchanged text sizes. |
| Audiobooks | Three two-line books instead of five; progress remains right-aligned. |
| Spotify | Three mixed rows plus section; retain the current-playback demo row (captured with Spotify active for Android parity). |
| Media devices / speed | Five plain rows; current selection text retained. |
| YouTube | Two two-line videos with hint instead of four; title/channel/duration unchanged. |
| Calendar | Two events plus day sections; scroll to remaining days, event reader uses eight lines. |
| People list | Fewer recent people after quick-view row; same scrolling and selection. |
| Person card / nearby | Six full-width body lines instead of eleven; people-cycle and body-page counters remain separate. |
| Health overview | Three 50 px metrics at a time instead of squeezing all five; add 1/2 and 2/2 counters. |
| Health history | 80 px plot instead of 110; retain low/high/average and range/breakdown hint. |
| Health breakdown | Four rows per page; add continuation pages so all eight readiness components remain accessible. |
| Home Assistant | Three scenes plus sections fit; alarms/printers scroll, detail readers paginate. |
| Scene confirmation | Both full-size choices retained. |
| Docs list / document | Five plain rows; document reader recomputes eight-line pages at 33 columns. |
| Teleprompter | Five large lines instead of eight; same line-based scrolling and accurate total. |
| Voice memo | Large timer instead of XL; one history item instead of four, with ▲▼ and history count. |
| Status | Six 28 px rows instead of 34 px; rebalance Wi-Fi/cell columns, preserve all bars and labels. |
| Settings | Two-line explanatory rows match current Android UI; scroll remaining settings at full text size. |
| App order | Five plain rows instead of seven; moving item stays selected and in view. |
| Phone incoming / waiting | Keep two large name lines plus three body lines; caption capped, complete answer/decline/hide hint. |
| Phone active / held | Keep large name and body; put duration ahead of carrier caption, shorten hint while B still works. |
| Call audio | Four-line long-device pages instead of five; device index/page count/hint have separate rows. |
| Missed call history | Two two-line rows with hint; detail readers use the shared shorter page size. |
| Clock hub | Three two-line rows (Timer, Stopwatch, Alarms) plus Settings below the fold; a ringing row is added on top while something rings. |
| Timer | One page per timer plus a New page; label, XL remaining time, end time/total, 12 px progress bar, hint row. |
| Stopwatch | XL elapsed time with tenths; three most recent laps (lap, split, total) at small size; hint row. |
| Alarms | GLASSES section (two-line alarm rows, New alarm), PHONE section (phone clock, Home Assistant alarm) scrolls. |
| Alarm set | XL time with AM/PM beside it as on Home; a 4 px underline marks the focused field; repeat row below. |
| Clock ringing | Full width (no rail): 48 px alarm icon, label and detail, XL overrun or alarm time; full-brightness controls. |
| Clock settings | Six plain rows (phone clock import/export switches, ring lengths, snooze). |

The Home Assistant screen's three printers are demo fixtures.

## Golden regeneration and visual review

Follow `android/README.md`: run `android/tools/capture_goldens.py`, then
`android/tools/raster_reference.py` and `android/tools/contact_sheet.py`.
There are 69 bitmap references, including continuation pages, call states,
dictation phases and the previously unpictured screen families. Contact sheets
are written under `android/app/build/`, never committed. Text goldens are unchanged.

`python simulator/tools/validate_display.py` checks native dimensions, identity
hashes, flat tiers, exact 1-bit fallback, Kotlin token parity, CSS aspect ratio, both display modes, approval scrolling,
usage continuation, health breakdown continuation and all 150 portrait frames.
`--capture` also runs the complete standard bitmap capture workflow.

Live assistant calls expose X mute/unmute. The turn-based fallback exposes X info,
because the current DataStore interface does not support muting that mode.

## Greyscale review

Keep the three lit tiers at least four codes apart, none below 5. There are no
new shapes, gradients, output antialiasing, dithering or shadows. Selection bars
remain full brightness with black text; unselected subtitles/ages/tags, rail and
header context, counters and section captions are secondary. Existing rules,
bar outlines/ticks, dotted tracks, inactive dots, unchecked outlines, non-focused
reactions and unavailable rows are tertiary. Hint words are secondary with key
glyphs primary; complete call controls, caller names and approval content stay
primary. Existing empty bar interiors stay black to preserve 1-bit geometry.

Capture checks every grey frame against its preserved `golden-1bit/` reference,
and exercises the actual 2-level selector. All four contact sheets were inspected
for focused-row contrast, caption consistency, unchanged portraits and glyphs,
call/approval legibility, inactive reactions/dots, list scrolling and bar fills.
No optical distinction between nearby panel codes is assumed.

## Per-screen grey inventory

The entries also describe continuation/live states beyond the frozen fixture.
Primary content, focused rows and hint key glyphs are retained throughout.

| Screen / golden | Grey use |
|---|---|
| app-order | Secondary rail/header context; tertiary header rule and dotted scroll track. |
| apps | Tertiary inactive page dot. |
| apps-page-2 | Tertiary inactive page dot. |
| audiobooks | Secondary rail/header context, unselected author/chapter/progress; tertiary rule. |
| beeper-actions | Secondary header context; tertiary header rule. |
| beeper-list | Secondary rail/header context, unselected previews/unread counts; tertiary rule/scroll track. |
| beeper-message | Secondary header/message context and hint words; tertiary rules. |
| beeper-reply | Secondary header context, hint words and page counters; tertiary rules. |
| beeper-thread | Secondary rail/header context, reply context and hint words; tertiary rules. |
| calendar | Secondary rail/header context, day labels and unselected event details; tertiary rules/scroll track. |
| calendar-event | Secondary header clock and page counters; tertiary header rule. |
| call-active | Secondary carrier/duration rail captions; tertiary rules; complete call controls primary. |
| call-audio | Secondary rail/header context, device metadata/counters and hint words; tertiary rules. |
| call-held | Secondary carrier/duration rail captions; tertiary rules; complete call controls primary. |
| call-history | Secondary rail/header context, unselected details and hint words; tertiary rules/scroll track. |
| call-incoming | Secondary carrier caption; tertiary rules; caller identity and answer/decline controls primary. |
| call-waiting | Secondary carrier caption; tertiary rules; caller identity and answer/decline controls primary. |
| dictation-enhancing | Tertiary header rule. |
| dictation-listening | Secondary stop-hint words; tertiary header rule. |
| dictation-pages | Secondary hint words and page counter; tertiary header/footer rules. |
| dictation-preview | Secondary hint words; tertiary header/footer rules. |
| dictation-transcribing | Tertiary header rule. |
| doc-reader | Secondary header clock and page counter; tertiary header rule. |
| docs | Secondary rail count/header clock; tertiary header rule. |
| health | Secondary rail/header context and page counter; tertiary header rule. |
| health-breakdown | Secondary rail/header context, section caption and navigation words/counter; tertiary rules/bar outlines. |
| health-breakdown-more | Secondary rail/header context, section caption and navigation words/counter; tertiary rules/bar outlines. |
| health-detail | Secondary rail/header context, low/high/average captions and navigation words; tertiary header rule. |
| health-more | Secondary rail/header context and page counter; tertiary header rule. |
| home | Secondary date and weather summary. |
| home-assistant | Secondary header context, section labels and unselected metadata; tertiary rules/scroll track. |
| joy | Secondary page counter when needed; this single-page golden remains fully primary. |
| joy-call-listening | Tertiary hint rule; portrait, state, timer and complete call controls primary. |
| joy-call-muted | Tertiary hint rule; portrait, state, timer and complete call controls primary. |
| joy-call-speaking | Secondary continuation counter when needed; tertiary hint rule; complete call controls primary. |
| joy-call-thinking | Tertiary hint rule; portrait, state, timer and complete call controls primary. |
| joy-call-turns | Secondary turn-based caption; tertiary hint rule; complete call controls primary. |
| joy-menu | Secondary header clock; tertiary header rule. |
| joy-thinking-reply | Secondary thinking/prompt status; portrait and reply remain primary. |
| media-devices | Secondary rail/header context and unselected current tag; tertiary header rule. |
| media-menu | Secondary header context, section label and unselected metadata; tertiary rules. |
| media-speed | Secondary header context and unselected current tag; tertiary header rule. |
| morning-report | Secondary rail/header context; tertiary header rule and inactive card dots. |
| notifications | Secondary rail/header context, unselected previews/ages and hint words; tertiary rules/scroll track. |
| now-playing | Secondary rail/header context, subtitle/times and hint words; tertiary rule/progress outline. |
| people | Secondary rail/header context, section label and unselected locations/ages; tertiary rules/scroll track. |
| people-card | Secondary header age, location, cycle/page counters and navigation words; tertiary header rule. |
| react-picker | Secondary header context, reaction caption/page counter; tertiary header rule/non-focused carousel items. |
| reminders | Secondary header context, unselected conditions/expiry and hint words; tertiary rules/scroll track. |
| reminders-empty | Secondary header clock and hint words; tertiary header/footer rules. |
| report-continuation | Secondary rail/header context and page counter; tertiary header rule/inactive card dots. |
| scene-confirm | Secondary header clock; tertiary header rule; action labels primary. |
| settings | Secondary header context and unselected supporting text; tertiary rule/unavailable rows. |
| spotify-playing | Secondary rail/header context, unselected subtitles/section labels; tertiary rules/scroll track. |
| status | Secondary rail/header context; tertiary header rule/bar outlines; fills and values primary. |
| t3-approval | Secondary header context, page/provider/status counters; tertiary rules/scroll track; commands/reasons/choices primary. |
| t3-approval-deny | Secondary header context, page/provider/status counters; tertiary rules/scroll track; commands/reasons/choices primary. |
| t3-choice | Secondary header/project/provider/status context and counters; tertiary rules/scroll track. |
| t3-environments | Secondary header context, unselected OS/state details; tertiary rule. |
| t3-multi | Secondary header/project/provider/status context and counters; tertiary rules/unchecked outlines/scroll track. |
| t3-new | Secondary header clock; tertiary header rule/scroll track. |
| t3-reader | Secondary header/project/provider/status context and page counter; tertiary header rule. |
| t3-root | Secondary rail/header context, section labels, unselected subtitles/ages/tags; tertiary rules/scroll track. |
| t3-usage | Secondary rail/header context, reset/pace captions and counters/hint words; tertiary rule/bar outlines/ticks/inactive dots. |
| t3-usage-more | Secondary rail/header context, reset/pace captions and counters/hint words; tertiary rule/bar outlines/ticks/inactive dots. |
| t3-working | Secondary header context, NOW/RECENT labels, quiet-state captions, recent durations/provider/status; tertiary rules. |
| teleprompter | Secondary rail/header context and line counter; tertiary header rule. |
| voice-memo | Secondary rail/header context, hint words, TODAY label, history times/counter; tertiary rules. |
| youtube | Secondary header context, unselected channel/duration and hint words; tertiary rules/scroll track. |
| clock, clock-alarms, clock-settings | Secondary rail/header context, unselected subtitles/values and section labels; tertiary rules/scroll track. |
| clock-timer, clock-timer-new, clock-stopwatch, clock-alarm-set | Secondary rail/header context, state, end time, lap names/totals and hint words; tertiary rules/bar outline. |
| clock-ringing, clock-ringing-alarm | Secondary header clock and detail line; hint row stays primary (safety controls, like calls). |
