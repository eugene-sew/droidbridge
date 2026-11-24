# DroidBridge Wire Protocol Specification (v1)

This document defines the network transport, pairing handshake, per-connection cryptographic handshake, framing, and message formats for **DroidBridge** (Android ↔ macOS).

Both the Kotlin (Android, BouncyCastle) and Swift (macOS, CryptoKit) implementations MUST conform strictly to this specification and pass all test vectors in `protocol/test-vectors/`.

---

## 1. Transport & Discovery

### 1.1 Roles
- **macOS** acts as the TCP server (`NWListener`), listening on preferred port `47321` (falling back to any OS-assigned ephemeral port if unavailable).
- **Android** acts as the TCP client.

### 1.2 Service Discovery (Bonjour / mDNS + UDP LAN Probe)
- **mDNS / Bonjour:** Service type `_droidbridge._tcp` with TXT record `v="1"`, `id=<macDeviceId>`. Android holds `WifiManager.MulticastLock` while discovering via `NsdManager`.
- **UDP LAN Discovery (port `47322`):** To handle routers/hotspots that block or delay mDNS and to reconnect seamlessly when switching Wi-Fi networks without re-pairing:
  - Android broadcasts a UTF-8 datagram `DBP1|<macDeviceId>` to `255.255.255.255:47322` (and interface subnet broadcast).
  - macOS responds directly to the sender with `DBR1|<macDeviceId>|<tcpPort>`.
  - No secrets or keys are exchanged over UDP; authentication still happens strictly via the E2EE X25519 handshake on TCP.
- **Parallel Racing + Fallback:** Android races UDP LAN discovery, mDNS discovery, and the last-known `host:port` saved at pairing in parallel and connects to the first endpoint that completes the authenticated E2EE handshake.

### 1.3 Channels
Each paired phone maintains up to **two** concurrent TCP connections to the Mac:
1. **`control`** (`mode = 0x02`): JSON control, notifications, input, clipboard, heartbeats, status.
2. **`video`** (`mode = 0x03`): Binary H.264 video stream (and optional AAC audio stream).

Separating `control` and `video` onto distinct TCP sockets ensures large video frames never head-of-line block input events or notifications.

Only **one phone** may be actively connected at a time. Additional phones may remain paired in storage; if another paired phone connects while one is active, the server rejects or replaces according to active session state (default: reject while active unless previous connection is dead).

---

## 2. Encoding Conventions

- All multi-byte integers (`u16`, `u32`, `u64`) are **big-endian** (network byte order), unsigned.
- JSON payloads are **UTF-8** encoded.
- Binary keys/tokens in URIs and JSON are **Base64URL without padding** (`RFC 4648 §5`, `-` and `_`, no `=`).
- PNG icons (`pngBase64`, `largeIconPngBase64`) use **standard Base64** (`RFC 4648 §4`).

---

## 3. Connection Preamble

Every TCP connection begins with a 5-byte cleartext preamble sent by the client (Android):

```
[4 bytes] Magic: ASCII "DBR1" (0x44 0x42 0x52 0x31)
[1 byte ] Mode:
            0x01 = Pairing (one-time pairing exchange)
            0x02 = Control channel (E2EE)
            0x03 = Video channel (E2EE)
```

If the magic bytes do not match or the mode is unknown, the server closes the socket immediately.

---

## 4. One-Time Pairing (`mode = 0x01`)

### 4.1 QR Code URI
The Mac generates:
- Long-term static X25519 keypair (stored in macOS Keychain).
- Random 16-byte `pairToken`, valid for **120 seconds** (single-use).

QR code payload URI:
```
droidbridge://pair?h=<ip>&p=<port>&k=<macStaticPub>&t=<pairToken>&n=<macName>
```
- `h`: Mac LAN IPv4 address (URL-encoded)
- `p`: TCP port (decimal integer)
- `k`: Mac 32-byte static X25519 public key (base64url, no padding)
- `t`: 16-byte `pairToken` (base64url, no padding)
- `n`: Mac human-readable name (URL-encoded UTF-8)

