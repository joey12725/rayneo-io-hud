# RayNeo iO (stock Strix OS / Bestechnic + NuttX): Bluetooth protocol spec for an Android (Kotlin) companion

Display/firmware correction, 2026-11-12: [firmware-decision.md](firmware-decision.md)
and [raster-wire.md](raster-wire.md) supersede older no-icon/pixel assumptions.
The emulator's BES1700 label and Turbo/Glassly's BES2800 label disagree; the
physical SoC marking/core count has not been verified here.

Compiled 2026-11-12 from three public repositories. They were cloned and read only (no builds or scripts run); the pinned commits are listed in
[firmware-decision.md](firmware-decision.md#what-evidence-this-decision-uses).

| Alias | Repo / ref | License |
| --- | --- | --- |
| **TOBI** | `Tobi1chi_OpenRayneo` @ `7f2fc0d` (main) | Apache-2.0 |
| **RUCH** | `ruchirlives_OpenRayneo` @ `1943b92` (branch `android-english-voice`; 4 commits on top of TOBI `7f2fc0d`) | Apache-2.0 (inherited LICENSE) |
| **TURBO** | `Turbo1123_Turbo-IO` @ `28a0fef` (main) | **PolyForm Noncommercial 1.0.0** (protocol facts only; no code copied) |

TOBI notes:
- PR #1 (`a9c4edc`: LifeLog, weather, ASR, captions, todo) and PR #2 (`9fcae3f`) are **already merged into main**. `git log main..pr/1` and `git log main..pr/2` are both empty.
- Branches `feat/display-experiments` and `feat/text-layout` are merged too. Everything is therefore cited from main.

**Citation format.** `ALIAS:path:line`.
- `A/` = `android/app/src/main/java/com/openrayneo/android/`
- `S/` = `Sources/OpenRayneoBridge/`

**Confidence labels.**
- **Verified**: the authors report a wearer-confirmed device test.
- **Static**: recovered from the official app (APK 1.0.5 or iOS RayneoNet); not hardware-tested by the citing repo.
- **Not found**: absent from all three sources.

**Scope.** This spec covers the phone-side "guest" authentication path that OpenRayneo implements. It deliberately does not document the official app's account-bound authentication branch (§2.5).

---

## 0. Architecture

```
Phone ─BLE GATT svc 0xB81D─► app auth (business 0x10); any business frame also works here
      ─BR/EDR RFCOMM (SDP UUID 99999999-…, resolves to channel 26)─► all business traffic (preferred)

Both transports carry the same frame:
  AA 55 | len BE16 | seq BE16 | flags u8 | [addr]? | [slice 0..7]? | business u8 | payload | CRC16-XMODEM BE16

payload:
  0x10 = pairing TLVs
  0x11 = raw protobuf (file transfer)
  else = protobuf envelope {1: version=1, 2: type, 3: UTF-8 JSON, 4: binary audio}
```

---

## 1. Discovery

### 1.1 Advertising and scanning

**Name.** Match case-insensitively on *contains* `"RayNeo iO"`.
- Sources: TOBI:A/RayneoConnection.kt:87; TOBI:S/main.swift:353.
- For bonded classic devices the filter is *contains* `"RayNeo"` (TOBI:A/RayneoConnection.kt:62-67).
- The exact full name format is **not found**.

**Service UUID** advertised: `0000B81D-0000-1000-8000-00805F9B34FB` (TOBI:A/RayneoConnection.kt:88,480).

**Manufacturer data.**
- Company ID `0x5716` (bytes `16 57` on air).
- `data[0]` = model `0x06` (VENUS/iO).
- `data[1..6]` = classic BR/EDR MAC, in display order.
- Match condition: `size >= 7 && data[0] == 6` (TOBI:A/RayneoConnection.kt:86-92).
- TOBI:docs/protocol.md:25 attributes this to the APK parser `S3.C0912a`.

**Scan settings.** `startScan(null, SCAN_MODE_LOW_LATENCY)`, filtered in the callback, stopped after 12 s (TOBI:A/RayneoConnection.kt:69-78).

**Discoverability.** The glasses can stop advertising after a re-pair. Re-entering pairing mode restored it (TOBI:docs/protocol.md:75-77).
- Pairing mode: long-press the glasses button for about 5 s until the blue LED blinks (TURBO:docs/CONFIGURATION.md:59).
- macOS first checks for already-connected B81D peripherals (TOBI:S/main.swift:480-484).

### 1.2 GATT layout (service B81D)

| Role | UUID | Use |
| --- | --- | --- |
| Send (phone→glasses) | `EA8B70D5-2BD3-49AB-9C31-9C38B2C3C4F9` | Write without response (TOBI:A/RayneoConnection.kt:326-327) |
| Receive (glasses→phone) | `7DB3E235-3608-41F3-A03C-955FCBD2EA4B` | Notify; CCCD `00002902-0000-1000-8000-00805F9B34FB` (TOBI:A/RayneoConnection.kt:224-229) |
| Pairing info | `EA8B60C5-2BD3-49AB-9C31-9D38B1C5C5F9` | Read |

Sources: TOBI:A/RayneoConnection.kt:479-484; TOBI:S/main.swift:247-250; TURBO:examples/macos-existing-bond/probe.py:18-21.

### 1.3 Pairing-info value

Length is 20 bytes (TURBO probe.py:240). TOBI only requires `>= 13` (TOBI:A/RayneoConnection.kt:214).

| Offset | Len | Meaning |
| --- | --- | --- |
| 0 | 6 | Glasses classic address. It is the `BluetoothDevice` address when hex-colon-formatted, and the "glasses identity" G used in auth (TOBI:A/RayneoConnection.kt:215-218,489). |
| 6 | 1 | State byte. Logged only; semantics **not found** (TOBI:A/RayneoConnection.kt:223). |
| 7 | 6 | Binding field: all-zero = unbound, non-zero = bound to some phone (TOBI:A/RayneoConnection.kt:219-222). |
| 13 | 7 | **Not found.** |

---

## 2. BLE application authentication (business `0x10`)

### 2.1 Android sequence (Verified, Xiaomi 13 / Android 16)

Sources: TOBI:A/RayneoConnection.kt:101-305; TOBI:android/README.md:79-85.

1. **Classic bond first**, if the advert carried a classic address.
   - Call `createBond()`.
   - Wait for `ACTION_BOND_STATE_CHANGED` (registered `RECEIVER_EXPORTED`) with a 45 s timeout (:108-113, :125-161).
   - The tested unit uses Just Works pairing (TOBI:docs/protocol.md:43).
2. **Connect GATT:** `connectGatt(ctx, false, cb, TRANSPORT_LE, PHY_LE_1M_MASK, mainHandler)`. Overall timeout 25 s (:116-123).
3. **Discover services** with `discoverServices()`. Require B81D and all three characteristics (:182-193).
4. **Negotiate MTU** with `requestMtu(512)`.
   - Require `mtu >= 160`. Chunk size is `mtu - 3`. Observed MTU was 517 (:191-202; TOBI:android/README.md:77).
   - **RUCH fix:** Android may deliver an unsolicited or duplicate `onMtuChanged`. Gate on "services discovered, then the first MTU callback" (RUCH:A/BleIdentityGate.kt:1-18, used at RUCH:A/RayneoConnection.kt:189,201).
5. **Read pairing-info** (§1.3). If the user picked a classic address, verify that it matches.
6. **Enable notifications** by writing the Receive CCCD.
7. **Send command 17**, device-info (§2.3), from `onDescriptorWrite`.
8. **Handle the command-17 reply.**
   - If it contains TLV 25, abort with "account-bound auth unsupported".
   - TURBO also aborts on a non-empty TLV 26 (TURBO probe.py:98-102).
   - Otherwise send command 24 (§2.4).
9. **Handle command 25.** Verify the proof, confirm the bond, then open RFCOMM (§3).
10. **Status commands 22 and 23** arrive unsolicited. Body byte 0 of command 23 is the pairing state; `2` means paired (TOBI:docs/protocol.md:180-190; TOBI:android/README.md:83).

**BLE writes.** Serialize them: one write in flight, the next sent from `onCharacteristicWrite` (TOBI:A/RayneoConnection.kt:312-328). TURBO's probe also:
- leaves 30 ms between chunks (TURBO probe.py:270);
- waits 3 s after subscribing before sending.

### 2.2 Pairing payload

```
payload = cmd:u8 | len:BE16 (= payload.size - 3) | inner TLVs
TLV     = tag:u8 | len:BE16 | value
```

- `cmd` acts as the outer TLV tag.
- Encoder: TOBI:A/ProtocolCodec.kt:29-38. Parser: TOBI:A/ProtocolCodec.kt:40-54.
- TURBO's TLV.swift:3-35 and IOSVersionExchange.swift:76-92 agree.
- Commands 22 and 23 carry raw bytes, not TLVs (TOBI:S/main.swift:105-106).

### 2.3 Command 17 (0x11): device-info, phone→glasses

Source: TOBI:A/RayneoConnection.kt:239-244.

| Tag | TOBI Android | TOBI macOS (S/main.swift:544-552) | TURBO iOS (IOSVersionExchange.swift:79-89) | Meaning |
| --- | --- | --- | --- | --- |
| 16 | `01` | `01` | `01` | Protocol version |
| 17 | 6-byte phone ID (§2.5) | same | same | Phone protocol identifier |
| 18 | `adapter.name` UTF-8 (fallback `"OpenRayneo Android"`) | Host BT name | Phone name | Display name |
| 19 | `02` | `02` | `01` | Probably OS type (1 iOS, 2 Android); enum **not found** |
| 22 | `Build.MANUFACTURER` | `"Apple"` | `"iPhone"` | Manufacturer |
| 23 | `Build.MODEL` | Host name | Model | Model |
| 27 | `01` | `01` | `01`/`02` (omitted for model V4) | "pairValue" (TOBI:docs/protocol.md:143-145) |

`pairValue` per the APK: 1 = pairing, 2 = reconnect. TOBI keeps `1`.

**Glasses reply (command 17).** Sources: TURBO:IOSVersionExchange.swift:136-141; TURBO probe.py:98-102.
- 16 = peer protocol version (1 or 2).
- 17 = glasses identity (equals pairing-info bytes 0-5).
- 20 = serial number (UTF-8).
- 24 = MFi serial.
- 25/26 = present only when the glasses demand account-bound authentication.

### 2.4 Commands 24/25: challenge and proof (guest path, Verified)

**Command 24 (0x18), phone→glasses.**
- TLV 16 = `R`, 4 random bytes.
- TLV 17 = `SHA-256(R ‖ P ‖ G ‖ K)`, 32 bytes.
- Sources: TOBI:A/RayneoConnection.kt:284-286,330-331; TOBI:S/main.swift:615-625.

**Command 25 (0x19), glasses→phone.**
- TLV 16 = `R'`, 4 bytes.
- TLV 17 = `SHA-256(R' ‖ P ‖ G ‖ K)`.
- Verify the proof before proceeding (TOBI:A/RayneoConnection.kt:288-301; TOBI:S/main.swift:584-601).

**Inputs.**
- `P` = 6-byte phone ID.
- `G` = pairing-info bytes 0-5.
- `K` = fixed public protocol constant `07 12 C8 AD 27 74 41 95 DE 5F A1 EA 6D 02 5F B8`. Sources: TOBI:A/RayneoConnection.kt:487; TOBI:S/main.swift:251; TURBO:Authentication.swift:12.

No link encryption above Bluetooth's own was observed after authentication.

### 2.5 Phone identity and pairing a fresh phone

**Your identity.**
- TOBI derives P from the last 12 hex digits of `Settings.Secure.ANDROID_ID`, read as 6 bytes. This mirrors the official APK's `E3/P` (TOBI:A/RayneoConnection.kt:333-337; TOBI:android/README.md:59).
- Generate it once and persist it. TURBO saw version requests go unanswered when a different identity was used against an existing bond (TURBO:examples/macos-existing-bond/README.md:17).

**Fresh phone.** No cloud account is needed for the guest path.

**Recommended first pairing** (TURBO:docs/CONFIGURATION.md:59):
1. Unbind the glasses in the official RayNeo app.
2. Forget the glasses in the phone's Bluetooth settings.
3. Long-press the glasses button until the blue LED blinks.
4. Pair from your app and confirm the system dialog.

**Account-bound branch** (TLV 25/26 in the reply).
- Not implemented by TOBI or RUCH; both refuse it explicitly (TOBI:README.md:305; TOBI:A/RayneoConnection.kt:283).
- Not covered by this spec. If you hit it, unbind via the official app.

**Note on TOBI's bound-ID fallback.** TOBI reuses pairing-info bytes 7-12 as P when they are non-zero (TOBI:A/RayneoConnection.kt:219-222). Do not copy this. It means presenting the identity of whichever phone the glasses are already bound to, which is not your app's own identity. Use your own persisted identity and the unbind flow above.

**Link-key pitfalls** (from macOS):
- BLE auth can succeed while RFCOMM fails with `INVALID_LINK_KEY`.
- Recovery: forget only the host-side bond and re-pair, keeping the glasses' state unchanged (TOBI:docs/protocol.md:79-128).
- On Android, one attempt returned `HCI_ERR_AUTH_FAILURE` about 30 s after the bond dialog; the retry succeeded (TOBI:android/README.md:82-84).

---

## 3. Classic data channel (RFCOMM) and framing

### 3.1 Opening the socket on Android

**Socket call.** `device.createRfcommSocketToServiceRecord(UUID.fromString("99999999-9999-9999-9999-999999999999"))`.
- Secure socket, public API, **no `createRfcommSocket(26)` reflection** (TOBI:A/RayneoConnection.kt:344-367,486).
- SDP resolves this UUID to `scn:26` (TOBI:android/README.md:57,84).
- The UUID comes from the APK's business call chain `E3/M → … → V3/p`.

**Do not use** the standard SPP UUID `00001101-…`. It belongs to the APK's RNLink log-capture module and gave `scn=0` (TOBI:android/README.md:57,77).

**Connect procedure.**
1. Call `adapter.cancelDiscovery()`.
2. Call blocking `connect()` on a single-thread executor, with a 20 s timeout.
3. Start a reader thread with a 4096-byte buffer (TOBI:A/RayneoConnection.kt:352-388).

**macOS equivalent.** `requestAuthentication()`, then `openRFCOMMChannelAsync(channelID: 26)` with a 12 s timeout (TOBI:S/main.swift:805-846).
- The channel opened in 46-75 ms with **RFCOMM MTU 1011** (TOBI:docs/protocol.md:87,128).
- HFP is not needed (TOBI:docs/protocol.md:147-149).

**BLE-only mode** (APK `E3/M`, VENUS model 06).
- Business frames can be written to the GATT Send characteristic, and a notification did display (Verified).
- However, the glasses stayed on the pairing screen instead of going home. RFCOMM is required for a full connection (TOBI:android/README.md:58,81).

**Inbound frames on BLE.** Even in full mode, business frames arrive on BLE as well as RFCOMM. Decode both, and de-duplicate audio by JSON `seq` using a window of the last 64 values (TOBI:docs/android-notifications.md:77; TOBI:A/GlassesDisplay.kt:80-87).

### 3.2 Segmentation and size limits

**Sending.**
- Android: write the whole frame to `outputStream` and flush (TOBI:A/RayneoConnection.kt:417-434).
- macOS: slice into chunks of at most the RFCOMM MTU (1011) under a write lock (TOBI:S/main.swift:763-791).
- BLE: chunks of at most `ATT MTU − 3`.

**Receiving.** The decoder must handle split and concatenated frames.

**Payload limit.**
- A 16,319-byte script went in a single frame (Verified, TOBI:docs/protocol.md:225).
- TOBI caps the payload at 65,531 bytes.
- TURBO says the vendor encoder caps it at 65,524 bytes (TURBO:TransportFrame.swift:17-18). Use ≤ 65,524.

### 3.3 Frame layout

```
off  size  field
0    2     magic: AA 55 = data; 55 AA = transport acknowledgement (TOBI:A/ProtocolCodec.kt:23)
2    2     L = BE16 count of bytes from 'seq' through end of payload
4    2     seq (BE16)
6    1     flags/packet type:
             bits 0-4 = flags (bit0 = ack requested; bit1 = 1-byte address present)
             bits 5-7 = count of opaque slice bytes
           (TURBO:TransportFrame.swift:41-50,92-109; TOBI:A/ProtocolCodec.kt:148-149)
7    0/1   address (only if bit1)
     0..7  slice metadata (semantics unverified)
     1     business ID
     n     payload
end  2     CRC16-XMODEM (BE16) over bytes [4, end-2)
```

**CRC16-XMODEM.** Polynomial 0x1021, initial value 0, no reflection or xorout. Equivalent to Python `binascii.crc_hqx(data, 0)` (TOBI:A/ProtocolCodec.kt:11-18; TURBO probe.py:25-26).

**Outgoing frames.** TOBI always sends `flags = 0`, with no address and no slice bytes (TOBI:A/ProtocolCodec.kt:20-25).

**Acknowledgements.**
- When an inbound frame has `flags & 1`, reply with: magic `55 AA` | len | same seq | `00` | same business | empty payload | CRC (TOBI:A/ProtocolCodec.kt:27).
- TOBI does this on BLE (TOBI:A/RayneoConnection.kt:265-267) but not on RFCOMM, which works regardless (:378-381).
- Drop inbound `55 AA` frames.

**Sequence numbers** appear to be unvalidated by the glasses.
- Android counters: pairing starts at 0, notifications at 0, display messages at `0x4000` (TOBI:A/GlassesDisplay.kt:27).
- macOS uses one shared counter.
- File-transfer frames always use seq 0 (TOBI:S/main.swift:82-84).

**Decoder algorithm** (TOBI:A/ProtocolCodec.kt:119-158):
1. Find either magic.
2. Require L ≥ 4.
3. Wait until L + 6 bytes are buffered.
4. Check the CRC. On failure, skip 1 byte and rescan.

**Heartbeat / keep-alive:** **not found** for stock firmware. TOBI only detects EOF and socket errors.

### 3.4 Protobuf envelope (all businesses except 0x10 and 0x11)

Fields (TOBI:docs/schedule-weather-protocol.md:10,21):

| Field | Type | Content |
| --- | --- | --- |
| 1 | varint | version = `1` |
| 2 | varint | message type |
| 3 | bytes | UTF-8 JSON |
| 4 | bytes | binary data (audio). Phone→glasses notification, teleprompter and launcher frames include an empty field 4 (`22 00`); display frames omit it. |

- Byte layout: `08 01 10 <type> 1A <len varint> <json> [22 <len> <bin>]`.
- Sources: TOBI:A/ProtocolCodec.kt:56-102; TOBI:S/main.swift:59-80.
- Encoding is always UTF-8; GBK is never used.

### 3.5 Business IDs (wire byte)

| ID | Name | Used for |
| --- | --- | --- |
| 0x09 | MARS_FOTA | version / battery / OTA preflight (§8) |
| 0x0B | LOG_REPORT | glasses logs (TURBO) |
| 0x0D | VOICE_ASSISTANT | assistant rounds; LifeLog A1-A8 (§7) |
| 0x0E | Recording service | glasses-initiated recordings (TURBO, §7.5) |
| 0x0F | LAUNCHER | weather, dashboard, settings, status, LifeLog switch, page reports |
| 0x10 | Pairing/auth | §2 |
| 0x11 | File transfer | teleprompter script (§4.2) |
| 0x13 | AI_SUBTITLE | captions / character grid (§4.3) |
| 0x14 | TELEPROMPTER | §4.2 |
| 0x15 | NOTIFICATION | §4.1; suggestion cards / head gestures (§6) |
| 0x16 | SCHEDULE_TODO | §4.5 |
| 0x17 | PROACTIVE_AI ("real-time prompt") | §4.4; stereo mic audio (§7.1) |

Sources: TOBI:docs/display-protocol.md:9-15; TOBI:docs/protocol.md:165-170; TOBI:docs/firmware-analysis.md:7; TURBO:android-addon/src/com/turboio/addon/HostBusiness.java:13-20; TURBO:core-probe/Sources/MessageABI.swift:31-35.

TURBO's numbers are the vendor SDK's business indices. They match TOBI's wire bytes wherever the two overlap.

---

## 4. Display surfaces

There is no transport-level render acknowledgement. A successful write proves transmission only, so the APIs return 202 (TOBI:docs/protocol.md:196).

### 4.1 Notification: business 0x15, type 2 (Verified)

**Envelope:** `08 01 10 02 1A <len> <json> 22 00` (TOBI:A/ProtocolCodec.kt:56-61).

**JSON sent by TOBI** (TOBI:A/RayneoConnection.kt:392-407; TOBI:S/main.swift:971-988):
```json
{"notificationUID":"<ms timestamp string>","appId":"com.openrayneo.bridge","appName":"OpenRayneo Android",
 "title":"…","subtitle":null,"content":"…","timestamp":"<ms timestamp string>",
 "category":0,"reply":false,"type":1}
```

**Official-app variant (Static)**, from TURBO:apps/RayNeoCompanion/Sources/CompanionNotifications.swift:30-49:
- `notificationUID` = decimal string from 1 to Int32.max.
- `subtitle` = `""`.
- `timestamp` = local ISO-8601 `yyyy-MM-dd'T'HH:mm:ss.SSS`.
- TURBO's local limits: title ≤ 80 chars, content ≤ 500. These are not firmware limits; firmware limits are **not found**.

**Related messages on 0x15 (TURBO, Static/iOS).**

| Type | Direction | Body | Source |
| --- | --- | --- | --- |
| 17 | phone→glasses | settings: `{"notification":bool,"callNotification":bool,"avoidDuplicate":false,"displayTime":5\|10\|15\|20\|30,"intervalTime":2,"filterUID":[blocked appIds]}` | :17-28 |
| 18 | phone→glasses | master switch `{"notification":bool}` | :204 |
| 18 | glasses→phone | reports the current switch value | :271 |
| 20 | phone→glasses | `{}` queries ANCS availability | :224 |
| 21 | glasses→phone | `{"available":bool}` answer to type 20 | :273 |
| 3 | glasses→phone | `{"notificationUID","state"}`; state 0 idle, 1 showing, 2 not worn, 3 do-not-disturb, 4 busy with another feature | :51-60, :278 |
| 4 | glasses→phone | `{"notificationUID","cmd":n}`; user operation on the notification, semantics unverified | :281 |

Your `appId` must not appear in `filterUID`, and the master switch must be on.

**Clearing a notification:** no command found. Notifications time out via `displayTime`.

### 4.2 Teleprompter: business 0x14 plus file transfer 0x11 (Verified start/pause/resume/stop)

Envelope: `08 <action> 10 <type> 1A <json> 22 00`. Field 1 here carries "action" (TOBI:S/main.swift:59-65). After each teleprompter frame, macOS sleeps 25 ms (TOBI:S/main.swift:1097-1101).

**Start sequence** (TOBI:S/main.swift:990-1065):

1. **Settings.** Send type 2 / action 1:
   ```json
   {"action":1,"countdown":3,"scroll":1,"speed":120,"gear":1,"depth":1,"size":18,"width":492,"leading":4,
    "total":<UTF-16 length>,"did":"<uuidv7>","pageOffset":0,"highLightOffset":0}
   ```
   - Tested speeds: 60 and 120.
   - The ranges and units of `size`, `width`, `leading`, `gear` and `depth` are unknown (TOBI:docs/apk-display-layout.md:40-52).
2. **Upload the script on 0x11.** Payloads are raw protobuf; seq 0 (TOBI:S/main.swift:1103-1159). Messages, each the given field wrapping a nested message:
   - Host → glasses, field 1: `{1: taskId "task_2_<ms>", 2: did, 3: byteCount (UTF-8), 4: md5 lowercase hex, 5: 17 (content type)}`.
   - Glasses → host, field 3 (chunk request): `{1: taskId, 2: chunkNo, 3: offset (absent means 0), 4: size}`.
   - Host → glasses, field 4 (chunk response): `{1: taskId, 2: chunkNo, 3: offset, 4: count, 5: bytes}`. Loop until the whole file is covered.
   - Glasses → host, field 5 (completion): `{1: taskId, 2: result}`. Absent result means 0 = success.
   - Field 2 is a descriptor reply that was observed but is unused.
3. **Wait** up to 1 s for type 2 / action 2.
4. **Script list item.** Send type 1 / action 2:
   ```json
   {"action":2,"type":3,"total":<scripts+2>,"chunkinfo":{"chunkid":1,"total_items":1,"islast":true},
    "items":[{"did":…,"pin":false,"title":…,"offset":0,"scroll":1,"total":<UTF-16 len>,"timestamp":<unix s>,
              "preview":"<first 80 chars>","timezone":<GMT offset s>}]}
   ```
5. **Transfer-complete / start.** Send type 3 / action 1: the settings from step 1, plus `"code":1,"checksum":"<FNV-1a-32 of UTF-8, 8 lowercase hex>"`.

**Encoding units.** Transfer sizes and offsets are UTF-8 bytes. The metadata `total` is UTF-16 code units (TOBI:docs/protocol.md:214).
- Conflict: TURBO's iOS path sends `total` as the UTF-8 byte count, uses `scroll:2`, and omits the layout fields (TURBO:apps/RayNeoCompanion/Sources/CompanionDeviceFeatures.swift:402).
- Prefer the verified TOBI values.

**Controls, phone→glasses.** All carry `did`.

| Type | Meaning | Body | Source |
| --- | --- | --- | --- |
| 4 | pause | `{"action":1,"did","offset":0,"code":1,"isCompleted":false}` | TOBI:S/main.swift:1067-1090 |
| 5 | resume | `{"action":1,"did"}` | TOBI:S/main.swift:1067-1090 |
| 6 | stop / close the surface | `{"action":1,"did"}` | TOBI:S/main.swift:1067-1090 |
| 7 | speed change (TURBO, Static) | `{"action":1,"did","scroll":2,"speed":60..240}` | TURBO CompanionDeviceFeatures.swift:417 |
| 8 | progress | `{"action":1,"did","pageOffset","highLightOffset","autoSync":bool\|null}` | TURBO TeleprompterJSONCodec.swift:177-180 |

**Paging and scroll.** Positions are expressed as `pageOffset` / `highLightOffset` (UTF-8 offsets) on type 8. There are no separate "page up/down" commands: **not found**.

**Requests from the glasses** (action 1): types 4, 5, 6 and 8. See §6. The app replies with the same type: `{"action":2,"did","code":1}`; code 2 means "not current session" (TURBO:rayneo-display/Sources/RayNeoDisplay/TeleprompterJSONCodec.swift:3-8,107-111,119-152,189-195).
- Type 9 from the glasses carries teleprompter audio (TOBI:docs/android-notifications.md:67).

### 4.3 Caption page / character grid: business 0x13 (Verified)

Display envelope: `08 01 10 <type> 1A <json>`, with no field 4 (TOBI:A/ProtocolCodec.kt:63-66; TOBI:S/main.swift:75-80).

Two startup variants work.

**Variant A, text-only** (macOS; TOBI:S/DisplayController.swift:235-262):
1. Send type 7:
   ```json
   {"sid":"<32 lowercase hex>","force":false,"scope":"temporary","config":{"font_size":2,"content_width":100,"max_lines":5,"position":"center","is_display":true,"straight_view":"original"}}
   ```
2. The glasses reply type 8 with the same `sid`, `code:1` and `effective_config`. Always use `effective_config`; the firmware clamps requests.

**Variant B, with mic audio** (Android navigation; TOBI:A/GlassesDisplay.kt:36-49,103-136; TOBI:docs/android-notifications.md:69):
1. Send type 1:
   ```json
   {"sid","trigger":1,"force":false,"code":0,"settings":{"mode":"translation","source_language":"zh-CN","target_language":"zh-CN","save_audio":false,"direction":"ahead"}}
   ```
2. Wait for type 2 with `code:1`. Audio (type 4) starts at this point.
3. Send type 7 (layout) on the same SID and wait for type 8.
4. Wait 500 ms before the first text frame.

**Text frame** (type 5):
```json
{"sid":…,"mode":3,"status":1,"content":{"source_transcript":"<text>"}}
```
- `status` 0 = partial, which renders progressively.
- `status` 1 = final, which replaces the whole frame at once. Use 1 for grids (TOBI:docs/tui-navigation.md:33-37).
- Optional `target_translation` (TOBI:S/DisplayController.swift:263-280).

**Close** with type 3: `{"sid","reason_code":10,"text":""}`; the glasses return home (TOBI:docs/display-protocol.md:53).
- If the **glasses** send type 3, the user exited. Release the display (TOBI:A/GlassesDisplay.kt:96-101).

**Grid geometry** (TOBI:docs/text-layout.md:38-57).

| font_size | content_width | Effective max_lines | Grid | Status |
| --- | --- | --- | --- | --- |
| 2 | 100 | 5 or 6 | 20 columns × 5 or 6 rows | Verified |
| 1 | 100 | 7 | 26 columns × 7 rows | Verified; the largest obtained |

Clamps observed: font 0 → 1, width 120 → 100, lines > 7 → 7.

**Cell rules.**
- Use only full-width characters.
- Convert ASCII `!`..`~` by adding `0xFEE0`.
- Use `U+3000` for blank cells. `U+0020`, NBSP and figure space are not equal width.
- Join rows with `\n` (TOBI:docs/text-layout.md:22-34,57; TOBI:A/GlassesDisplay.kt:195-203).
- Half-width Latin is not monospaced.

**Alignment workaround** (font 2). The first frame of a session needs no prefix. Every later frame needs one `U+3000` before the entire string, not per row (TOBI:docs/tui-navigation.md:39-53; TOBI:A/GlassesDisplay.kt:146).

**Video modes** (TOBI:docs/text-video.md:18-28; TOBI:S/TextVideo.swift:9-54):

| Mode | Characters | Resolution | Status |
| --- | --- | --- | --- |
| Full-width grayscale | `U+3000` plus `U+FF01..FF5E`, chosen by measured ink coverage | 26×7 (2×4 box-filtered from 52×28 samples) | Verified |
| Blocks | `█` or `U+3000` | 26×7 | Verified |
| Half blocks | `▀ ▄ █ U+3000` | 26×14 | Experimental |
| Braille | — | — | Reported unusable; removed |

**Refresh rate.**
- Daily-use target: 5-10 whole-frame updates per second. 30/s ran smoothly with constant-speed content.
- At 60/s and 120/s there was stutter and catch-up.
- Send sequentially and skip stale frames rather than queueing (TOBI:docs/text-layout.md:86-130).

### 4.4 Real-time prompt (question/answer page): business 0x17 (Verified)

Sources: TOBI:docs/display-protocol.md:55-73; TOBI:S/DisplayController.swift:244-290; TOBI:A/GlassesDisplay.kt.

1. **Start** with type 1, as in Variant B but with `"mode":"conversation"`. The glasses reply type 2 with `code:1` and `final_settings`.
   - Starting this page **always opens the mic uplink** (type 4), even with `save_audio:false`.
   - Type 7 does not start this page.
2. **Text** with type 5:
   ```json
   {"sid","mode":4,"status":0|1,"content":{"source_transcript":"<question/title>","target_translation":"<answer>","keyword_info":null,"label":0}}
   ```
   A final update replaces the previous pair. A 120-line answer in a single update worked host-side (TOBI:docs/virtual-microphone.md:84).
3. **Close** with type 3: `{"sid","reason_code":2,"text":""}`.

### 4.5 Todo: business 0x16 (Verified)

Source: TOBI:docs/display-protocol.md:75-103; TOBI:S/DisplayController.swift:170-212,388-426.

**Query.** Send type 15: `{"queryType":0,"eventType":1,"lastSyncTime":0,"eventIDList":[],"needFullData":true}`.
- Replies arrive as type 16 batches containing `scheduleTotal`, `todoTotal`, `batchNo`, `isLastBatch`, `needFullData`, `dataList`.
- Collect batches until `isLastBatch` is true.

**Full-list replace.** Send type 6: `{"total":n,"isLastBatch":true,"eventList":[…]}`. Each item:
```json
{"eventType":1,"eventID":<int64>,"createTime":<unix ms>,"title":…,"isImportant":bool,"status":0|1,"lastModifiedTime":<unix s>}
```
Preserve the existing items when you write the list back. Keep IDs as int64.

**TURBO additions** (Static):

| Type | Direction | Meaning | Source |
| --- | --- | --- | --- |
| 2 | phone→glasses | single-item update | CompanionDeviceFeatures.swift:280-282 |
| 4 / 10 | glasses→phone | status changes (completed on the glasses) | :286-289 |
| 14 | glasses→phone | `successCount` receipt | :286 |

Schedule type 5 exists, but its fields are unknown. Do not send it as a probe, because it may clear schedules (TOBI:docs/schedule-weather-protocol.md:32-41).

Keep the official app disconnected while writing todos.

### 4.6 Home and weather: business 0x0F, launcher command wrapper (Verified)

Sources: TOBI:docs/schedule-weather-protocol.md:60-177; TOBI:S/DisplayController.swift:144-169,353-386.

**Wrapper.** Send type 18:
```json
{"cmd":"<cmd>","payload":{"value":0,"mode":0,"data":"<JSON STRING>","ts":"<unix seconds string>"}}
```
The glasses reply type 19 `{"cmd":"<cmd>","payload":{"value":0}}`. The reply has no transaction ID, and `value:0` is not a proven success code.

**Commands.**
- **`current_weather_update`** sets the home-page temperature. `data` = `{"location":…,"icon":<int>,"temp":<int>}`.
- **`weather_update`** sets the city cards. `data` is a JSON array of:
  ```json
  {"location","location_id":"<existing configured id>","temp","icon","des","temp_range":"27°/14°","hourly":{"10am":[22,100],…}}
  ```
  - The `hourly` members must stay in display order. Build this object by hand; do not use a map.
- **Icon codes:** use the QWeather-style namespace, not OpenWeather condition IDs:
  100 sunny, 101 cloudy, 104 overcast, 150 clear night, 151 cloudy night,
  300 shower, 302 thundershower, 305 light rain, 310 storm, 400 light snow,
  501 fog. [QWeather's catalogue](https://icons.qweather.com/en/icons/) names
  those codes; firmware may group them. Only 100→150 was visibly confirmed
  upstream. 2001 remains an extra, unclassified experiment; a complete firmware
  mapping and distinct appearance of every code are unverified.
- **`dashboard_config`** is read-only: `data:""`, `ts:"0"`. The reply's `data` contains `simple_mode`, `widgets`, `widgets_v2`, and `widgets_data.weather.location_ids`.

The official app overwrites these values when it reconnects.

### 4.7 Navigation (Android, Verified)

Navigation is not a separate surface. It is a 20×5 grid on 0x13 (Variant B) using block arrows (TOBI:A/GlassesDisplay.kt:195-223; TOBI:docs/tui-navigation.md). The data is parsed from Amap notifications.

---

## 5. Bitmaps, images and icons

**Stock: no general arbitrary-bitmap upload/draw command was found (high confidence,
not proof of absence). Stock does have resident icons, cards and structured UI.**
Notification 0x15/type2 selects firmware app icons through `appId` (Spotify,
WhatsApp, Telegram, YouTube, Gmail among others) and category fallbacks; test
categories 0..33, without claiming all are distinct. These strings/resources
are present in the unpacked newer stock APs. Weather codes (§4.6), todo and
suggestion cards also use resident imagery.

Stock's A2UI v0.9 runtime supports Row/Column/Card/Divider/Text/Icon/BarChart/
LineChart (and List in the older symbol catalogue), with 13 named icons.
Turbo `official-addon/A2UIProtocol.m` documents launcher **0x0F/type18**
`widget_install`/`widget_uninstall`; `payload.data` is an object, with `extras`
containing a JSON string whose `uiContent` has createSurface/updateComponents.
This differs from weather's JSON-string `payload.data`. Expect a type19/code0
reply, and still inspect the physical dashboard. Installation is reported
disabled from 1.0.5.5 (remaining strings are not proof of a reachable handler).
The app's minimal probe is bounded to **509 bytes including AA55 framing**.

Firmware geometry is **540×280 per-eye 4-bit green panel buffers**, **540×220 UI**,
with a **544×220 RGB565** aligned LVGL v9 source buffer. Our 540×220 simulator and Android master now matches the exposed UI surface
1:1. A 26×7 grid implies only a 20.77×31.43 pixel *budget* within
the UI, not a measured advance; 20×5 gives 27×44. See the decision document for
font-2's reported 20×6 layout and the remaining optical measurements.

**TURBO's graphics require its modified firmware.** Installing it is an OTA flash, risky and non-commercial. Its claims:
- **Turbo Display:** 512×128 8-bit grayscale canvas with partial updates (TURBO:docs/TURBO_DISPLAY_NAVIGATION.md:9-11).
- **Native navigation page:** 540×180, ≤ 328 B per update, ≤ 1 Hz (:19-22).
- **TAP1 app runtime:** 540×180, ≤ 4 pages × 12 components. Components: text, button, image, progress bar, border.
  - Images are 8-128 px with width a multiple of 8, in `mono1-msb` format; ≤ 32 KiB decoded per page.
  - Delivered as a ZIP of ≤ 24,576 bytes over the file channel (TURBO:dashboard-service/README.md:128-130).
- Full binary formats live under `official-addon/research/app-runtime-v1/` and were not reviewed in depth (non-commercial license).

Newer **TVP2** (Strix 1.0.5.5; 1.0.6.11 VIDEO-R1 port) is a separate raw
business **decimal15 / 0x0F** protocol: native 540×220, four strips, grey levels,
CRC and atomic commit. Glassly revision ≥7 is a raw BLE **0xF0** alternative,
540×220 full/box-RLE raster with per-eye ACKs. Original phone encoders are now
implemented here; exact wire details and guest-link caveats are in
[raster-wire.md](raster-wire.md). None of these raster paths is stock support.

**Usable on stock firmware:**
- Suggestion cards (title/source/content text) on 0x15 (§6).
- Built-in weather icon codes.

---

## 6. Input events from the glasses

| Event | Wire | Source / status |
| --- | --- | --- |
| User exits a caption/prompt page | 0x13 / 0x17 type 3 `{"sid","reason_code"}` | TOBI:A/GlassesDisplay.kt:96-101 (Verified) |
| Teleprompter pause/resume/stop/progress (crown or temple on the teleprompter page) | 0x14 types 4/5/6/8, action 1; reply action 2 code 1 | TURBO TeleprompterJSONCodec.swift:119-152; CompanionDeviceFeatures.swift:437-449 (Static/iOS) |
| **Head gesture nod/shake** on a suggestion card | Phone sends 0x15 type 33 `{"type":1,"title","source","content","suggestUID","suggestType":2 (todo) \| 1 (calendar, + optional "timeRange")}`. Glasses reply type 34 `{"suggestUID","suggestType","cmd":1 accept (nod) \| 2 reject (shake)}`. Phone acks type 35 `{"suggestUID","suggestType","code":0}` and removes the card with type 33 `{"type":2,…empty strings}`. | TURBO SuggestionCardJSONCodec.swift:5-8,60-86; SuggestionJSONCodec.swift:97-103,129-175; HeadControlNotificationTest.swift:92-118,183 (iOS test) |
| Notification state / operation | 0x15 types 3 / 4 | §4.1 |
| Voice-assistant wake (trigger mechanism unspecified) | 0x0D type 1; next-round request type 11; glasses exit type 8 | TURBO:core-probe/Sources/StandbyVoiceSession.swift:61-104 |
| LifeLog wake / exit | 0x0D type 161 (A1) / 165 (A5) | §7.3 |
| Glasses page report | 0x0F type 24 `{"cmd":"glass_preview","payload":{"data":"<json: scence\|scene 0 dashboard / 1 app menu / 2 app page, index, app:{name,action (2 = exited)}>"}}`. App names include `com.rayneo.liteos.prompter`, `.aiSubtitle`, `.conversationAssist`, `.todo`, `.recorder`, `.ai_memory`. | TURBO:apps/RayNeoCompanion/Sources/DisplayObservation.swift:25-27,56-70 |
| Screen status | 0x0F `{"cmd":"screen_status","payload":{"value":n}}`; meaning unknown | DisplayObservation.swift:71-74 |
| Raw crown rotate/click, touch, or raw head-gesture stream on stock firmware | — | **Not found**. Crown events are reported only through page-specific messages, and the crown double/long-press mapping is a setting (§8). |

---

## 7. Audio

### 7.1 Prompt page (0x17) and caption page (0x13) uplink (Verified)

**Start / stop.**
- The uplink starts automatically when the type-1 startup is accepted (§4.3 Variant B, §4.4).
- It stops when the page closes (type 3).
- There is no separate mic start/stop command on these pages.

**Packets.** Type 4, JSON `{"sid":…,"seq":n,…}`, with field 4 = **one Opus packet** (TOBI:S/main.swift:919-923; TOBI:A/GlassesDisplay.kt:80-94). Drop packets whose `sid` does not match, and de-duplicate by `seq`.

**0x17 parameters.**
- **48 kHz, 2 channels (stereo).**
- 20 ms frames, derived from the live test: 2,781,120 frames ÷ 2,897 packets = 960 samples per packet over 57.94 s (TOBI:docs/recording.md:59-61).
- **Channel 0 = bone conduction (wearer); channel 1 = forward-facing microphone (other people).** This is owner-confirmed (TOBI:docs/recording.md:50-57; TOBI:docs/virtual-microphone.md:5-8).
- Packets can occasionally be mono. Check the Opus TOC stereo bit, `toc & 0x04` (TOBI:A/PromptAudio.kt:101).

**0x13 navigation page:** decoded as 48 kHz **mono** (TOBI:docs/android-notifications.md:69).

**Android decoding** uses `MediaCodec` `audio/opus` (TOBI:A/PromptAudio.kt:62-89):
- `csd-0` = 19-byte OpusHead: `"OpusHead",1,channels,preSkip=0,48000 LE,gain=0,mapping=0`.
- `csd-1` = 0 (pre-skip ns). `csd-2` = 80,000,000 (seek pre-roll ns).
- Output PCM16. Queue bound 16 packets; drop when full.
- Compute timestamps from the TOC (TOBI:A/PromptAudio.kt:167-176).

### 7.2 Voice assistant (0x0D), TURBO Static/iOS-tested

**Flow.**
1. Glasses send type 1 (wake).
2. Phone sends type 2 `{"rc":1}` to start the recorder; `{"rc":2}` stops it.
3. Glasses stream type 3 audio in field 4.
4. Phone sends VAD status as type 4 `{"rc":1 start | 2 stop | 3 timeout}`.
5. Phone shows the ASR text as type 5 `{"text","final":bool}`.
6. Phone sends the answer as type 32, JSON with `answer:{text,isFinal}`, `sid`/`uuid`, `query`, `timestamp` ms, plus fixed fields.
7. Phone sends response-complete as type 12 `{}`.
8. Phone exits with type 7 `{"rc":1}`.

Sources: TURBO:rayneo-protocol/Sources/RayNeoProtocol/AssistantRecorderPrototype.swift:7-10, AssistantVADPrototype.swift:7-11, AssistantTextPrototype.swift:9-24, AssistantAnswerPrototype.swift:9-35, AssistantResponseCompletePrototype.swift:6, AssistantExitPrototype.swift:6-8; core-probe/Sources/VoiceHandshakeProbe.swift:208-253.

**Audio: Opus 16 kHz mono**, packets that are multiples of 10 ms, up to 120 ms (TURBO:core-probe/Sources/NativeVoiceVAD.c:17,29-37). TURBO runs WebRTC VAD mode 2 on 10 ms frames.

**Enable voice wakeup** (Static): 0x0F type 16 `{"cmd":"set_ai_voice_wakeup","payload":{"data":"","mode":1,"value":0}}` (TURBO:LauncherControlPrototype.swift:8-27).

### 7.3 LifeLog / AlwaysOn (0x0D types 161-168), Verified on Mac

Source: TOBI:docs/lifelog-protocol.md; TOBI:S/DisplayController.swift:63-127,308-340; TOBI:S/main.swift:892-918.

**Arm it.** Send 0x0F type 20 `{"cmd":"life_log_switch","payload":{"value":1,"mode":0,"data":""}}`.
- The glasses reply type 21 `{"cmd":"life_log_switch_result","payload":{"value":…}}`.
- Non-zero values per TURBO: 1 = no mic permission, 2 = unfold the temples, 3 = low battery (TURBO:apps/RayNeoCompanion/Sources/AlwaysOnLocalProbe.swift:180-182).
- TURBO's enable variant also sends `life_log_guide` first and uses `data:"{\"delay\":2}"` (AlwaysOnLocalProbe.swift:8-13,118-119).

**Recording round.**
1. Glasses send **A1 (161)**, a wake caused by speech from the wearer or someone nearby.
2. Phone replies **A2 (162)** `{"taskId":"<uuid>","idleTimeoutSec":30}`, then **A8 (168)** `{"inRealtimePage":false}`.
3. Glasses stream **A3 (163)**, real-time audio (§7.4).
4. Glasses send **A5 (165)** `{"rc":0}` when the round ends.
5. Phone aborts with **A6 (166)**: TOBI sends `{"rc":1}`, TURBO sends `{"rc":2}`.

**Other messages.**
- **A4 (164)**: cached audio with `taskId` and `isFinish`. Not exercised.
- **A7 (167)**: phone queries the cache after a reconnect. Static only.

**Disarm.** Send `life_log_switch` with `value:0`.

### 7.4 LifeLog A3 audio format

- JSON: `{"mode":1,"frameCount":n,"vpuMask":bits,"vadMask":bits,"taskId":…}`.
- Field 4 = **concatenated 240-byte Opus frames**. Count = `min(31, frameCount, len/240)`. Decode each frame separately, never the batch as one packet (TOBI:docs/lifelog-protocol.md:58; TOBI:S/SpeechController.swift:108-113).
- Bit *i* of `vpuMask` / `vadMask` flags frame *i*.
- Format: **mono**, decoded at 48 kHz. 1,539 frames covered 92.34 s, which works out to about **60 ms per frame** (derived; TOBI:docs/lifelog-protocol.md:75).
- **Delivery is batched (observed 2026-11-17, current firmware, phone answering A8 `inRealtimePage:false`).** About every 20 s the glasses send ~11 A3 messages of 31 frames within about one second: ~20 s of audio per burst. Arrival time therefore only bounds the *end* of the received audio; the phone stamps audio with a stream clock (sample counts anchored to arrival, `StreamClock` in `ambient/CaptureSource.kt`) and treats only more than 30 s of unaccounted wall time as a gap. Whether `inRealtimePage:true` switches to real-time delivery is untested (hardware experiment pending).

### 7.5 Glasses recording service (0x0E), TURBO Static

**Handshake.**
- Phone sends type 14 `{"isAppForeground":true}`.
- Either side sends type 1 `{"uuid","action":1,"code":1}`; the reply uses `action:2`.

**Transfer.**
- Data arrives as type 3 `{"uuid","offset"}` with field 4 bytes.
- Completion is type 6 `{"completed":true}`.

**Controls.**

| Type | Meaning |
| --- | --- |
| 4 | stop |
| 8 | pause |
| 9 | resume |
| 11 | mark |
| 5 | reconnect state |
| 12 | sync list |

Source: TURBO:apps/RayNeoCompanion/Sources/CompanionDeviceFeatures.swift:92-193.

**Audio:** Opus 48 kHz stereo (TURBO:core-probe/Sources/NativeRecording.c:20).

---

## 8. Status, version, battery, settings, coexistence

**Battery and brightness (launcher).** Send 0x0F type 1 `{"cmd":"request_general_status","payload":{"data":"","mode":0,"value":0}}`. The reply is type 1 with `{"generalStatus":{"battery":0-100,"brightness":…}}`.
- This works over BLE right after auth (TURBO probe.py:195-197, :104-107 region; CompanionDeviceFeatures.swift:299-305,354-360).

**General settings.** Send 0x0F type 4 `{"cmd":"request_general_settings",…}`. The reply contains `generalSettings`, which includes `crownConfig`, `displayConfig` and `privacyConfig` (CompanionDeviceFeatures.swift:302,361).

**Settings commands** (Static, iOS). The wrapper is `{"cmd","payload":{"value","mode","data"}}`:

| Command | Type | Values |
| --- | --- | --- |
| `brightness_change` | 2 | 7-8 |
| `head_gestures` | 5 | value 1/0; mode 0/1 |
| `auto_lock_time` | 5 | 15 or 25 s |
| `crown_config` | 5 | `data` = `{"double":8 (todo)\|0,"longPress":n}` |
| `display_config` | 5 | `data` = `{"height":1\|3\|5,"distance":1\|2}` |

Replies arrive on types 3/6/17/19 (CompanionDeviceFeatures.swift:306-326,362-372).

**Firmware version.** Send MARS_FOTA 0x09 type 1 with empty JSON. The reply is `{"OsVersion":"Strix OS 1.0.4.12","OtaValidationState":"idle",…}` (TOBI:docs/firmware-analysis.md:7-20).
- Type 2 with empty JSON returns `{"BatteryLevel":n}`.
- Type 11 returns idle `{"Result":true}`.
- Treat types 1, 2 and 11 as read-only. Other types start an OTA; **never send them** (TURBO:android-addon/src/com/turboio/addon/OtaFrame.java:27; OtaSession.java:44-52).

**Device serial number:** in the command-17 reply, TLV 20 (§2.3).

**Coexistence with the official app** (`com.rayneo.venus.pub`).
- Only one client at a time. Disconnect or stop the official app first (TOBI:android/README.md:40; TURBO:docs/CONFIGURATION.md:59).
- Todo and weather writes are clobbered when the official app reconnects.
- RUCH's switcher grants or revokes the official app's `BLUETOOTH_CONNECT` permission via ADB `pm grant|revoke com.rayneo.venus.pub android.permission.BLUETOOTH_CONNECT`, plus `am force-stop`. A PC helper is needed (RUCH:scripts/glasses-transcribe.py:44-48; RUCH:docs/english-voice-fork.md:158-170).
- **Non-ADB fallback:** ask the user to revoke the official app's Nearby-devices permission manually.

---

## 9. Android implementation notes (TOBI v1.0.0, versionCode 19)

**Build.** `minSdk 33`, `targetSdk`/`compileSdk 36`, Compose + Material 3, OkHttp 4.12.0. No Bluetooth libraries (TOBI:android/app/build.gradle.kts:12-43).
- RUCH adds `com.github.gkonovalov.android-vad:webrtc:2.0.10`.

**Permissions** (TOBI:android/app/src/main/AndroidManifest.xml:3-14):
- `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_CONNECT`
- `INTERNET`, `ACCESS_NETWORK_STATE`
- `POST_NOTIFICATIONS`
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`
- `WAKE_LOCK`
- features `bluetooth` and `bluetooth_le`

Runtime requests are made in TOBI:A/MainActivity.kt:21-83.

**Foreground service.**
- `BridgeService`, declared with `foregroundServiceType="connectedDevice"`.
- `startForeground(1, …, FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)` runs only when connecting. Returns `START_NOT_STICKY`. The notification has a "Stop" action (TOBI:A/BridgeService.kt:92-103,278-289).

**Reconnect** (ConnectionSupervisor). On an unexpected loss after FULL, it rescans and reconnects to the same address or classic address.
- At most 3 attempts, with backoff 5/10/15 s (TOBI:A/ConnectionSupervisor.kt:25-30,81-88).
- It never deletes bonds.

**Threading.**
- GATT callbacks run on the main `Handler`.
- RFCOMM writes go through a single-thread executor.
- A dedicated reader thread posts frames to main.
- Each connection gets a generation counter, so stale callbacks can be ignored (TOBI:A/RayneoConnection.kt:35-37,346-388,442-461).

**Display coordinator.**
- A pure reducer: `GlassesCoordinator.reduce(state, event) → (state, effects)`.
- Close timeout 1.5 s; answer-restore timer 12 s (TOBI:A/BridgeService.kt:150-159).
- Teleprompter activity on 0x14 is treated as "external page took over" (TOBI:A/GlassesDisplay.kt:60-66).

**APIs used.**
- `BluetoothManager`/`BluetoothAdapter`, `BluetoothLeScanner`
- `BluetoothGatt` (Android 13 overloads of `writeCharacteristic`/`writeDescriptor` returning status codes)
- `createBond`, `createRfcommSocketToServiceRecord`
- `MediaCodec` (Opus)
- `NotificationListenerService` (Amap)

### TOBI Android file list (`A/`)

| File | Purpose |
| --- | --- |
| ProtocolCodec.kt | AA55 frame, CRC, ack, pairing TLVs, protobuf envelope encode/decode, stream decoder |
| RayneoConnection.kt | Scan, bond, GATT, auth, RFCOMM, reader, notification send |
| ConnectionSupervisor.kt | Link-state mapping and 3× backoff reconnect |
| GlassesDisplay.kt | 0x13/0x17 SID sessions, layout handshake, text writes, close, audio dispatch; `NavigationFrame` 20×5 renderer |
| GlassesCoordinator.kt | Pure state reducer choosing which page to show |
| GlassesState.kt | Intent/ownership/link/page/audio types, events, effects |
| BridgeService.kt | Foreground service, event queue, effect executor, timers |
| PromptAudio.kt | MediaCodec Opus decoder, levels, state |
| NavigationNotifications.kt | Amap notification listener and instruction parsing (`NavigationFeed`) |
| NotificationRecovery.kt | Listener rebind/recovery state |
| MobileAgent.kt | Chat-Completions tool-calling agent (phone model) |
| CodexClient.kt | WebSocket client to a PC Codex app-server |
| AppModel.kt / AppSettings.kt | UI view-model, service binding, Keystore-encrypted settings |
| MainActivity.kt, CompanionApp.kt, SetupScreens.kt, AssistantScreen.kt, GlassesPreview.kt, CompanionTheme.kt | Compose UI, onboarding, glasses preview, theme |

### RUCH additions

| File | Purpose |
| --- | --- |
| BleIdentityGate.kt | MTU/discovery race fix |
| GlassesVoice.kt / GlassesVoiceCommand.kt / SpeechEndpoint.kt | Channel-1 speech → PC Whisper → "Codex …" commands |
| AnswerReader.kt / AnswerSpeech.kt | Paged answers, TTS |
| AppSwitcher.kt | Official ↔ OpenRayneo switch via PC helper |
| CodexHistory.kt / RecentProjects.kt | Chat-history and project helpers |

RUCH also translates all strings to English.

---

## 10. Licensing

**TOBI is Apache-2.0** and safe to port with attribution (keep the copyright and SPDX headers; include the LICENSE).
- All 20 Android `.kt` files carry `// SPDX-License-Identifier: Apache-2.0`, as do the Swift sources and `Drivers/OpenRayneoAudio/OpenRayneoAudio.c`.
- `ThirdParty/opus/COPYING` is Xiph BSD, the Opus license.

**RUCH** inherits Apache-2.0.
- The new files (AnswerReader, AnswerSpeech, AppSwitcher, BleIdentityGate, CodexHistory, GlassesVoice, GlassesVoiceCommand, RecentProjects, SpeechEndpoint) lack SPDX headers but sit under the repo's Apache LICENSE.
- Its VAD dependency notices are in `assets/voice-detector-notices.txt`.

**TURBO is PolyForm Noncommercial 1.0.0. Do not copy its code** into a commercial or app-store product. Use it only as protocol documentation (TOBI:docs/turbo-io-firmware.md:82). Its vendored third-party code keeps its own licenses.

**Official APK / firmware:** none is redistributed in any of the three repos. The protocol constants are facts recovered by interoperability research.

---

## 11. Kotlin port checklist

1. `RayneoFrame` / `FrameCodec`: AA55 encode/decode, CRC16-XMODEM, flags/address/slice parsing, `55 AA` ack builder, `StreamDecoder` with resync. Port TOBI `ProtocolCodec.kt`.
2. `Envelope`: protobuf fields 1/2/3/4 (varint/bytes) encode and decode; `PairingTlv` container.
3. `BleScanner`: name / B81D / manufacturer `0x5716` filter, classic-MAC extraction.
4. `BleAuthSession`: GATT state machine with the `BleIdentityGate` (RUCH), MTU 512, serialized no-response writes, pairing-info read, commands 17→24→25, SHA-256 proof with `K`, TLV 25/26 rejection. Persistent `PhoneIdentity` (6 bytes, ANDROID_ID-derived).
5. `BondManager`: `createBond` plus `ACTION_BOND_STATE_CHANGED` receiver with timeouts.
6. `RfcommLink`: `createRfcommSocketToServiceRecord(9999…)`, `cancelDiscovery`, reader thread, single-writer executor, generation guard; BLE fallback transport.
7. `ConnectionSupervisor`: 3-attempt backoff, link-state flow.
8. `DisplaySession`: SID handling for 0x13 (type 7 or type 1+7) and 0x17 (type 1); type 5 text; type 3 close; detect glasses-initiated type 3.
9. `CharGrid`: 20×5 / 26×7 full-width buffer, ASCII→`+0xFEE0`, `U+3000`, first-frame prefix rule, block/half-block video renderer, 5-10 Hz frame pacer that skips stale frames.
10. `NotificationSender` (0x15 type 2); optional notification settings (types 17/18).
11. `Teleprompter`: 0x14 types 1-8 plus `FileTransfer` (0x11 descriptor, chunk request/response, completion, MD5, FNV-1a).
12. `TodoSync` (0x16 types 15/16/6) and `LauncherClient` (0x0F wrapper: weather, dashboard, general status/settings, LifeLog switch, `glass_preview` observer).
13. `SuggestionCard`: 0x15 types 33/34/35 for nod/shake confirmation.
14. `OpusUplink`: MediaCodec decoder (48 kHz stereo for 0x17, mono for 0x13 and LifeLog 240-byte frames, 16 kHz mono for 0x0D), `seq` de-duplication, bounded queue, channel split (0 = wearer, 1 = forward).
15. `LifeLogController`: A1→A2/A8, A3 batch splitter with VAD/VPU masks, A5/A6, switch on/off.
16. `DeviceInfo`: MARS_FOTA read-only types 1/2/11 only; never send other 0x09 types.
17. `GlassesService`: foreground service (`connectedDevice`), permissions (`BLUETOOTH_SCAN` neverForLocation, `BLUETOOTH_CONNECT`, `POST_NOTIFICATIONS`, FGS connected-device), official-app coexistence guidance.
