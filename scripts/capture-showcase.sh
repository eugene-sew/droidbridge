#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SHOT_DIR="$ROOT_DIR/docs/screenshots"
mkdir -p "$SHOT_DIR"

MODE="${1:-all}" # all | burst

# Discover visible DroidBridge window IDs and titles using CoreGraphics
WINDOWS="$(swift - <<'SWIFT'
import Foundation
import CoreGraphics

let opts: CGWindowListOption = [.optionOnScreenOnly, .excludeDesktopElements]
guard let list = CGWindowListCopyWindowInfo(opts, kCGNullWindowID) as? [[String: Any]] else {
    exit(0)
}
for w in list {
    guard let owner = w[kCGWindowOwnerName as String] as? String, owner == "DroidBridge" else { continue }
    guard let wid = w[kCGWindowNumber as String] as? Int else { continue }
    let bounds = w[kCGWindowBounds as String] as? [String: Any] ?? [:]
    let width = (bounds["Width"] as? Double) ?? 0
    let height = (bounds["Height"] as? Double) ?? 0
    if width < 100 || height < 100 { continue }
    let title = (w[kCGWindowName as String] as? String) ?? "window"
    let cleanTitle = title.replacingOccurrences(of: "|", with: " ")
    print("\(wid)|\(cleanTitle)|\(Int(width))x\(Int(height))")
}
SWIFT
)"

if [[ -z "$WINDOWS" ]]; then
  echo "No visible DroidBridge windows found on screen."
  echo "Open the DroidBridge Mirror window, Pairing window, or Settings window and re-run."
  exit 1
fi

echo "Found DroidBridge windows:"
echo "$WINDOWS"
echo ""

capture_window() {
  local wid="$1"
  local out="$2"
  screencapture -l "$wid" -x "$out"
  echo "  Saved: $out"
}

if [[ "$MODE" == "burst" ]]; then
  # Pick the mirror window (first window that isn't Settings or Pair)
  MIRROR_WID="$(echo "$WINDOWS" | grep -viE "Settings|Pair" | head -n 1 | cut -d'|' -f1 || true)"
  if [[ -z "$MIRROR_WID" ]]; then
    echo "ERROR: Mirror window is not open." >&2
    exit 1
  fi
  echo "Capturing 6-frame motion burst of mirror window (ID=$MIRROR_WID)..."
  for i in 1 2 3 4 5 6; do
    capture_window "$MIRROR_WID" "$SHOT_DIR/mirror-motion-0${i}.png"
    sleep 0.35
  done
  echo "Motion burst complete in $SHOT_DIR/"
  exit 0
fi

IDX=1
while IFS='|' read -r wid title dims; do
  [[ -z "$wid" ]] && continue
  if [[ "$title" == *"Settings"* ]]; then
    NAME="settings-window.png"
  elif [[ "$title" == *"Pair"* ]]; then
    NAME="pairing-window.png"
  else
    NAME="mirror-window-${IDX}.png"
    IDX=$((IDX + 1))
  fi
  capture_window "$wid" "$SHOT_DIR/$NAME"
done <<< "$WINDOWS"

echo ""
echo "Screenshots saved to $SHOT_DIR/"
ls -lh "$SHOT_DIR/"
