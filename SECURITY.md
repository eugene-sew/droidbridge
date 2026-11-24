# Security Policy

DroidBridge gives a Mac remote control of an Android phone, so security bugs matter more than most. Thank you for reporting them responsibly.

## Reporting a vulnerability

**Please do not open a public issue.** Report privately through GitHub:

1. Go to the repository's **Security** tab.
2. Click **Report a vulnerability**.
3. Include the affected component (Android app, macOS app, or protocol), steps to reproduce, and the impact you expect.

You should get an acknowledgement within 7 days. Once a fix is ready we'll coordinate a disclosure date with you and credit you in the release notes unless you'd rather stay anonymous.

## Supported versions

Only the latest release on `main` receives security fixes.

## Scope

In scope:

- The pairing flow (QR token, HMAC, key pinning)
- The 3-DH X25519 handshake, HKDF key derivation and ChaCha20-Poly1305 framing
- Anything that lets an unpaired device connect, read notifications, see the screen or inject input
- Local storage of identity keys on either device
- The UDP discovery responder and mDNS advertisement

Out of scope:

- Attacks that need an already-compromised phone or Mac (root, malware with Accessibility access, etc.)
- Android or macOS platform behaviour such as `FLAG_SECURE`, OTP redaction or clipboard restrictions
- Denial of service on the local network

## Design references

The full wire and crypto spec is in [`protocol/PROTOCOL.md`](protocol/PROTOCOL.md). Shared test vectors live in [`protocol/test-vectors/`](protocol/test-vectors/).