### 4.2 Pairing Exchange
After sending `[4B "DBR1"][1B 0x01]`, the phone sends a single plaintext length-prefixed JSON frame:
```
[u32 length][UTF-8 JSON bytes]
```
Maximum pairing frame size: 65,536 bytes.

**Request (`pair.request`):**
```json
{
  "type": "pair.request",
  "deviceId": "550e8400-e29b-41d4-a716-446655440000",
  "deviceName": "Pixel 9",
  "phoneStaticPub": "<base64url-nopad 32-byte X25519 public key>",
  "mac": "<base64url-nopad 32-byte HMAC-SHA256>"
}
```
Where:
```
mac = HMAC-SHA256(key = raw_16_byte_pairToken, message = raw_32_byte_phoneStaticPub)
```

**Verification on Mac:**
1. Verify an active, non-expired `pairToken` exists.
2. Decode `phoneStaticPub` (must be exactly 32 bytes) and `mac` (must be exactly 32 bytes).
3. Compute `expectedMac = HMAC-SHA256(pairToken, phoneStaticPub)` and compare in constant time.
4. If valid: **burn the token immediately**, persist `{deviceId, deviceName, phoneStaticPub}` as trusted, and reply with `pair.ok`.

**Response (`pair.ok` or `pair.error`):**
```
[u32 length][UTF-8 JSON bytes]
```
Success:
```json
{
  "type": "pair.ok",
  "macDeviceId": "11111111-2222-3333-4444-555555555555",
  "macName": "Eugene's MacBook Pro"
}
```
Failure:
```json
{
  "type": "pair.error",
  "reason": "not_pairing | expired | invalid_request | bad_mac"
}
```
The server closes the pairing socket immediately after sending the response.

---

## 5. Per-Connection Cryptographic Handshake (`mode = 0x02` or `0x03`)

Primitives:
- **Key Agreement:** X25519 (RFC 7748)
- **KDF:** HKDF-SHA256 (RFC 5869)
- **AEAD:** ChaCha20-Poly1305 (RFC 8439)

### 5.1 Handshake Wire Format

1. **Client (Phone, Initiator) sends 69 bytes total:**
   ```
   [4 bytes]  "DBR1"
   [1 byte ]  mode (0x02 for "control", 0x03 for "video")
   [32 bytes] phoneStaticPub (raw X25519 public key)
   [32 bytes] phoneEphPub    (fresh ephemeral X25519 public key)
   ```

2. **Server (Mac, Responder) checks trust:**
   - Looks up `phoneStaticPub` in its trusted paired phones store.
   - If unknown (or unpaired): closes the TCP connection immediately without replying.

3. **Server (Mac, Responder) generates fresh ephemeral X25519 keypair and sends 37 bytes:**
   ```
   [4 bytes]  "DBR1"
   [1 byte ]  status (0x00 = OK)
   [32 bytes] macEphPub (fresh ephemeral X25519 public key)
   ```

### 5.2 Key Derivation (Canonical Initiator-First Ordering)

Let:
- `I_static`, `I_eph` = Phone (Initiator) static and ephemeral keypairs
- `R_static`, `R_eph` = Mac (Responder) static and ephemeral keypairs

Both sides compute three X25519 Diffie-Hellman shared secrets (each 32 bytes):
```
dh1 = X25519(I_eph_priv,    R_eph_pub)    == X25519(R_eph_priv,    I_eph_pub)
dh2 = X25519(I_static_priv, R_eph_pub)    == X25519(R_eph_priv,    I_static_pub)
dh3 = X25519(I_eph_priv,    R_static_pub) == X25519(R_static_priv, I_eph_pub)
```
**Validation:** If any of `dh1`, `dh2`, or `dh3` is all-zero bytes (low-order point), abort and close the connection immediately.

