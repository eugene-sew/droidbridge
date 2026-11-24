#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TELEMETRY_FILE="$HOME/Library/Application Support/DroidBridge/telemetry.jsonl"
OUT_DIR="$ROOT_DIR/docs/benchmarks"
mkdir -p "$OUT_DIR"

MODE="${1:-summarize}"
DURATION="${2:-30}"

if [[ "$MODE" == "clear" ]]; then
  rm -f "$TELEMETRY_FILE"
  echo "Cleared telemetry log: $TELEMETRY_FILE"
  exit 0
fi

if [[ "$MODE" == "record" ]]; then
  echo "========================================================================"
  echo " DroidBridge Live Benchmark Recorder (${DURATION}s)"
  echo " Hardware: $(sysctl -n hw.model) / $(sysctl -n machdep.cpu.brand_string)"
  echo " Telemetry source: $TELEMETRY_FILE"
  echo "========================================================================"
  START_MS="$(python3 -c 'import time; print(int(time.time()*1000))')"
  SYS_LOG="$OUT_DIR/mac_system_samples.jsonl"
  rm -f "$SYS_LOG"

  echo "Recording for ${DURATION}s... (Interact with your mirrored phone or switch Fast/Balanced/Sharp presets!)"
  for ((i=1; i<=DURATION; i++)); do
    TS_MS="$(python3 -c 'import time; print(int(time.time()*1000))')"
    DB_LINE="$(ps -ax -o pid=,pcpu=,rss=,comm= | awk '/DroidBridge$/ {print $1, $2, $3; exit}' || true)"
    WS_LINE="$(ps -ax -o pid=,pcpu=,rss=,comm= | awk '/WindowServer$/ {print $1, $2, $3; exit}' || true)"
    python3 - "$TS_MS" "$DB_LINE" "$WS_LINE" >> "$SYS_LOG" <<'PY'
import sys, json
ts = int(sys.argv[1])
def parse(s):
    parts = s.strip().split()
    if len(parts) >= 3:
        return {"pid": int(parts[0]), "cpu_pct": float(parts[1]), "rss_mb": round(int(parts[2]) / 1024.0, 2)}
    return None
print(json.dumps({"timestampMs": ts, "droidbridge_ps": parse(sys.argv[2]), "windowserver_ps": parse(sys.argv[3])}))
PY
    printf "\r  [%2d / %2d s] Sampling Mac + Android telemetry..." "$i" "$DURATION"
    sleep 1
  done
  echo ""
  echo "Sampling complete. Analyzing records since timestamp $START_MS..."
  MIN_TS="$START_MS"
else
  MIN_TS="0"
fi

if [[ ! -f "$TELEMETRY_FILE" ]]; then
  echo "ERROR: No telemetry found at $TELEMETRY_FILE." >&2
  echo "Make sure the updated DroidBridge Mac app and Android vc3 APK are running and connected." >&2
  exit 1
fi

python3 - "$TELEMETRY_FILE" "$MIN_TS" "$OUT_DIR/results.json" "$OUT_DIR/results.md" <<'PY'
import sys, json, statistics, subprocess
from collections import defaultdict

telemetry_path, min_ts_str, out_json, out_md = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
min_ts = int(min_ts_str)

records = []
with open(telemetry_path, "r", encoding="utf-8") as f:
    for line in f:
        line = line.strip()
        if not line:
            continue
        try:
            obj = json.loads(line)
            if obj.get("timestampMs", 0) >= min_ts:
                records.append(obj)
        except Exception:
            pass

if not records:
    print("ERROR: No telemetry records found in the selected window.", file=sys.stderr)
    sys.exit(1)

def stats(vals):
    clean = [v for v in vals if v is not None]
    if not clean:
        return None
    s = sorted(clean)
    p95_idx = min(len(s) - 1, int(round(0.95 * (len(s) - 1))))
    return {
        "n": len(s),
        "min": round(s[0], 2),
        "avg": round(statistics.mean(s), 2),
        "p95": round(s[p95_idx], 2),
        "max": round(s[-1], 2),
    }

by_phase = defaultdict(list)
for r in records:
    by_phase[r.get("phase", "unknown")].append(r)

# Hardware descriptors
mac_chip = subprocess.check_output(["sysctl", "-n", "machdep.cpu.brand_string"]).decode().strip()
mac_model = subprocess.check_output(["sysctl", "-n", "hw.model"]).decode().strip()
mac_mem_gb = round(int(subprocess.check_output(["sysctl", "-n", "hw.memsize"]).decode().strip()) / (1024**3))

android_meta = {}
for r in reversed(records):
    a = r.get("android")
    if a:
        android_meta = {
            "deviceModel": a.get("deviceModel"),
            "socModel": a.get("socModel"),
            "androidVersion": a.get("androidVersion"),
            "cpuCores": a.get("cpuCores"),
            "totalRamMb": a.get("totalRamMb"),
        }
        break

summary = {
    "macHardware": {"model": mac_model, "chip": mac_chip, "ramGb": mac_mem_gb},
    "androidHardware": android_meta,
    "totalSamples": len(records),
    "phases": {}
}

