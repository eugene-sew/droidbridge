import Foundation
import Darwin

public struct MacTelemetrySnapshot: Codable, Equatable {
    public let cpuPercent: Double
    public let physFootprintMb: Double
    public let thermalState: String
    public let rttMs: Double?
    public let decodedFps: Int
    public let avgDecodeMs: Double
    public let videoMbps: Double
    public let qualityPreset: String
}

public struct CombinedTelemetryRecord: Codable {
    public let timestampMs: Int64
    public let phase: String
    public let mac: MacTelemetrySnapshot
    public let android: DeviceTelemetryPayload?
}

/// Samples MacBook process CPU, physical memory footprint (`phys_footprint`), thermal state,
/// VideoToolbox decode timing, network RTT, and merges incoming Android `device.telemetry`
/// into `~/Library/Application Support/DroidBridge/telemetry.jsonl` at 1 Hz.
public final class TelemetryRecorder: ObservableObject {
    @Published public private(set) var latestMac = MacTelemetrySnapshot(
        cpuPercent: 0,
        physFootprintMb: 0,
        thermalState: "nominal",
        rttMs: nil,
        decodedFps: 0,
        avgDecodeMs: 0,
        videoMbps: 0,
        qualityPreset: "Balanced"
    )
    @Published public private(set) var latestAndroid: DeviceTelemetryPayload?

    public let logFileURL: URL
    private let lock = NSLock()
    private var lastCpuSeconds: Double = 0
    private var lastWallSeconds: CFAbsoluteTime = CFAbsoluteTimeGetCurrent()
    private var latestRttMs: Double?
    private var bytesReceivedWindow: Int64 = 0
    private var decodeMsSum: Double = 0
    private var decodeCount: Int = 0
    private var lastBandwidthTime: CFAbsoluteTime = CFAbsoluteTimeGetCurrent()
    private var currentQualityName: String = "Balanced"
    private var currentFps: Int = 0
    private var timer: Timer?

    public init() {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        let dir = appSupport.appendingPathComponent("DroidBridge", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        self.logFileURL = dir.appendingPathComponent("telemetry.jsonl")
        self.lastCpuSeconds = Self.readProcessCpuSeconds()
    }

    public func start() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            self?.tick()
        }
    }

    public func stop() {
        timer?.invalidate()
        timer = nil
    }

    public func recordRtt(ms: Double) {
        lock.lock()
        latestRttMs = ms
        lock.unlock()
    }

    public func recordVideoFrameDecoded(wireBytes: Int, decodeMs: Double, fps: Int) {
        lock.lock()
        bytesReceivedWindow += Int64(wireBytes)
        decodeMsSum += decodeMs
        decodeCount += 1
        currentFps = fps
        lock.unlock()
    }

    public func setQualityPreset(_ name: String) {
        lock.lock()
        currentQualityName = name
        lock.unlock()
    }

    public func handleAndroidTelemetry(_ payload: DeviceTelemetryPayload) {
        DispatchQueue.main.async {
            self.latestAndroid = payload
        }
    }

    public func clearAndroidTelemetry() {
        DispatchQueue.main.async {
            self.latestAndroid = nil
        }
    }

    private func tick() {
        let nowWall = CFAbsoluteTimeGetCurrent()
        let nowCpu = Self.readProcessCpuSeconds()
        let dt = max(nowWall - lastWallSeconds, 0.001)
        let dCpu = max(nowCpu - lastCpuSeconds, 0)
        lastWallSeconds = nowWall
        lastCpuSeconds = nowCpu

        let cpuPct = (dCpu / dt) * 100.0
        let memMb = Self.readPhysicalFootprintMb()
        let thermal: String
        switch ProcessInfo.processInfo.thermalState {
        case .nominal: thermal = "nominal"
        case .fair: thermal = "fair"
        case .serious: thermal = "serious"
        case .critical: thermal = "critical"
        @unknown default: thermal = "unknown"
        }

        lock.lock()
        let bwDt = max(nowWall - lastBandwidthTime, 0.001)
        let mbps = (Double(bytesReceivedWindow) * 8.0) / 1_000_000.0 / bwDt
        let avgDecMs = decodeCount > 0 ? (decodeMsSum / Double(decodeCount)) : 0.0
        let rtt = latestRttMs
        let fps = decodeCount > 0 ? currentFps : 0
        let quality = currentQualityName
        bytesReceivedWindow = 0
        decodeMsSum = 0
        decodeCount = 0
        lastBandwidthTime = nowWall
        lock.unlock()

        let macSnap = MacTelemetrySnapshot(
            cpuPercent: cpuPct,
            physFootprintMb: memMb,
            thermalState: thermal,
            rttMs: rtt,
            decodedFps: fps,
            avgDecodeMs: avgDecMs,
            videoMbps: mbps,
            qualityPreset: quality
        )

        self.latestMac = macSnap

        // Only append to JSONL while a phone is connected
        guard let androidSnap = self.latestAndroid else { return }
        let phase = androidSnap.mirrorState == "streaming"
            ? "streaming_\(quality.lowercased())"
            : androidSnap.mirrorState

        let record = CombinedTelemetryRecord(
            timestampMs: Int64(Date().timeIntervalSince1970 * 1000),
            phase: phase,
            mac: macSnap,
            android: androidSnap
        )
        if let data = try? JSONEncoder().encode(record),
           var line = String(data: data, encoding: .utf8) {
            line.append("\n")
            if let lineData = line.data(using: .utf8) {
                appendLine(lineData)
            }
        }
    }

    private func appendLine(_ data: Data) {
        if !FileManager.default.fileExists(atPath: logFileURL.path) {
            try? data.write(to: logFileURL)
            return
        }
        // Keep file under 10 MB
        if let attrs = try? FileManager.default.attributesOfItem(atPath: logFileURL.path),
           let size = attrs[.size] as? UInt64, size > 10 * 1024 * 1024 {
            try? data.write(to: logFileURL)
            return
        }
        if let fh = try? FileHandle(forWritingTo: logFileURL) {
            defer { try? fh.close() }
            _ = try? fh.seekToEnd()
            try? fh.write(contentsOf: data)
        }
    }

    private static func readProcessCpuSeconds() -> Double {
        var r = rusage()
        guard getrusage(RUSAGE_SELF, &r) == 0 else { return 0 }
        let user = Double(r.ru_utime.tv_sec) + Double(r.ru_utime.tv_usec) / 1_000_000.0
        let sys = Double(r.ru_stime.tv_sec) + Double(r.ru_stime.tv_usec) / 1_000_000.0
        return user + sys
    }

    private static func readPhysicalFootprintMb() -> Double {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
        let kerr: kern_return_t = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        guard kerr == KERN_SUCCESS else { return 0 }
        return Double(info.phys_footprint) / (1024.0 * 1024.0)
    }
}
