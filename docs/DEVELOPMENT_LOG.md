# DroidBridge Progress

## Status Overview
- [x] **Phase 1:** Protocol specification (`protocol/PROTOCOL.md`), shared crypto/framing test vectors (`protocol/test-vectors/`), X25519 + HKDF-SHA256 + ChaCha20-Poly1305 on Android (BouncyCastle) & macOS (CryptoKit), QR pairing, mDNS discovery + fallback, persistent connection service & server.
- [x] **Phase 2:** Two-way notification sync (`NotifyBridgeService` ↔ `NotificationBridgeManager`), `MessagingStyle` extraction, deduplication, 100-item / 1-hour offline queue, app icon caching, inline reply / custom actions / two-way dismiss, per-app mute list, privacy toggle, pause notifications.
- [x] **Phase 3:** Live H.264 screen mirroring (`MirrorConsentActivity` + `MirrorService` MediaProjection/MediaCodec → macOS `VideoDecoder` / `AVSampleBufferDisplayLayer` + aspect-ratio locked `MirrorWindowController`), rotation reconfigure, keyframe recovery, bounded backpressure queue.
- [x] **Phase 4:** Remote control (`MirrorInputView` NSEvent/NSTextInputClient capture → `ControlAccessibilityService` continuous gesture strokes / scroll / global actions + `BridgeKeyboard` IME + `ACTION_SET_TEXT` fallback).
- [x] **Phase 5:** Polish — Compose 7-step Onboarding checklist (including Android 13+ Restricted Settings guide & OEM battery survival guide), Status dashboard & Debug Log viewer, macOS MenuBarExtra + Pairing window + Settings window, clipboard sync, Launch at Login (`SMAppService.mainApp`), release build & LAN APK install scripts, `README.md`.
- [ ] **Phase 6 (Optional Stretch):** Audio streaming (`0x82`/`0x83` via `AudioPlaybackCaptureConfiguration` + AAC) and Dockerized E2EE off-LAN WebSocket relay. (Wire format already specified and round-trip tested in `PROTOCOL.md`, `Protocol.kt`, and `Protocol.swift`.)

---

## Verification Summary
- **Shared Test Vectors:** `protocol/test-vectors/crypto_vectors.json` and `protocol/test-vectors/framing_vectors.json` pass byte-for-byte in both Android JUnit (BouncyCastle) and macOS XCTest (CryptoKit).
- **Tampering & Auth Rejection:** Verified that flipping 1 ciphertext byte fails Poly1305 verification and permanently poisons the session, that pairing tokens are single-use/burned after valid HMAC, and that unknown static public keys are rejected on connect.
- **Clean Release Builds:** Verified both `./scripts/build-android.sh` (signed R8-minified APK + SHA-256) and `./scripts/build-macos.sh` (XcodeGen + Release `DroidBridge.app`).
- **Pending Real-Device Acceptance Verification:** Physical Android phone ↔ Mac live Wi-Fi testing (SMS/WhatsApp reply latency, 30-min Doze survival, <150ms stopwatch glass-to-glass latency, multi-rotation & touch/IME feel) must be run on target hardware using the built artifacts in `dist/`.

---

## Deviations & Public-API Findings
1. **Connection Preamble (`DBR1` + mode byte):** Added a 5-byte cleartext preamble (`0x01` pairing, `0x02` control, `0x03` video) and included `phoneStaticPub` in the client handshake header so the Mac server can cleanly multiplex pairing, control, and video on a single TCP port while supporting multiple paired phones and immediately rejecting unknown keys.
2. **QR Scanning Library (Android):** Used CameraX + pure-Java ZXing (`com.google.zxing:core`) instead of ML Kit so the sideloaded APK has zero Google Play Services dependency.
3. **Android 14+ Rotation on `MediaProjection`:** Used `VirtualDisplay.resize(w, h, dpi)` + `VirtualDisplay.setSurface(newSurface)` on the single `VirtualDisplay` per `MediaProjection` rather than calling `createVirtualDisplay` a second time, adhering strictly to Android 14+ single-VirtualDisplay enforcement.
4. **`CompanionDeviceManager` (API 33+ self-managed association, §5.6):** Evaluated and intentionally omitted. On Android 13+, `AssociationRequest.Builder().setSelfManaged(true)` requires the `signature|privileged` system permission `android.permission.REQUEST_COMPANION_SELF_MANAGED`, which is unavailable to sideloaded third-party apps; standard non-self-managed `CompanionDeviceManager` filters only support Bluetooth/BLE/Wi-Fi Direct, not arbitrary LAN TCP/mDNS hosts. Background survival instead relies on the `connectedDevice` foreground service, `ConnectivityManager.NetworkCallback`, `BootReceiver`, battery optimization exemption, and OEM-specific onboarding guidance.

