# Contributing to DroidBridge

Thanks for your interest in DroidBridge. Bug reports, device test results, docs fixes and code are all welcome.

## Ground rules

DroidBridge has a few hard constraints. Pull requests that break them can't be merged:

1. **No ADB, root, developer mode or hidden/private APIs** on Android. Public SDK APIs and user-granted permissions only.
2. **No Google Play Services dependency.** The APK must work sideloaded on any stock phone.
3. **Everything after pairing stays end-to-end encrypted.** No plaintext fallbacks, no cloud relay without E2EE.
4. **Wire format changes go through the spec.** Update [`protocol/PROTOCOL.md`](protocol/PROTOCOL.md) and the shared test vectors in [`protocol/test-vectors/`](protocol/test-vectors/) in the same PR, and keep Kotlin and Swift passing them byte-for-byte.

## Project layout

| Path | What lives there |
|---|---|
| `android/` | Kotlin + Jetpack Compose Android app (`minSdk 29`, `targetSdk 35`) |
| `macos/` | Swift / SwiftUI macOS app, generated with [XcodeGen](https://github.com/yonaskolb/XcodeGen) from `project.yml` |
| `protocol/` | Wire protocol spec and cross-platform crypto/framing test vectors |
| `scripts/` | Build, sideload, benchmark and screenshot scripts |
| `docs/` | Architecture write-up, benchmarks, screenshots and branding |

## Development setup

See [Prerequisites](README.md#prerequisites) and [Quick Start](README.md#quick-start-under-15-minutes-no-usb-cable) in the README. In short:

```bash
brew install xcodegen
./scripts/build-macos.sh       # macOS app -> dist/DroidBridge.app
./scripts/build-android.sh     # signed APK -> dist/ (keystore is created outside the repo)
```

`macos/DroidBridge.xcodeproj` is generated. Edit `macos/project.yml`, not the project file.

## Running tests

Both suites must pass before you open a PR:

```bash
(cd android && ./gradlew :app:testDebugUnitTest)
(cd macos && xcodegen generate && xcodebuild test -project DroidBridge.xcodeproj \
  -scheme DroidBridge -destination 'platform=macOS' CODE_SIGN_IDENTITY="-")
```

CI runs the same commands on every push and pull request.

## Pull requests

- Keep PRs focused: one fix or feature per PR.
- Add or update tests for behaviour changes. Crypto and framing changes need new test vectors.
- If a change touches performance (encoder, decoder, crypto, socket path), include before/after numbers from `./scripts/benchmark.sh` and the phone model you tested on.
- Describe how you tested on real hardware: phone model, Android version, Mac model, macOS version.
- Never commit keystores, `keystore.pass`, `local.properties` or anything from `dist/`.

## Device reports

Testing on new hardware is one of the most useful contributions. Open an issue with the **Device report** details from the bug template (phone, Android version, OEM skin, what worked and what didn't), especially for OEMs that aggressively kill background services.

## Security issues

Please don't open public issues for vulnerabilities. See [SECURITY.md](SECURITY.md).

## Code of conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md). By taking part you agree to uphold it.