Concatenate in canonical order:
```
ikm  = dh1 || dh2 || dh3           (96 bytes)
salt = UTF8("droidbridge-v1")      (14 bytes)
info = UTF8(channelName)           ("control" for 0x02, "video" for 0x03)
okm  = HKDF-SHA256(ikm, salt, info, L = 64)
```
Split the 64-byte output key material:
```
keyPhoneToMac = okm[0..31]         (32 bytes)
keyMacToPhone = okm[32..63]        (32 bytes)
```

### 5.3 Encrypted Framing

All subsequent bytes on the socket are encrypted frames:

```
Plaintext (before encryption):
  [u8 messageType]
  [payload bytes...]

Wire format (after encryption):
  [u32 big-endian cipherLen]       (cipherLen = plaintext.length + 16)
  [ciphertext (plaintext.length bytes) || poly1305Tag (16 bytes)]
```

- **Nonce (12 bytes / 96 bits):**
  ```
  [0x00, 0x00, 0x00, 0x00] || uint64_be(counter)
  ```
- **Counters:** Each direction maintains an independent `uint64` counter starting at `0`, incremented by `1` for every frame sent/received in that direction.
- **AAD:** Empty (0 bytes).
- **Limits:**
  - Maximum `cipherLen`: `4,194,304` bytes (4 MiB) on `control`, `16,777,216` bytes (16 MiB) on `video`.
  - Minimum `cipherLen`: `17` bytes (1-byte message type + 16-byte Poly1305 tag).
  - Maximum counter value: `UINT64_MAX - 1` (close and reconnect before overflow).
- **Error Handling:** Any invalid length, AEAD authentication failure, or counter violation MUST immediately terminate the TCP connection.

### 5.4 Mandatory First Message
The very first encrypted frame sent by each side MUST be `0x01` (`hello`). If any other message type arrives first, or if `protocolVersion` is incompatible, close the connection.

---

## 6. Message Types

### 6.1 Control Channel Messages (UTF-8 JSON Payloads)

| Type | Name | Dir | Payload Schema |
|---|---|---|---|
| `0x01` | `hello` | Both | `{"deviceId": str, "deviceName": str, "appVersion": str, "protocolVersion": 1, "capabilities": [str]}` |
| `0x02` | `ping` | Both | `{"t": int64_ms}` |
| `0x03` | `pong` | Both | `{"t": int64_ms}` |
| `0x04` | `error` | Both | `{"code": str, "message": str}` |
| `0x10` | `notif.posted` | A→M | See §6.2 |
| `0x11` | `notif.removed` | A→M | `{"key": str}` |
| `0x12` | `notif.dismiss` | M→A | `{"key": str}` |
| `0x13` | `notif.reply` | M→A | `{"key": str, "text": str}` |
| `0x14` | `notif.action` | M→A | `{"key": str, "actionIndex": int}` |
| `0x15` | `app.icon` | A→M | `{"pkg": str, "appName": str, "pngBase64": str}` |
| `0x16` | `app.icon.request` | M→A | `{"pkg": str}` |
| `0x20` | `mirror.request` | M→A | `{}` |
| `0x21` | `mirror.state` | A→M | `{"state": "idle"\|"awaiting_consent"\|"standby"\|"streaming"\|"error", "reason": str?}` |
| `0x22` | `mirror.stop` | M→A | `{}` (fully terminates `MediaProjection`) |
| `0x23` | `video.keyframe.request` | M→A | `{}` |
| `0x24` | `app.launch` | M→A | `{"pkg": str}` |
| `0x25` | `mirror.pause` | M→A | `{}` (pauses encoder & closes video socket while keeping `MediaProjection` in zero-CPU standby for instant zero-prompt resume) |
| `0x26` | `mirror.quality` | M→A | `{"maxLongSide": int, "bitrateBps": int, "fps": int}` |
| `0x30` | `input.touch` | M→A | `{"phase": "down"\|"move"\|"up", "x": float, "y": float, "t": int64_ms}` (`x,y` ∈ `[0,1]`) |
| `0x31` | `input.scroll` | M→A | `{"dx": float, "dy": float, "x": float, "y": float}` |
| `0x32` | `input.text` | M→A | `{"text": str}` |
| `0x33` | `input.key` | M→A | `{"key": "enter"\|"backspace"\|"tab"\|"escape"\|"left"\|"right"\|"up"\|"down"}` |
| `0x34` | `input.global` | M→A | `{"action": "back"\|"home"\|"recents"\|"notifications"\|"quick_settings"\|"lock"}` |
| `0x40` | `clipboard` | Both | `{"text": str}` |
| `0x41` | `device.status` | A→M | `{"batteryLevel": int?, "isCharging": bool?}` |
| `0x42` | `device.telemetry` | A→M | `{"deviceModel": str, "socModel": str, "androidVersion": str, "cpuCores": int, "totalRamMb": int, "appCpuPercent": float, "appPssMb": float, "batteryPercent": int?, "batteryTempC": float?, "batteryCurrentMa": float?, "batteryPowerW": float?, "thermalStatus": str, "mirrorState": str, "videoWidth": int, "videoHeight": int, "encodedFps": float, "videoBitrateKbps": float, "avgEncryptUs": float, "avgSocketWriteMs": float, "droppedFrames": int}` |

