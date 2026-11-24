# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-11-24

First public release.

### Added

- QR pairing with single-use HMAC token and pinned X25519 static keys
- Per-connection 3-DH X25519 handshake, HKDF-SHA256 key derivation and ChaCha20-Poly1305 framing, verified against shared Kotlin/Swift test vectors
- Two-way notification sync into macOS Notification Center with inline reply, actions, dismiss, per-app mute and privacy mode
- Hardware H.264 screen mirroring (MediaProjection + MediaCodec, VBR, no B-frames) decoded with VideoToolbox into IOSurface-backed layers
- Remote control via Accessibility gestures, the DroidBridge Keyboard IME and keyboard shortcuts
- Mirror standby (`mirror.pause`) for prompt-free resume, Android 14+ entire-screen capture config
- Parallel reconnect over UDP discovery, mDNS and last-known IP
- Clipboard sync, live telemetry HUD and quality presets
- Build, LAN sideload, benchmark and screenshot scripts
