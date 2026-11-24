#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT_DIR/android"
DIST_DIR="$ROOT_DIR/dist"
VERSION_CODE_FILE="$ROOT_DIR/scripts/.version_code"

VERSION_NAME="${DROIDBRIDGE_VERSION_NAME:-1.0.1}"
KEYSTORE_PATH="${DROIDBRIDGE_KEYSTORE_PATH:-$HOME/.droidbridge/droidbridge-release.jks}"
KEY_ALIAS="${DROIDBRIDGE_KEY_ALIAS:-droidbridge}"
KEYSTORE_PASSWORD="${DROIDBRIDGE_KEYSTORE_PASSWORD:-}"
KEY_PASSWORD="${DROIDBRIDGE_KEY_PASSWORD:-}"

# Ensure keystore lives OUTSIDE the repository
case "$KEYSTORE_PATH" in
  "$ROOT_DIR"/*)
    echo "ERROR: DROIDBRIDGE_KEYSTORE_PATH must be outside the repository ($ROOT_DIR)." >&2
    exit 1
    ;;
esac

if [[ ! -f "$KEYSTORE_PATH" ]]; then
  echo "========================================================================"
  echo " Creating new release signing keystore at:"
  echo "   $KEYSTORE_PATH"
  echo ""
  echo " WARNING: BACK UP THIS KEYSTORE FILE AND ITS PASSWORD!"
  echo " Losing this keystore means future APK updates cannot install over"
  echo " the existing DroidBridge app without uninstalling & re-pairing."
  echo "========================================================================"
  mkdir -p "$(dirname "$KEYSTORE_PATH")"

  if [[ -z "$KEYSTORE_PASSWORD" ]]; then
    # Generate a random password and store it in ~/.droidbridge/keystore.pass (0600)
    PASS_FILE="$(dirname "$KEYSTORE_PATH")/keystore.pass"
    KEYSTORE_PASSWORD="$(openssl rand -base64 24 | tr -d '/+=' | head -c 24)"
    umask 077
    printf '%s\n' "$KEYSTORE_PASSWORD" > "$PASS_FILE"
    echo "Generated keystore password saved (mode 600) at: $PASS_FILE"
  fi
  KEY_PASSWORD="${KEY_PASSWORD:-$KEYSTORE_PASSWORD}"

  keytool -genkeypair \
    -v \
    -keystore "$KEYSTORE_PATH" \
    -storetype PKCS12 \
    -alias "$KEY_ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -storepass "$KEYSTORE_PASSWORD" \
    -keypass "$KEY_PASSWORD" \
    -dname "CN=DroidBridge, OU=Personal, O=DroidBridge, L=Local, ST=Local, C=US"
else
  if [[ -z "$KEYSTORE_PASSWORD" ]]; then
    PASS_FILE="$(dirname "$KEYSTORE_PATH")/keystore.pass"
    if [[ -f "$PASS_FILE" ]]; then
      KEYSTORE_PASSWORD="$(cat "$PASS_FILE")"
    else
      echo "ERROR: Set DROIDBRIDGE_KEYSTORE_PASSWORD for existing keystore $KEYSTORE_PATH" >&2
      exit 1
    fi
  fi
  KEY_PASSWORD="${KEY_PASSWORD:-$KEYSTORE_PASSWORD}"
fi

# Bump versionCode on every build
if [[ -n "${DROIDBRIDGE_VERSION_CODE:-}" ]]; then
  VERSION_CODE="$DROIDBRIDGE_VERSION_CODE"
else
  if [[ -f "$VERSION_CODE_FILE" ]]; then
    PREV_CODE="$(cat "$VERSION_CODE_FILE")"
    VERSION_CODE=$((PREV_CODE + 1))
  else
    VERSION_CODE=1
  fi
  printf '%s\n' "$VERSION_CODE" > "$VERSION_CODE_FILE"
fi

echo "Building DroidBridge Android release APK v${VERSION_NAME} (versionCode=${VERSION_CODE})..."

export DROIDBRIDGE_KEYSTORE_PATH="$KEYSTORE_PATH"
export DROIDBRIDGE_KEYSTORE_PASSWORD="$KEYSTORE_PASSWORD"
export DROIDBRIDGE_KEY_ALIAS="$KEY_ALIAS"
export DROIDBRIDGE_KEY_PASSWORD="$KEY_PASSWORD"
export DROIDBRIDGE_VERSION_CODE="$VERSION_CODE"
export DROIDBRIDGE_VERSION_NAME="$VERSION_NAME"

cd "$ANDROID_DIR"
./gradlew :app:assembleRelease

APK_SRC="$ANDROID_DIR/app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$APK_SRC" ]]; then
  echo "ERROR: Expected signed release APK at $APK_SRC" >&2
  exit 1
fi

mkdir -p "$DIST_DIR"
APK_OUT="$DIST_DIR/droidbridge-${VERSION_NAME}-vc${VERSION_CODE}.apk"
cp "$APK_SRC" "$APK_OUT"

SHA256="$(shasum -a 256 "$APK_OUT" | awk '{print $1}')"
printf '%s  %s\n' "$SHA256" "$(basename "$APK_OUT")" > "${APK_OUT}.sha256"

echo ""
echo "Signed release APK ready:"
echo "  APK:    $APK_OUT"
echo "  SHA256: $SHA256"
echo ""
echo "Next: run ./scripts/serve-apk.sh to install on your phone over Wi-Fi (no ADB needed)."