**Heartbeat policy:**
- Send `ping` every **5 seconds** on the control channel.
- Respond immediately to `ping` with `pong` echoing `t`.
- If no frame is received from the peer for **15 seconds**, drop the connection (and Android reconnects with exponential backoff 1s → 30s).

### 6.2 `notif.posted` (`0x10`) Payload
```json
{
  "key": "0|com.google.android.apps.messaging|...",
  "pkg": "com.google.android.apps.messaging",
  "appName": "Messages",
  "title": "Kofi",
  "text": "Are we still on for 6?",
  "subText": null,
  "conversationTitle": null,
  "messages": [
    {"sender": "Kofi", "text": "Are we still on for 6?", "t": 1732300000000}
  ],
  "postTime": 1732300000000,
  "category": "msg",
  "isGroupConversation": false,
  "actions": [
    {"index": 0, "title": "Reply", "hasRemoteInput": true},
    {"index": 1, "title": "Mark as read", "hasRemoteInput": false}
  ],
  "largeIconPngBase64": "<optional base64 PNG, <= 96px>"
}
```

### 6.3 Video Channel Messages (Binary Payloads)

#### `0x80` `video.config` (A→M)
```
[u16 width]           // encoded video width in pixels
[u16 height]          // encoded video height in pixels
[u16 rotation]        // display rotation degrees: 0, 90, 180, 270
[u32 spsLen]          // length of raw H.264 SPS NAL unit (without start code)
[spsLen bytes: sps]   // raw SPS NAL bytes
[u32 ppsLen]          // length of raw H.264 PPS NAL unit (without start code)
[ppsLen bytes: pps]   // raw PPS NAL bytes
```

#### `0x81` `video.frame` (A→M)
```
[u64 ptsMicros]       // presentation timestamp in microseconds
[u8  flags]           // bit 0 (0x01) = keyframe (IDR), bits 1..7 reserved (0)
[N bytes: nalData]    // Annex-B formatted H.264 NAL units (0x00000001 or 0x000001 start codes)
```

#### `0x82` `audio.config` (A→M, Phase 6 optional)
```
[u32 sampleRate]      // e.g. 48000
[u8  channels]        // 1 or 2
[u16 ascLen]          // AudioSpecificConfig length
[ascLen bytes: asc]   // AAC AudioSpecificConfig (esds / csd-0)
```

#### `0x83` `audio.frame` (A→M, Phase 6 optional)
```
[u64 ptsMicros]       // presentation timestamp in microseconds
[N bytes: aacData]    // raw AAC access unit
```
