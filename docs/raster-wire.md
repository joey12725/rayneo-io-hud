# Raster interoperability contract

Recorded 2026-11-12. This is a description of wire facts used to write original
Kotlin encoders, not a translation or incorporation of firmware/companion code.
No downloaded research script or binary was executed. Hardware validation is pending.

Sources: Turbo-IO `28a0fef0a42773f38222fcd0db2e0f2852ed4257` (2026-11-10),
[`docs/TVP2_TAR2.md`](https://github.com/Turbo1123/Turbo-IO/blob/28a0fef/docs/TVP2_TAR2.md),
`official-addon/tar2-display/{README.md,player.h,player.c,HDLink.m,FrameEncoder.m}`,
`official-addon/tar2-airplay/README.md`, and
`firmware-research/strix-1.0.5.5-tvp2/src/{video-player-v1/native.inc,camera-v1/camera_native.c}`.
Glassly `451dd88064e6f7544c1d1adf0e5ccd7093be5576` (2026-11-04),
[`docs/PROTOCOL.md`](https://github.com/tetramo-labs/glassly-rayneo-cfw/blob/451dd88/docs/PROTOCOL.md),
`patches/{link.c,raster.c,ident.c,disp.c,pixels.h}`. Where prose is stale, the
specific revision's receive/compose code takes precedence.

## Common carrier

Use the authenticated stock AA55 framing in [rayneo-protocol.md](rayneo-protocol.md).
These raster payloads are **raw bytes**, without the normal protobuf/JSON envelope.
TVP2 uses launcher business **decimal 15 = 0x0F**, not notification 0x15.
This app uses the existing secure RFCOMM service for TVP2. Glassly uses raw
business 0xF0 on the already authenticated B81D BLE send characteristic. Incoming
replies may arrive on either link. BLE writes use the existing serialized GATT
queue and platform write-completion backpressure. No second Bluetooth owner is opened.

The inspected firmware entry points do not demand a private API key or the
authors' companion. Guest-link behaviour still needs testing: Turbo checks
`nav_bonded(link)` and connection 0x80; those states are not proved by our guest
proof exchange. Glassly removed its pair-state-2 gate because guest links never
reach it (`docs/RAW-LINK-PLAN.md`, revision 3). `RasterTransport.open()` is the
explicit boundary at which a third-party connection can be refused. We do not
substitute an invented proprietary handshake or silently adopt someone else's identity.

## TVP2

Despite the TVP2 name, command magic is `TVP1`; reply magic is `VPA1`.
All integers below are unsigned little endian. Header is 32 bytes:

| Offset | Field |
| --- | --- |
| 0 | ASCII magic, 4 bytes |
| 4 | version 1, u8 |
| 5 | opcode u8 |
| 6 | result u8 (commands 0) |
| 7 | reserved zero |
| 8, 12 | nonzero session ID and sequence, u32 each |
| 16, 20, 24 | a, b, c, u32 each |
| 28 | CRC32/ISO-HDLC of header bytes 0..27 followed by data bytes 32..end |
| 32 | data, at most 14,967 bytes (total message ≤14,999) |

CRC32: reflected polynomial 0xEDB88320, initial/final XOR 0xFFFFFFFF
(`123456789` → CBF43926). Replies have no data. Reject wrong magic/version,
reserved byte, CRC, session, opcode or sequence. One request is in flight; a
3-second missing/rejected/mismatched ACK stops sending. HELLO must answer within
1 second. Results: 0 OK, 1 invalid, 2 busy, 3 stale, 4 CRC, 5 session, 6 render.
BUSY on BEGIN/STRIP/COMMIT drops that frame and waits for the newest one;
BUSY during session opening stops the attempt. No deferred frame advances the mirror.

| Opcode | Request | Successful reply |
| --- | --- | --- |
| 1 HELLO | a=0x32445056 (`VPD2` LE), b=c=0 | a=540, b=220 OR (1<<16), c=device tick token |
| 2 OPEN | a=HELLO token, b=c=0, same session | OK; token age must be −250..1500 ms |
| 6 BEGIN | a=nonzero increasing frame ID, b=2 or 4 wire bits, c=CRC of expanded grey pixels | a=frame ID, b=c=0 |
| 7 STRIP | a=y OR (rows<<16), b=wire bits, c=CRC32 of compressed strip; data=strip | a=cumulative rows, b=decode ms, c=cumulative compressed bytes for frame |
| 8 COMMIT | a=frame ID, b=0, c=expanded frame CRC | a=session displayed-frame count, b=decode ms, c=session compressed-byte count |
| 4 PING | a=b=c=0 | a=paused 0/1, b=displayed count, c=byte count |
| 5 CLOSE | a=b=c=0 | OK and release |

Each frame has four **sequential full-width 540×55 strips**, y=0,55,110,165.
Pack row-major samples MSB first, without row padding. Wire b=2 supports 0..3;
b=4 supports 0..15. Requested 2/4 levels use b=2; 8/16 use b=4. Eight requested
levels therefore become the nearest eight values on the 16-level wire palette.
The complete CRC covers the actual expanded byte intensities `sample*255/max`,
540×220 bytes with no stride padding. The phone mirror uses these same values.

Compress packed bytes with PackBits: tags 0..127 mean tag+1 literal bytes;
129..255 mean repeat the next byte 257−tag times; 128 is invalid. Raw maximum
is 14,850 bytes per 4-bit strip. Firmware switches front/back buffers only after
all strips and both kinds of CRC pass. **There is no changed-tile opcode**;
send all four strips on any changed frame and skip wholly identical frames.

Unsolicited op32 PAUSE and op33 EXIT use a separate increasing event sequence
within the same session. PAUSE a=0/1; EXIT releases ownership. PING every two
seconds prevents the 15-second idle timeout. Firmware also limits sessions to
600 seconds; the app renews at 540 seconds. User exit/error never silently
resumes raster in the same preference generation. A commit ACK means firmware
accepted presentation; it does not measure optical refresh.

### App session behaviour (raster workstream, Nov 2026)

* **Pipelining** (pref `raster.pipeline`, default on): BEGIN, the four STRIPs and
  COMMIT are written back to back with consecutive sequences, then the six replies
  are collected and the same counter checks run. The pinned receiver handles each
  packet synchronously and only needs increasing sequences. **Works on
  TMIG2-VIDEO-R1** (measured 2026-11-18): each stop-and-wait round trip costs
  80-360 ms on the BR/EDR link even for the 32-byte BEGIN, while the firmware
  decodes a strip in 5-8 ms, so pipelining cuts a ~12 KB frame from ~1.1 s to
  ~0.2-0.4 s on the wire. Any failed pipelined frame (non-OK reply, counter mismatch
  or timeout) reopens, resends stop-and-wait and turns pipelining off for 2 minutes
  or until the next connection. Only 3 failures in a row with no pipelined success in
  between (`raster.pipeline.timeouts`) persist `raster.pipeline.fallback`; a single
  R1 refusal (op 7 result 1) once disabled it for good. Writing `raster.pipeline`
  again clears both. The "one request in flight" rule above is how stop-and-wait
  works, not a proven firmware limit.
* **Renewal** at 540 s (or after 300 s while the display is dark) is a fast
  same-backend reopen: CLOSE, HELLO straight after the CLOSE ack (BUSY retried every
  40 ms), OPEN, cached frame. No stock text page, no 500 ms settle, no LifeLog
  stop/re-arm (TVP2's gate ignores an armed LifeLog; Glassly still disarms it).
  That gate behaviour is unverified on R1: if an open is refused while LifeLog is
  armed, the rest of that connection disarms LifeLog before every open and renewals
  take the acquire path again (the old behaviour), so the display keeps working.
* **PAUSE** (crown short-press) is recovered the same way, since only a reopen
  unpauses. While the display is dark it is also reported as a wearer wake.
* **Dark display** (`GlassesTransport.displayAwake(false)`): backend `BLACK`
  (default) commits one black frame and then sends no frames, PING every 4 s.
  Backend `PARK` (`power.backend=PARK`; look-up behaviour is `power.lookUp`)
  also CLOSEs the page 1.5 s later so stock auto-sleep and head-up can run; a stock
  `screen_status` with a non-zero value while parked reopens a black page and
  raises `onWearerWake`. Whether `screen_status` arrives on head-up is unverified.
  The service routes `screen_status` through `power.lookUp` (ON, OFF, TOGGLE,
  NOTHING) and `power.lookUpWhenLocked`; with NOTHING (stock head-up off) it can
  only be the crown, so it is a plain wake.
* **No caption page while raster is expected**: `display.mode=RASTER`, or the
  glasses address has opened a raster session before (`raster.proven.<address>`).
  HELLO is retried after 1 s and 3 s, and a failed session is retried after 1, 3
  and 10 s (forever in RASTER mode) before any text fallback.
* **Frame pacing**: latest frame wins; key-press and data-change frames go out at
  once; animation-only frames wait 1.5 s after the last press and then run at one
  per `raster.idleFrameMs` (default 1000). While interacting (10 s after a press) a
  fire-and-forget PING every 600 ms keeps the BR/EDR link out of sniff. While the
  raster glasses are the only viewer (phone app not visible), the service also renders
  animation-only frames at that same cadence instead of 10 Hz, so the main thread is
  free when a press arrives.

## Glassly direct raster (revision ≥7, IDENT bit4)

Send QUERY `[10 00 01]` on business F0 (hex bytes). After two seconds with no
IDENT, support is unknown/absent. Do not infer it from OsVersion.

IDENT: `[11][ver u8][w LE16][h LE16][maxChunk LE16][storeKiB LE16]
[revision LE16][flags u8][ASCII GLASSLYCFW-IO/n][00][lineHeight?]`.
Validate name, 540×220, chunk 1..240, revision ≥7 and raw+raster flags before
automatic use. Flags bit0 zlib, bit1 raw F0, bit2 context, bit3 two lenses,
bit4 direct raster, bit5 remote mic, bit6 head gestures. The old 540×180,
1-bit README description is **not** the newer direct raster path: `raster.c`
expands nibble grey as `v*17`, and `disp.c` packs L8 to 4-bit eye buffers.

Controls: `[10][stream=0][command]`, commands 2 PAGE_OPEN, 3 PAGE_CLOSE,
4 KEEPALIVE. Reply `[13][state 0/1][reason]`: 0 success, 1 busy, 2 historical
not-paired, 3 lease expired, 4 user exit (local long-close removed in v14).
PAGE_OPEN still calls stock isBussinessIdle. Keepalive renews 120 seconds;
this app sends it every two seconds, and stops on refusal or page loss.

Raster messages:

* Full: `[06][RLE for exactly 540*220 pixels]`.
* Box: `[03][left/4 u8][top/2 u8][width/4 u8][height/2 u8][frameId LE16][RLE]`.
  Frame ID is ignored by this firmware. Align x/width to 4, y/height to 2;
  include the unchanged pixels within the aligned bounds.
* Grey nibble RLE crosses rows. `[count<<4 | colour]` for 1..15;
  `[colour][count u8]` for 1..255; `[colour][00][count LE16]` for 1..65535.
  A zero count is invalid. Cover the exact full/box area, with no overrun.

Wrap each message in one uncompressed record:
`[flags][messageSize LE16][CRC16-CCITT-FALSE LE16][message]`.
Flags are 0x08 RESET_CONTEXT plus lens 1, or lenses 3 when IDENT bit3 is set.
CRC init FFFF, polynomial 1021, no reflection/final XOR (`123456789` → 29B1).
Compression is optional; this sender does not require zlib.

Packetize that record into chunks ≤IDENT maxChunk (normally 240):
`[opt][stream][chunk]`, opt bit0 LENS, bit7 RESET on first packet, bit6 END on
last. Stream is the low byte of the first packet's **AA55 sequence number**,
repeated in every packet. Reserve consecutive frame numbers for the stream.
One record is in flight. Each targeted eye must ACK:
`[01][stream][ordinal LE16=0][lens][messageSize LE16][messageCRC LE16]`.
NACK kind03 invalidates our delta baseline. Reject mismatched counts/CRC; do not
accept just one of two eye ACKs. Closed pages and partial malformed paints can
NACK. Reopening always requires a keyframe. The next SHOW/HIDE scene command
would take raster ownership away; this app does not send those scene commands.

First frame uses full-frame RLE when it fits the 65,535-byte record limit.
Noisy frames that do not fit use bounded full-coverage 32-row boxes. Later
frames use a changed-pixel bounding box, aligned as above and split if needed.
Box presents are not atomic whole-frame commits: tearing during a large split
update remains possible. Identical frames send nothing.

## App scheduling and audio boundary

Both senders run outside the audio event reducer, with one active frame and one
conflated pending frame. Pacing is at most 10 fps, measured after a completed
frame. Drain the pending slot at send time so the newest wins. Actual bandwidth
and display FPS require hardware. Mirror raster only after all frame ACKs;
mirror text after its successful write (stock has no render ACK).

Opening caption microphone capture suspends raster and closes its page first.
The existing caption/LifeLog audio lifecycle is retained. This branch does not
add continuous ambient recording. Glassly remote mic (business 0x0D type58/59)
is only a capability/documentation fact here, for the separate audio branch.
