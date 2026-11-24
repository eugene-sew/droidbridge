#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST_DIR="$ROOT_DIR/dist"
PORT="${DROIDBRIDGE_SERVE_PORT:-8088}"

if [[ ! -d "$DIST_DIR" ]]; then
  echo "ERROR: $DIST_DIR does not exist. Run ./scripts/build-android.sh first." >&2
  exit 1
fi

LATEST_APK="$(ls -t "$DIST_DIR"/droidbridge-*.apk 2>/dev/null | head -n 1 || true)"
if [[ -z "$LATEST_APK" ]]; then
  echo "ERROR: No APK found in $DIST_DIR. Run ./scripts/build-android.sh first." >&2
  exit 1
fi

# Detect Mac's active Wi-Fi / LAN IPv4 address
LAN_IP="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)"
if [[ -z "$LAN_IP" ]]; then
  LAN_IP="$(python3 -c 'import socket; s=socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.connect(("8.8.8.8",80)); print(s.getsockname()[0]); s.close()' 2>/dev/null || echo "127.0.0.1")"
fi

APK_NAME="$(basename "$LATEST_APK")"
URL="http://${LAN_IP}:${PORT}/${APK_NAME}"

echo "========================================================================"
echo " DroidBridge LAN APK Server (No ADB Required)"
echo "========================================================================"
echo " File: $LATEST_APK"
echo " URL:  $URL"
echo ""
echo " On your Android phone (same Wi-Fi):"
echo "   1. Scan the QR code below or open the URL in Chrome."
echo "   2. Download the APK and tap it."
echo "   3. When prompted, allow 'Install unknown apps' for Chrome."
echo "========================================================================"
echo ""

if command -v qrencode >/dev/null 2>&1; then
  qrencode -t ansiutf8 "$URL"
elif python3 -c "import qrcode" >/dev/null 2>&1; then
  python3 - "$URL" <<'PY'
import sys, qrcode
qr = qrcode.QRCode(border=1)
qr.add_data(sys.argv[1])
qr.print_ascii(invert=True)
PY
else
  echo "(Tip: install 'qrencode' with 'brew install qrencode' to render a terminal QR code.)"
  echo "Open directly on phone: $URL"
fi

echo ""
echo "Serving $DIST_DIR on ${LAN_IP}:${PORT} — press Ctrl+C to stop."
exec python3 -m http.server "$PORT" --bind "$LAN_IP" --directory "$DIST_DIR"
