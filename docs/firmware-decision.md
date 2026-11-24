# Firmware options and risks

Research date: **2026-11-12**; firmware versions move fast, so recheck everything here before acting on it. Recommendation: keep the shipped firmware for the first week.
For custom raster graphics, prefer the **Turbo-IO TVP2 family**, conditional on
the exact stock version, an available matching installer, and a successful
LifeLog/audio acceptance test. It has the strongest public history of the
candidates, but none has a broad independent hardware validation programme.
Do not flash just because this app now implements a raster sender.

The best-supported raster candidate is the **1.0.5.5 TVP2 release of October 23**.
The **1.0.6.11 TMIG2-VIDEO-R1 release of November 10** is newer and has weaker
hardware evidence. A unit shipping 1.0.4.12 needs a different decision: TLC1
has a useful coexistence report, but is not TVP2. Do not put the wrong-version
AP on any unit or assume that a downgrade is available.

## What evidence this decision uses

Local clones and OTA files supplied with this task were read as untrusted data;
no script, build, updater, downloaded app, or firmware from them was run.
Code reading, manifest inspection, byte/string reads and upstream reports are
**not a hardware test of this app**. Source pins:

| Source | Pin and date | Evidence used |
| --- | --- | --- |
| [Turbo1123/Turbo-IO](https://github.com/Turbo1123/Turbo-IO/tree/28a0fef) | `28a0fef0a42773f38222fcd0db2e0f2852ed4257`, Nov 10 | Release readmes, audits, wire encoders/receivers, idle assembly, licences |
| [Glassly CFW](https://github.com/tetramo-labs/glassly-rayneo-cfw/tree/451dd88) | `451dd88064e6f7544c1d1adf0e5ccd7093be5576`, Nov 4 | `docs/{PROTOCOL,HOOKS,RAW-LINK-PLAN,SAFETY,V11,V12,V14}.md`, `patches/` |
| [Spare io](https://github.com/owenc1122/spare-io/tree/c7666b8) | `c7666b8f5ac21f1043667b80a89fbac548151bec`, Nov 2 | README, licence, SDK and Workshop guide; runtime/link/updater absent |
| OpenRayneo | `7f2fc0d`, as in [protocol notes](rayneo-protocol.md) | Wearer-reported grids, text/video tests, guest link, LifeLog messages |
| Stock OTAs | Local `fw-research/stock-1.0.5.5` and `stock-1.0.6.11` | Separate audio/VAD payloads, resident app/icon strings and manifests |

The supplied `stock-1.0.4.12` directory is empty in this checkout's research
snapshot. Older stock addresses below are from Glassly's committed symbol
table and Turbo's audited relocation, not a fresh local read of that older AP.
The two supplied `ap-*.txt` dumps are also empty; conclusions do not rely on
them. The unpacked newer APs do contain the notification app identifiers,
weather resource names, and widget command strings listed below.

Public GitHub metadata checked November 12 showed **346 stars for Turbo** and
**0 for Glassly**. Popularity indicates community attention, not safety. Glassly's
33 commits are credited to `fossephate`/`fosse` (apparently one author; Git author
names cannot prove identities). Turbo's independent [issue #41](https://github.com/Turbo1123/Turbo-IO/issues/41)
(Sept 29) reports difficulty returning to stock; the author links a stock
archive, without a demonstrated brick recovery. [Issue #47](https://github.com/Turbo1123/Turbo-IO/issues/47)
(Oct 19) reports newer glasses firmware not flashable from an Android installer.
Those are practical limits, not evidence that all newer packages fail.

## The design is now native 540×220

The physical composition buffers describe **540×280 pixels per eye, 4-bit
monochrome green**. The normal UI and native raster window are **540×220**.
Stock LVGL v9 uses a **544×220 RGB565** drawing buffer (544 is storage/alignment,
not 544 visible columns), converted to 4-bit grey. Glassly's
`patches/disp.c` records `G4_ROWS=280`, 270 pixel bytes for 540 pixels, and the
stock 220-row update window; `patches/pixels.h` packs two L8 samples into one
byte. Turbo's `official-addon/tar2-display/player.h` fixes the native output at
540×220 and maximum stride 544. These are static firmware facts; actual
visible clipping, optical alignment and perceived grey steps need the unit.

The simulator and Android master are **540×220, 4-bit green**, laid out natively
at the exposed UI size. Three restrained lit tiers carry the hierarchy:
PRIMARY=15, SECONDARY=10, TERTIARY=6 (black=0). The named tokens live once in
each renderer. Titles/body/values/icons/focus remain full brightness; supporting
captions/context step down to secondary and existing rules/tracks/inactive marks
to tertiary. Supported raster targets receive every master pixel 1:1 without
geometric resampling. The 2/4/8/16-level setting quantizes these encoded codes;
four levels retain all three lit tiers and two levels map every positive code to
ON, exactly recovering the previous 1-bit design. Four-bit grey is the maximum
claimed here, not RGB colour. The phone mirror uses the final wire-quantized
image, mapping encoded brightness to green with gamma 1. Actual optical
separation of the steps is still unreported; no essential action relies on grey.

`RasterFit` still supports **FIT** (preserve proportions and centre) and
**STRETCH** (fill the target) for other source/target sizes, with linear coverage
averaging followed by gamma encoding when resampling is required. Both modes
have an explicit identity path when sizes match. The setup screen hides the fit
choice for the current matching 540×220 surfaces; grey selection remains available.

You **cannot derive panel resolution from a character count alone**. Dividing
the known UI bounds gives only these upper-bound cell budgets:

| Grid | UI width / columns | UI height / rows |
| --- | --- | --- |
| Font 1, 26×7 | 20.77 px | 31.43 px |
| Font 2, app fallback 20×5 | 27 px | 44 px |

These are not measured font advances or ink bounding boxes: margins, leading
and page layout occupy some of that space. OpenRayneo also reports **20×6** in
some font-2 layout experiments (`docs/text-layout.md`); our 20×5 is a chosen
fallback, not proof that the panel can hold only five rows. Count columns/rows
and measure actual glyph advance with a ruler/photo during week one. Font-2
`█` matched U+3000 spacing in the upstream four-cell ruler; half blocks,
quadrants and arrows need their own ruler. Halves offer 26×14 samples;
quadrants offer **52×14**, if both glyph shape and advance are usable.

## What stock can actually draw

Stock accepts caption/prompt text, teleprompter text, notifications, suggestion
cards, todos and weather/dashboard data. No general upload-and-draw arbitrary
bitmap command was found in these sources. Confidence is high, approximately
90%, not a proof that no hidden command exists. Existing text output stays usable.

The earlier claim that stock has no icons was wrong:

* Notifications, business **0x15 type2**, select resident icons with `appId`.
  The newer AP contains `com.spotify.music`, `com.whatsapp`,
  `org.telegram.messenger`, `com.google.android.youtube`, `com.google.android.gm`
  beside notification resource names. There are category fallbacks (roughly
  33); experiment **0..33 inclusive**, without promising every ID is distinct.
  In local stock 1.0.6.11 AP, Spotify is at file offset `0x154c11`, WhatsApp
  `0x154ab3`, Telegram `0x154af7`, YouTube `0x154c5b`, Gmail `0x1535c2`.
* `current_weather_update`, business **0x0F type18**, takes a numeric icon.
  Use the QWeather-style namespace: 100 sunny, 101 cloudy, 104 overcast,
  150 clear night, 151 cloudy night, 300 shower, 302 thundershower,
  305 light rain, 310 storm, 400 light snow, 501 fog. These names match
  [QWeather's catalogue](https://icons.qweather.com/en/icons/); the firmware
  may group several codes into one resident image. Only the 100→150 visible
  change was wearer-confirmed upstream (`docs/schedule-weather-protocol.md`).
  **2001 is an extra probe, not an established weather condition code here.**
* Todo/suggestion cards use firmware layouts and images. They do not accept
  our icon bitmaps. Reconnecting the official app may overwrite weather/todos.
* An A2UI v0.9 runtime has Row, Column, Card, Divider, Text, Icon, BarChart,
  LineChart (also List in the older symbol catalogue), and 13 named icons:
  sunny, rainy_heavy, foggy, weather_snowy, thunderstorm, moon_stars,
  partly_cloudy_night, rainy, location_on, directions_walk, accessibility_new,
  trending_up, trending_down. This is structured UI, not an arbitrary raster API.
  See Turbo `official-addon/A2UIProtocol.m` and Glassly `patches/symbols.json`.
  Phone `widget_install` is reported disabled from 1.0.5.5; command strings
  remaining in a later AP do not establish reachability. Expect the older
  1.0.3.15–1.0.4.x path to be the useful experiment. This port sends a minimal
  ≤509-byte frame and logs type19/code0 if received; visible installation is
  still required to call it a success. No install success is asserted here.
* Full block `█` is wearer-confirmed. `▀▄` remain experimental; quadrants,
  box drawing and symbols are unknown. Braille was reported unusable
  (`docs/{tui-navigation,text-video}.md`), so it is a probe, not a fallback promise.

## Firmware options and actual maturity

**Stock with our companion** has the least added firmware risk and the strongest
LifeLog baseline. Guest BLE auth/RFCOMM, caption grids and LifeLog wake/rounds
are reported by OpenRayneo. It cannot show the owner's original raster artwork.
It is the baseline against which every custom build must be tested.

**Turbo-IO** has the broadest release/audit history: reproducible builds, fixed
hashes, patch allowlists, ARM/emulator tests, host protocol tests and several
dated user reports. Reports include TLC1 on Sept 30, TMIG1 on an iPhone 17 Pro
Max on Oct 14, and TVP2 on the same phone model on Oct 23. They are mostly
anonymous single-user acceptance reports maintained by the project, not a
named independent lab or a many-device matrix. TVP2's report describes 3,470
decoded source frames and **186 fully acknowledged glasses frames**; those
counts do not establish 10 optical fps or long-term audio coexistence.
Sources: `README.md` history; `firmware-research/strix-1.0.5.5-tvp2/{README.md,verification.json}`
(last change `40d92d8`, Oct 23); `docs/TVP2_TAR2.md`.

Turbo has multiple incompatible graphics systems: older Turbo Display
512×128, native navigation/app surfaces 540×180, TVP1 270×110 enlarged 2×,
and **TVP2 native 540×220**, requested 2/4/8/16 grey levels, four CRC-checked
strips, atomic commit. Our app implements the last one. A 10 fps cap is a
target; payload complexity and Bluetooth throughput determine actual speed.
The 1.0.6.11 VIDEO-R1 release fixes an ABI change that discarded the HELLO;
its base TMIG2 was reportedly flashed, but **R1 continuous playback/pause/recovery
had no complete hardware acceptance at the pinned commit**. Do not transfer
the 1.0.5.5 acceptance report to R1.

Turbo patches **nuttx_ap.bin**, packaged in the normal complete Bluetooth OTA.
It is not a replacement VAD algorithm or full firmware source tree. Exact
stock versions, stock hashes, patch offsets, complete ZIP hashes and matching
phone installers matter. A modified official iOS host was used for published
flashes; public source does not equal a ready signed installer for our Android
phone. Our sender can implement the documented protocol without AirPlay or the
modified companion, but its **guest link passing the firmware's bonded/connection
gate is unverified**. The app never performs OTA writes.

**Glassly** is a smaller, apparently single-author project with useful explicit
failure reports. Revision 3 was wearer-verified on Oct 9 after actual flashes
exposed a wrong registry lookup, double free, pair-state gate and decimal/hex
mode mistakes. Later hardware investigations recorded UI starvation and display
timing. Revision 11 fixes cache safety and rendering behaviour, but its own
`docs/V11.md` says that revision had **not** been flashed/measured. The v12
heading mentions hardware validation in README, while `docs/V12.md` explicitly
says it is pending. Trust the specific validation statement. v14 (Nov 4)
hides the stock launcher while linked and removes local long-press close;
`docs/V14.md` also says hardware acceptance is pending. Do not choose latest
merely because its number is largest.

Glassly pins **Strix 1.0.4.12 only**. Revision ≥7 supports native **540×220**
full and box-RLE raster, plus cached vector scenes/assets; later code preserves
4-bit grey to the display buffers. Older README text still says 540×180 and
1-bit, so that blanket claim was wrong for direct raster. IDENT reports the
dimensions/capabilities. It uses BLE business **0xF0**, PAGE_OPEN/CLOSE,
keepalive, per-eye ACKs and a 120-second lease. The guest pair-state gate was
explicitly removed, making a standalone companion plausible; this Android
port still needs a real guest-link test. Only raster is implemented here,
not the vector/object-cache API. A malformed box can partly paint; after a
NACK the baseline is discarded and reopening sends a keyframe. Large split
box updates can tear.

**Spare io / Spare Nova** is a replacement system rather than a narrowly
auditable display patch. The open repo supplies launcher UI and a Workshop
SDK, not the firmware runtime, phone transport or updater. Its guide permits
PNG images with even width, **≤128×128**, and bounded scripted apps. An app
rollback command in Workshop means re-signing an old app with a higher build
number; it is **not stock firmware rollback**. The original supplied claim
“firmware 2.1.39, iPhone only” cannot be retained: the live
[project page](https://spare.cool/nova/) checked Nov 12 advertises Android
2.0.39, iPhone support, and glasses firmware stable 2.1.20 / preview 2.1.21.
Version numbers must be rechecked before any decision. No independently
auditable LifeLog arbitration, native framebuffer link, or verified stock
recovery was found. There is not enough public evidence to prefer this for
this project's own Android app and its speech-detection (VAD) requirement. No Spare code is included here.

## What happens to VAD and LifeLog?

The newer stock OTA manifests have separate `pil_algo_vad_demo.dll`,
`pil_algo_wakeup_dll_nand.dll`, `nuttx_bth.bin`, and `nuttx_audio.bin` payloads.
Turbo TVP2 audit says only AP changes (14 other payloads unchanged on 1.0.5.5;
15 on 1.0.6.11); TLC1 changes AP only, with 13 others unchanged. Glassly's
patch/repack process changes AP and its manifest row. **Keeping algorithm
bytes unchanged does not prove recording still starts or stays live.** AP
owns the switches, page arbitration and audio sessions that use those algorithms.

The critical stock function is misspelled **`LauncherAPI::isBussinessIdle`**
(`0x106d334c` in 1.0.4.12, Thumb symbol +1). In its ordinary path, it tests
front-page/launcher state and competing assistant/business state, then calls
**isLifeLogEnabled**; if the **switch is on**, it returns busy even between
speech rounds. It is not just “mic currently recording”. The function also
has special early returns and missing-service fallbacks: it is not a universal
mutex or a guarantee that all audio tasks can coexist. The committed relocated
assembly preserves those paths; frontend callers separately check pairing,
folded temples and ownership. See Turbo
`firmware-research/strix-1.0.4.12/lifelog-coexist/{README.md,build.py,coexist-arm.json}`
and Glassly `patches/symbols.json`.

**Exactly what TLC1 changes:** adds a relocated `tlc_display_idle` copy and
redirects the six extension home/safe-home gates (navigation, music, reader,
focus, Didi and small apps). It skips the LifeLog-enabled rejection at
`0x106d336e`; the original function stays intact. It does not disarm LifeLog,
replace VAD, take over audio, or bypass the remaining foreground/assistant
checks. Increment over TGR1 is 192 AP bytes, no new thread/timer/heap. A wearer
confirmed entering extensions with LifeLog enabled; sustained speech capture,
power, repeated wake and long-term coexistence were explicitly **not accepted**.
The published 480 differential ARM cases are model-based tests, not 480 units.

**Correction to “coexistence only exists in TLC1”:** the later TVP2 and R1
source trees include `src/context-english-v1/coexist.S`, whose
**`ce_extension_idle` also replaces the LifeLog test with zero**. Their
`camera-v1/camera_native.c` home gate (used by `video-player-v1/native.inc`)
calls this copy. Thus there is static evidence the newer raster path permits
an armed LifeLog switch. It is not evidence of glitch-free parallel recording
or wake handling while video owns the page. This is the reason to prefer the
Turbo family, and the most important hardware go/no-go test.

**Glassly** PAGE_OPEN still calls the original stock busy gate (`patches/page.c`),
so an armed LifeLog switch is expected to refuse it with PAGE reason1. QUERY
works while busy. No TLC1-style LifeLog exception was found there. Revision ≥10
advertises a different audio path (IDENT bit5): assistant business **0x0D
type58** rc1/rc2 opens/closes a **16 kHz mono Opus** remote stream, inbound
type59 field4, until a call/SCO/voice session/recording takes the recorder.
That can support continuous capture + phone speech detection, but is not
on-device LifeLog wake and needs its own real-device validation.

## A staged plan, with exact-version choices

**Day one: stay stock.** Record OsVersion (0x09 type1), phone/Android version,
visible row count and battery. **DECLINE OTA updates**, disable automatic OTA
where offered, and do not accept the official app's upgrade prompt. Run the
[week-one experiments](../android/GLASSES.md#display-experiments-in-decision-order)
in order. Save the app's shareable log. Establish whether LifeLog wake works
with the grid armed and whether useful blocks/icons already meet the need.

| Shipped version | Specific candidate / decision |
| --- | --- |
| 1.0.5.5 | Preferred raster trial: `StrixOS-1.0.5.5-TurboIO-TVP2-CANDIDATE-NOT-APPROVED.zip`, [TVP2 Oct 23 release](https://github.com/Turbo1123/Turbo-IO/releases/tag/tvp2-tar2-20261023), SHA256 `154f0f8e68a69b8fb93f869e423081ee43969bf09c5174e8dbeac192c1047c11`. Preserve LifeLog if acceptance passes. |
| 1.0.6.11 | Candidate only after stricter acceptance: `TMIG2-VIDEO-R1-1.0.6.11-lab.zip`, [Nov 10 release](https://github.com/Turbo1123/Turbo-IO/releases/tag/strix-10611-tmig2-video-r1), SHA256 `c03321061605aba186b44e7b90d86d3b13a2f75e081687fc684680e2800a4e8a`. Do not flash older TVP2 or basic TMIG2 expecting the R1 fix. Stay stock if no matching installer or if LifeLog is indispensable before validation. |
| 1.0.4.12 | For the LifeLog priority, stay stock first. A coexistence-only pilot is **TLC1-01**, [Sept 30 release](https://github.com/Turbo1123/Turbo-IO/releases/tag/firmware-strix-1.0.4.12-tlc1), SHA256 `5e2b85acb9cd25e0b245838e665e39d291eae4aae640308f126718eb3185e519`; this app's TVP2 sender does not drive its older graphics system. For native raster, defer to an explicitly supported 1.0.5.5 upgrade + TVP2 path, if available, or a separately validated TVP2 backport. Do not claim TLC1 provides native TVP2. |
| 1.0.4.12, willing to replace LifeLog wake | Alternative developer trial: Glassly **revision11 / `2073aeb`**, includes raster and remote-mic capability without v14's launcher suppression. Use the exact revision's generated pins and matching OTA; it remains host-tested, not a validated flash recommendation. Continuous audio/phone detection must be ready first. Do not build latest and label it v11. |
| Anything else | **No-go** until a version-specific audited build and installer exist. Do not downgrade, patch arbitrary offsets, change version strings or defeat hash gates. |

TLC1 and TVP2 packages are complete OTAs despite “only AP changed”. They can
still write other partitions. Never flash a standalone `nuttx_ap.bin` as if
it were the OTA ZIP. A stock archive is useful only while a functioning OTA
path remains; there is **no verified hardware recovery** if Bluetooth/boot is
lost. Do not plan around an assumed bootloader button combination.

Before any flash, all of these must be true:

1. Exact model/stock version, original archive and target full ZIP hashes are
   recorded; the build's manifest/patch audit matches. Preserve personal data
   and obtain the matching stock package. No unexplained extra payload change.
2. The matching installer is actually available, built/signed and can read back
   the downloaded candidate and its manifest. Published iOS instructions are
   not an Android installer. Old GUARD-07/TGR1 gates must not be bypassed to
   accept TLC1/TVP2/R1. This Android companion intentionally cannot flash.
3. Charge ≥50%, keep phone/glasses beside each other and powered, unfold,
   return to the home page and stop **all recording including LifeLog** for OTA.
   Validate the installer’s one-shot authorization/readback process before
   installation. Stale protection/cache must be cleared only by its documented
   completed-upgrade procedure, not during an unknown/in-progress install.
4. Accept that a failed flash may permanently lose the glasses and their audio
   functions. With a single indispensable pair and no recovery, defer the flash.
5. Have a way to measure successful audio rounds and to fall back to a validated
   capture path; a visible raster image alone is insufficient acceptance.

After flashing, first test normal crown/wake/display/recording and reconnect;
then raster with LifeLog **off**, then arm LifeLog and repeat opening the page.
Speak, wait through silence, trigger at least ten wake/start/audio/end rounds,
check VAD/VPU metadata and transcripts, and repeat after a power cycle. Run
1/10/30-minute audio + raster trials; record dropped rounds, actual packet
cadence, UI latency, battery and temperature. Check calls/assistant preemption
and restore recording afterwards. If any indispensable audio check fails,
stop the custom display trial; return to stock only through a still-working,
validated OTA recovery route. Do not keep experimenting on a failing recorder.

## How audio keeps working in each decision

* **Stock:** keep on-device LifeLog wake and A1/A2/A3/A5/A8 round handling when
  coexistence with the chosen stock page is proved. Current push-to-talk keeps
  the existing caption mono path and LifeLog fallback; setup never arms
  background recording merely by connecting.
* **Turbo with a passing gate and audio test:** keep the unchanged VAD/wake
  payloads and LifeLog rounds while the raster page owns the display. Passing
  the gate alone does not settle audio resource preemption.
* **Glassly or any raster build whose gate/audio test fails with LifeLog armed:**
  LifeLog wake cannot be promised alongside that page. The alternative is a
  proven continuous mic stream plus **phone-side speech detection** (the app's
  ambient capture has this as its "continuous" / "auto" source). Glassly bit5 describes a candidate remote
  stream; other Turbo continuous paths require separate verification. Do not
  claim this raster branch implements that fallback. If the fallback cannot
  coexist, retain stock or text display until it can.
* Starting this app's existing **caption mic** deliberately closes raster first;
  stop capture to return to raster. This prevents two app-owned display pages
  being opened simultaneously and preserves the existing audio code's role.

## Licences and what remains unknown

For a personal non-commercial project, Turbo's **PolyForm Noncommercial 1.0.0**
allows the stated personal/research use; it is not unrestricted open-source
licensing or permission to commercialize a derivative. Its AirPlay receiver
SDK is a separate **GPL-3.0** component; distributing an incorporated derivative
requires its source/licence obligations. No AirPlay receiver is needed by this
app. Glassly's original patches are **MIT** (retain notice when incorporating
code); RayNeo still owns the patched stock image. Spare's open UI is PolyForm
Noncommercial; its missing runtime is not licensed by that UI grant. These
licences do not grant rights over all stock firmware/resources. Consult the
actual pinned licences before distributing firmware or a commercial product.
This app contains original raster interoperability code, no Turbo/Glassly
implementation or firmware binaries; protocol sources are recorded in
[android/NOTICE](../android/NOTICE) and [raster-wire.md](raster-wire.md).

Chip naming is not settled by these sources: the emulator calls it **BES1700**,
while Turbo/Glassly describe **BES2800**. Stock symbol/platform names and shared
IP can differ from a marketed SoC. Treat it as a Bestechnic NuttX platform;
do not choose patches or assert core count from that naming disagreement.

Without hardware we have not verified: guest authentication against either
custom build's gating; detection when the wearer starts on a busy page;
actual GATT/RFCOMM throughput and ACK routing; grey visibility, margins and
optical FPS; every glyph advance and prefix behaviour; notification category
and weather mappings; reachable A2UI installation; TVP2 pause/exit/session
renewal; Glassly page/lease/per-eye updates and tearing; raster reconnect or
power-cycle recovery; LifeLog wake/round completeness during raster, sustained
mic attribution/packet deduplication or audio preemption; continuous audio +
phone speech detection; power/thermal impact; any successful OTA/stock rollback
or brick recovery. Host tests cannot resolve those questions.
