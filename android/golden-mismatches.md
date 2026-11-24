Native 540×220 simulator versus Android, frozen at 2026-11-11 7:45 PM America/Chicago.

Open-source data refresh: every reference whose Android frame differed from it (51 screens, including
`home`, `beeper-list`, `joy-menu`, `youtube`, `calendar`, `health`, `dictation-*` and the call screens) was
rewritten from the native frame with `UPDATE_BITMAP_GOLDENS=<list>` (grey and 1-bit), so no reference shows the
old demo persona or places. These now match the app exactly; the simulator's own fixtures have drifted for some
screens (calendar, health, morning report) and are no longer the capture source for them.

Phone tabs and glyph coverage (2026-11-18): the Phone hint now reads "◀▶ tab" on every
tab ("X letter" on Contacts), so `phone-favorites`, `phone-recents` and `phone-contacts`
(grey and 1-bit) were recaptured with `GOLDEN_ONLY` and `UPDATE_GOLDEN_COVERAGE=1`.
Residuals are unchanged (0.0370%, 0.0370%, 0.0000%). The new `$`, `^`, `{`, `}`, `~`,
backslash, backtick and symbol glyphs (FONT5 mirrors Font5.kt) changed no other reference.

Launcher order, shortcuts and Status earbuds (2026-11-17, requirements 15.3, 16.2, 16.4):
recaptured `apps`, `apps-page-2`, `app-order`, `shortcuts-last` and `status` (grey and
1-bit) from the simulator with `GOLDEN_ONLY` and `UPDATE_GOLDEN_COVERAGE=1`. Home is on
page 1, Shortcuts names the apps that keep a gesture instead of a fixed list, and the demo
earbuds row is labelled by device ("Life P2"). Residuals: apps 0.0008%, apps-page-2
0.0017%, app-order, shortcuts-last and status 0.0000%. `shortcuts` is unchanged.

The assistant launcher refresh (2026-11-17): regenerated only the assistant icon rectangles in
`apps` and `app-order`, using the simulator capture procedure in
`android/tools/capture_goldens.py`. This retains approved
neighboring geometry across Chromium versions. Current residuals are 99 pixels
(0.0833%) for Apps and 37 pixels (0.0311%) for App order, including the unchanged
surrounding glyphs. All eight the assistant portrait/menu/call fixtures and their 1-bit
twins remain byte-identical; the menu rail still uses the portrait. Only the
affected rows below were refreshed; other rows retain their earlier measurements.

Beeper priority (BEEP-PRI, 2026-11-17): three new references, `beeper-priority`,
`beeper-quiet` (Quiet row expanded) and `beeper-menu` (X on a chat row), captured from
the simulator's `PRIORITY_CHATS` fixture with `GOLDEN_ONLY=... UPDATE_GOLDEN_COVERAGE=1`;
the app renders the same chats from `priorityGoldenData` in `CallGoldenData.kt`. The
existing `beeper-list` and `home` references are unchanged.

All 69 references assert expected/rendered dimensions and 14,850 compatibility packed bytes.
Comparison now counts unequal brightness codes quantized to 16 levels, rather than
binary lit pixels. The unchanged failure threshold is ≥0.5% of 118,800 pixels.
Maximum residual: 114 pixels (0.0960%, reaction picker), from procedural-curve
rasterization. Home and the assistant portraits/reply/call states match exactly.

PRIMARY=15, SECONDARY=10, TERTIARY=6. Every grey golden thresholds at >0 to its
preserved `golden-1bit/` reference exactly; all 69 preserved PNGs match the original
Git blobs. Capture also verifies the actual 2-level selector for every screen.
Quantization tests cover 2/4/8/16 levels, including four distinct codes at 4 levels.
No stock text golden changed. The image reader remains 43×8 full width / 33×8
beside the rail, and stock writing remains 26×5.