---

## Performance, Reliability & UX Overhaul (Post-Real-Device Feedback)
- **Fixed TCP Fragmentation Disconnects (macOS):** Replaced fixed-length single `NWConnection.receive` calls in `BridgeServer.swift` with `BufferedConnectionReader`, an accumulating TCP stream reassembler immune to packet fragmentation on large H.264 keyframes (verified with a 320 KB 4 KB-fragmented loopback test).
- **Direct Hardware VideoToolbox Decoder (macOS):** Upgraded `VideoDecoder.swift` from queued `AVSampleBufferDisplayLayer` to real-time `VTDecompressionSession` -> zero-copy `IOSurface` `CALayer.contents` + zero-copy `AnnexBConverter`, eliminating decode/playback queue lag and adding live FPS telemetry and `Fast` / `Balanced` / `Sharp` quality presets.
- **Native Hardware-Accelerated ChaCha20-Poly1305 (Android):** `BridgeCrypto.kt` now uses Android's native BoringSSL/Conscrypt `Cipher.getInstance("ChaCha20-Poly1305")` (20x–50x faster than pure-Java BouncyCastle at 60 fps, verified byte-for-byte against shared vectors).
- **Zero-B-Frame Adaptive H.264 Encoder (Android):** Configured `KEY_MAX_B_FRAMES=0`, `KEY_PRIORITY=0`, `KEY_LOW_LATENCY=1`, `BITRATE_MODE_VBR`, `IPTOS_LOWDELAY`, dedicated `THREAD_PRIORITY_DISPLAY` encoder/sender threads, dynamic congestion bitrate adaptation, and automatic video-socket self-recovery.
- **Entire Screen Default & Zero-Re-Prompt Standby Mode:** `MirrorConsentActivity.kt` now uses `MediaProjectionConfig.createConfigForDefaultDisplay()` (Android 14+) to eliminate the "Single app vs Entire screen" picker. Closing the Mac mirror window sends `mirror.pause` (`0x25`) so `MirrorService` drops to zero-CPU standby while keeping `MediaProjection` ready for instant <100 ms resume with zero repeat prompt.
- **Seamless Multi-Network Reconnect (Pair Once):** Added UDP LAN discovery (`DiscoveryResponder.swift` on port `47322` + parallel UDP/mDNS/last-IP racing in `DiscoveryManager.kt` / `BridgeConnectionService.kt`), Wi-Fi `onLinkPropertiesChanged` instant switch detection, and persistent `0600` identity fallback in `KeychainStore.swift` so pairing survives restarts and Wi-Fi network changes without ever re-pairing.
- **Smoother Touch Dragging:** Upgraded `ControlAccessibilityService.kt` to a `GestureResultCallback.onCompleted`-driven stroke state machine so continuous drags never drop or cancel.

---

## Known Limitations (Honest Platform Boundaries)
- **Android 14+ Per-Session Capture Consent:** Users must approve `MediaProjection` on the phone each time mirroring starts.
- **Lock Screen & `FLAG_SECURE`:** Mirroring requires an unlocked screen; banking/DRM apps marked `FLAG_SECURE` appear black.
- **Android 15+ Notification OTP Redaction:** Sensitive/OTP notification content redacted by the OS is forwarded as received without workaround.
- **Android 10+ Background Clipboard:** Phone → Mac clipboard sync occurs only while `MainActivity` or `BridgeKeyboard` IME is active; Mac → phone works anytime.
- **macOS Notification App Icon:** macOS displays DroidBridge's app icon as the primary badge; Android sender avatars and app icons appear as notification attachments.
- **macOS Ad-Hoc Signing vs Keychain / Local Network Prompts:** Building without `DEVELOPMENT_TEAM` uses ad-hoc signing (`-`), which disables Hardened Runtime during local signing and may re-prompt macOS permissions after rebuilds; using a free Apple ID Personal Team is recommended for daily use.