for phase, rows in sorted(by_phase.items()):
    mac_rows = [r["mac"] for r in rows if r.get("mac")]
    and_rows = [r["android"] for r in rows if r.get("android")]
    resolutions = sorted({f"{a.get('videoWidth',0)}x{a.get('videoHeight',0)}" for a in and_rows if a.get("videoWidth", 0) > 0})
    thermals_mac = sorted({m.get("thermalState", "unknown") for m in mac_rows})
    thermals_and = sorted({a.get("thermalStatus", "unknown") for a in and_rows})

    summary["phases"][phase] = {
        "samples": len(rows),
        "resolutions": resolutions,
        "mac": {
            "cpuPercent": stats([m.get("cpuPercent") for m in mac_rows]),
            "physFootprintMb": stats([m.get("physFootprintMb") for m in mac_rows]),
            "decodedFps": stats([m.get("decodedFps") for m in mac_rows]),
            "avgDecodeMs": stats([m.get("avgDecodeMs") for m in mac_rows if (m.get("avgDecodeMs") or 0) > 0]),
            "videoMbps": stats([m.get("videoMbps") for m in mac_rows]),
            "rttMs": stats([m.get("rttMs") for m in mac_rows]),
            "thermalStates": thermals_mac,
        },
        "android": {
            "appCpuPercentTotal": stats([a.get("appCpuPercent") for a in and_rows]),
            "appCpuPercentSingleCore": stats([a.get("appCpuCorePercent") for a in and_rows]),
            "appPssMb": stats([a.get("appPssMb") for a in and_rows]),
            "batteryPercent": stats([a.get("batteryPercent") for a in and_rows]),
            "batteryTempC": stats([a.get("batteryTempC") for a in and_rows]),
            "batteryCurrentMa": stats([a.get("batteryCurrentMa") for a in and_rows]),
            "batteryPowerW": stats([a.get("batteryPowerW") for a in and_rows]),
            "encodedFps": stats([a.get("encodedFps") for a in and_rows]),
            "videoBitrateKbps": stats([a.get("videoBitrateKbps") for a in and_rows]),
            "avgEncryptUs": stats([a.get("avgEncryptUs") for a in and_rows if (a.get("avgEncryptUs") or 0) > 0]),
            "avgSocketWriteMs": stats([a.get("avgSocketWriteMs") for a in and_rows if (a.get("avgSocketWriteMs") or 0) > 0]),
            "droppedFramesDelta": (and_rows[-1].get("droppedFrames", 0) - and_rows[0].get("droppedFrames", 0)) if len(and_rows) >= 2 else 0,
            "thermalStates": thermals_and,
        }
    }

with open(out_json, "w", encoding="utf-8") as f:
    json.dump(summary, f, indent=2)

def fmt(st, unit=""):
    if not st:
        return "—"
    return f"{st['avg']}{unit} (p95: {st['p95']}{unit})"

md = []
md.append("# DroidBridge Real-Hardware Benchmark Results\n")
md.append(f"- **Mac:** {mac_chip} (`{mac_model}`, {mac_mem_gb} GB RAM)")
md.append(f"- **Android:** {android_meta.get('deviceModel', 'Unknown')} — SoC: `{android_meta.get('socModel', 'Unknown')}`, {android_meta.get('androidVersion', '')}, {android_meta.get('cpuCores', '?')} cores, {android_meta.get('totalRamMb', '?')} MB RAM")
md.append(f"- **Total 1 Hz samples analyzed:** {len(records)}\n")

md.append("## macOS Hardware Impact (MacBook Air M2)\n")
md.append("| Phase | Samples | Mac CPU (%) | Mac RAM (MB) | Decoded FPS | VT Decode (ms) | Throughput (Mbps) | Control RTT (ms) | Thermal |")
md.append("|---|---:|---:|---:|---:|---:|---:|---:|---|")
for phase, d in summary["phases"].items():
    m = d["mac"]
    md.append(
        f"| `{phase}` | {d['samples']} | {fmt(m['cpuPercent'], '%')} | {fmt(m['physFootprintMb'], ' MB')} | "
        f"{fmt(m['decodedFps'])} | {fmt(m['avgDecodeMs'], ' ms')} | {fmt(m['videoMbps'], ' Mbps')} | "
        f"{fmt(m['rttMs'], ' ms')} | {', '.join(m['thermalStates'])} |"
    )

md.append("\n## Android Hardware Impact (Itel S26 Ultra)\n")
md.append("| Phase | Resolution | App CPU % (Total / 1-Core) | App RAM PSS (MB) | Battery Temp (°C) | Power Draw (W) | ChaCha20 Encrypt (µs) | Socket Write (ms) | Dropped Frames | Thermal |")
md.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---|")
for phase, d in summary["phases"].items():
    a = d["android"]
    res = ", ".join(d["resolutions"]) if d["resolutions"] else "—"
    cpu_str = "—"
    if a["appCpuPercentTotal"] and a["appCpuPercentSingleCore"]:
        cpu_str = f"{a['appCpuPercentTotal']['avg']}% / {a['appCpuPercentSingleCore']['avg']}%"
    md.append(
        f"| `{phase}` | {res} | {cpu_str} | {fmt(a['appPssMb'], ' MB')} | "
        f"{fmt(a['batteryTempC'], '°C')} | {fmt(a['batteryPowerW'], ' W')} | "
        f"{fmt(a['avgEncryptUs'], ' µs')} | {fmt(a['avgSocketWriteMs'], ' ms')} | "
        f"{a['droppedFramesDelta']} | {', '.join(a['thermalStates'])} |"
    )

report = "\n".join(md) + "\n"
with open(out_md, "w", encoding="utf-8") as f:
    f.write(report)

print(report)
print(f"Saved JSON report to: {out_json}")
print(f"Saved Markdown report to: {out_md}")
PY