All four contact sheets were inspected at native pixels for selection dominance,
supporting captions, rules/tracks, inactive dots/reactions, unchanged portraits,
call identity/controls, approval content, health values and bar fills. Full call
controls and approval commands/choices remain primary. Lens brightness separation
is unmeasured. The [per-screen grey inventory](../simulator/README.md#per-screen-grey-inventory)
records each fixture and continuation state.

| Screen | Mismatch |
|---|---:|
| home | 0.0000% |
| doc-reader | 0.0101% |
| calendar-event | 0.0042% |
| scene-confirm | 0.0143% |
| dictation-listening | 0.0118% |
| dictation-transcribing | 0.0185% |
| dictation-enhancing | 0.0185% |
| dictation-preview | 0.0118% |
| dictation-pages | 0.0118% |
| apps-page-2 | 0.0017% |
| joy-menu | 0.0051% |
| beeper-actions | 0.0000% |
| beeper-message | 0.0354% |
| beeper-reply | 0.0261% |
| t3-choice | 0.0017% |
| t3-multi | 0.0017% |
| t3-reader | 0.0008% |
| t3-actions | 0.0000% |
| t3-environments | 0.0000% |
| t3-new | 0.0042% |
| t3-usage-more | 0.0076% |
| audiobooks | 0.0143% |
| spotify-playing | 0.0354% |
| media-menu | 0.0227% |
| media-speed | 0.0076% |
| media-devices | 0.0034% |
| people | 0.0194% |
| health-more | 0.0438% |
| health-breakdown | 0.0135% |
| health-breakdown-more | 0.0135% |
| home-assistant | 0.0109% |
| docs | 0.0101% |
| teleprompter | 0.0135% |
| voice-memo | 0.0118% |
| status | 0.0000% |
| settings | 0.0000% |
| app-order | 0.0000% |
| settings | 0.0673% |
| app-order | 0.0000% |
| call-incoming | 0.0067% |
| call-active | 0.0067% |
| call-held | 0.0067% |
| call-audio | 0.0067% |
| call-waiting | 0.0067% |
| call-history | 0.0067% |
| apps | 0.0008% |
| joy | 0.0000% |
| joy-thinking-reply | 0.0000% |
| joy-call-listening | 0.0000% |
| joy-call-thinking | 0.0000% |
| joy-call-speaking | 0.0000% |
| joy-call-muted | 0.0000% |
| joy-call-turns | 0.0000% |
| joy-call-worker | 0.0000% |
| joy-call-compact-listening | 0.0000% |
| joy-call-compact-speaking | 0.0000% |
| joy-call-compact-muted | 0.0000% |
| joy-call-compact-worker | 0.0000% |
| joy-call-compact-reconnecting | 0.0000% |
| youtube | 0.0202% |
| beeper-list | 0.0000% |
| beeper-thread | 0.0354% |
| beeper-priority | 0.0000% |
| beeper-quiet | 0.0000% |
| beeper-menu | 0.0000% |
| react-picker | 0.0960% |
| notifications | 0.0194% |
| reminders-empty | 0.0093% |
| reminders | 0.0093% |
| t3-root | 0.0126% |
| t3-working | 0.0168% |
| t3-approval | 0.0034% |
| t3-approval-deny | 0.0034% |
| t3-usage | 0.0076% |
| now-playing | 0.0143% |
| health | 0.0606% |
| health-detail | 0.0017% |
| morning-report | 0.0370% |
| report-continuation | 0.0370% |
| people-card | 0.0000% |
| calendar | 0.0042% |
| call-dialing | 0.0000% |
| phone-favorites | 0.0370% |
| phone-recents | 0.0370% |
| phone-contacts | 0.0000% |
| phone-numbers | 0.0000% |
| phone-search | 0.0000% |
| phone-confirm | 0.0000% |

Phone app (2026-11-17): the Phone entry at the end of the launcher adds a third
Apps page dot and an App order row, so `apps`, `apps-page-2` and `app-order` (and
their `golden-1bit` twins) were recaptured with `GOLDEN_ONLY` and
`UPDATE_GOLDEN_COVERAGE=1`. The new Phone screens (`phone-favorites`,
`phone-recents`, `phone-contacts`, `phone-numbers`, `phone-search`,
`phone-confirm`, `call-dialing`) were captured the same way from `phoneRootV`,
`phoneNumbersV`, `phoneSearchV`, `phoneConfirmV` and a placed call. The 44-pixel
residual on Favorites/Recents is the hint row's "L": the native hint highlights L
as a key while the simulator's highlight list still has L2.
| clock | 0.0000% |
| clock-timer | 0.0000% |
| clock-timer-new | 0.0000% |
| clock-stopwatch | 0.0000% |
| clock-alarms | 0.0000% |
| clock-alarm-set | 0.0000% |
| clock-ringing | 0.0000% |
| clock-ringing-alarm | 0.0000% |
| clock-settings | 0.0000% |

Clock app (requirement 7, 2026-11-17): nine new fixtures captured from the simulator
(`GOLDEN_ONLY=clock,clock-timer,clock-timer-new,clock-stopwatch,clock-alarms,clock-alarm-set,clock-ringing,clock-ringing-alarm,clock-settings`
with `UPDATE_GOLDEN_COVERAGE=1`); the Kotlin state comes from `ClockFixtures.kt` and the simulator's `clockDemo()`.
Adding Clock as the 17th app adds a third page dot to `apps` and `apps-page-2` and shortens the `app-order`
scroll thumb. Only those regions (the dot strip y >= 198, the scroll column x >= 524) were pasted into the
approved references, so neighbouring geometry is unchanged; their 1-bit twins were rebuilt from the result.
| memo-recording-hud | 0.0000% |
