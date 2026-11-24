#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MACOS_DIR="$ROOT_DIR/macos"
DIST_DIR="$ROOT_DIR/dist"
BUILD_DIR="$MACOS_DIR/build"

cd "$MACOS_DIR"

if ! command -v xcodegen >/dev/null 2>&1; then
  echo "ERROR: xcodegen is required. Install it with: brew install xcodegen" >&2
  exit 1
fi

echo "Generating Xcode project with XcodeGen..."
xcodegen generate

SIGN_ARGS=()
if [[ -n "${DEVELOPMENT_TEAM:-}" ]]; then
  echo "Building DroidBridge.app (Release) with Personal/Developer Team: $DEVELOPMENT_TEAM"
  SIGN_ARGS=(
    "DEVELOPMENT_TEAM=$DEVELOPMENT_TEAM"
    "CODE_SIGN_STYLE=Automatic"
    "CODE_SIGN_IDENTITY=Apple Development"
  )
else
  echo "Building DroidBridge.app (Release) with ad-hoc 'Sign to Run Locally'..."
  SIGN_ARGS=(
    "CODE_SIGN_IDENTITY=-"
    "CODE_SIGNING_REQUIRED=NO"
  )
fi

xcodebuild \
  -project DroidBridge.xcodeproj \
  -scheme DroidBridge \
  -configuration Release \
  -derivedDataPath "$BUILD_DIR" \
  "${SIGN_ARGS[@]}" \
  build

APP_SRC="$BUILD_DIR/Build/Products/Release/DroidBridge.app"
if [[ ! -d "$APP_SRC" ]]; then
  echo "ERROR: Expected built app at $APP_SRC" >&2
  exit 1
fi

mkdir -p "$DIST_DIR"
rm -rf "$DIST_DIR/DroidBridge.app"
cp -R "$APP_SRC" "$DIST_DIR/DroidBridge.app"

echo ""
echo "Built macOS app:"
echo "  $DIST_DIR/DroidBridge.app"
echo ""
echo "Install:"
echo "  cp -R \"$DIST_DIR/DroidBridge.app\" /Applications/"
echo "  Right-click DroidBridge in /Applications -> Open (first launch Gatekeeper approval)."
