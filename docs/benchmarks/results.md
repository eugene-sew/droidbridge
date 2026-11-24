# DroidBridge Real-Hardware Benchmark Results

- **Mac:** Apple M2 (`Mac14,2`, 16 GB RAM)
- **Android:** ITEL itel S688LN — SoC: `Spreadtrum UMS9360`, Android 15 (API 35), 8 cores, 7707 MB RAM
- **Total 1 Hz samples analyzed:** 501

## macOS Hardware Impact (MacBook Air M2)

| Phase | Samples | Mac CPU (%) | Mac RAM (MB) | Decoded FPS | VT Decode (ms) | Throughput (Mbps) | Control RTT (ms) | Thermal |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| `awaiting_consent` | 2 | 2.13% (p95: 2.68%) | 43.06 MB (p95: 43.06 MB) | 0 (p95: 0) | — | 0 Mbps (p95: 0 Mbps) | 20.66 ms (p95: 20.66 ms) | nominal |
| `idle` | 132 | 2.57% (p95: 5.28%) | 43.19 MB (p95: 45.66 MB) | 0 (p95: 0) | — | 0 Mbps (p95: 0 Mbps) | 25.32 ms (p95: 92.43 ms) | nominal |
| `streaming_balanced` | 367 | 4.18% (p95: 17.04%) | 52.93 MB (p95: 60.47 MB) | 6.45 (p95: 35) | 3.74 ms (p95: 4.94 ms) | 0.31 Mbps (p95: 1.51 Mbps) | 21.69 ms (p95: 87.53 ms) | nominal |

## Android Hardware Impact (Itel S26 Ultra)

| Phase | Resolution | App CPU % (Total / 1-Core) | App RAM PSS (MB) | Battery Temp (°C) | Power Draw (W) | ChaCha20 Encrypt (µs) | Socket Write (ms) | Dropped Frames | Thermal |
|---|---|---:|---:|---:|---:|---:|---:|---:|---|
| `awaiting_consent` | — | 1.83% / 14.6% | 132.33 MB (p95: 137.17 MB) | 29.7°C (p95: 29.7°C) | 2.3 W (p95: 3.41 W) | — | — | 0 | none |
| `idle` | — | 0.5% / 4.01% | 60.47 MB (p95: 81.98 MB) | 29.73°C (p95: 29.9°C) | 1.57 W (p95: 2.46 W) | — | — | 0 | none |
| `streaming_balanced` | 576x1280 | 1.06% / 8.48% | 50.28 MB (p95: 61.05 MB) | 29.42°C (p95: 29.9°C) | 1.07 W (p95: 2.19 W) | 359.32 µs (p95: 464.2 µs) | 0.45 ms (p95: 0.61 ms) | 0 | none |
