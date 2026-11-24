import Foundation
import CoreGraphics

public enum MirrorSizePreset: String, CaseIterable, Identifiable {
    case small = "S"
    case medium = "M"
    case large = "L"

    public var id: String { rawValue }

    public var targetLongEdge: CGFloat {
        switch self {
        case .small: return 540
        case .medium: return 720
        case .large: return 920
        }
    }
}

public enum StreamQualityPreset: String, CaseIterable, Identifiable {
    case fast = "Fast"
    case balanced = "Balanced"
    case sharp = "Sharp"

    public var id: String { rawValue }

    public var payload: MirrorQualityPayload {
        switch self {
        case .fast:
            return MirrorQualityPayload(maxLongSide: 960, bitrateBps: 2_500_000, fps: 60)
        case .balanced:
            return MirrorQualityPayload(maxLongSide: 1280, bitrateBps: 4_000_000, fps: 60)
        case .sharp:
            return MirrorQualityPayload(maxLongSide: 1600, bitrateBps: 6_000_000, fps: 60)
        }
    }
}

/// Zero-copy Annex-B to 4-byte length-prefixed AVCC converter for VideoToolbox.
public enum AnnexBConverter {

    /// Splits an Annex-B byte buffer into raw NAL units (excluding start codes).
    public static func splitNalUnits(_ data: Data) -> [Data] {
        return data.withUnsafeBytes { rawBuf -> [Data] in
            guard let base = rawBuf.bindMemory(to: UInt8.self).baseAddress else { return [] }
            let n = rawBuf.count
            var ranges: [(Int, Int)] = []
            var nalStart = -1
            var i = 0
            while i < n {
                if i + 2 < n && base[i] == 0 && base[i + 1] == 0 {
                    if base[i + 2] == 1 {
                        if nalStart >= 0 && i > nalStart {
                            ranges.append((nalStart, i))
                        }
                        nalStart = i + 3
                        i += 3
                        continue
                    } else if i + 3 < n && base[i + 2] == 0 && base[i + 3] == 1 {
                        if nalStart >= 0 && i > nalStart {
                            ranges.append((nalStart, i))
                        }
                        nalStart = i + 4
                        i += 4
                        continue
                    }
                }
                i += 1
            }
            if nalStart >= 0 && n > nalStart {
                ranges.append((nalStart, n))
            } else if ranges.isEmpty && n > 0 {
                return [data]
            }
            return ranges.map { Data(bytes: base.advanced(by: $0.0), count: $0.1 - $0.0) }
        }
    }

    /// Converts Annex-B frame bytes directly into AVCC 4-byte length-prefixed NAL units
    /// without intermediate array copies. Skips inline SPS (7), PPS (8), and AUD (9).
    public static func annexBToAVCC(_ annexB: Data) -> Data {
        return annexB.withUnsafeBytes { rawBuf -> Data in
            guard let base = rawBuf.bindMemory(to: UInt8.self).baseAddress else { return Data() }
            let n = rawBuf.count
            var out = Data(capacity: n + 16)

            func appendNal(start: Int, end: Int) {
                let len = end - start
                guard len > 0 else { return }
                let nalType = base[start] & 0x1F
                if nalType == 7 || nalType == 8 || nalType == 9 { return }
                var lenBE = UInt32(len).bigEndian
                withUnsafeBytes(of: &lenBE) { out.append(contentsOf: $0) }
                out.append(base.advanced(by: start), count: len)
            }

            var nalStart = -1
            var i = 0
            while i < n {
                if i + 2 < n && base[i] == 0 && base[i + 1] == 0 {
                    if base[i + 2] == 1 {
                        if nalStart >= 0 { appendNal(start: nalStart, end: i) }
                        nalStart = i + 3
                        i += 3
                        continue
                    } else if i + 3 < n && base[i + 2] == 0 && base[i + 3] == 1 {
                        if nalStart >= 0 { appendNal(start: nalStart, end: i) }
                        nalStart = i + 4
                        i += 4
                        continue
                    }
                }
                i += 1
            }
            if nalStart >= 0 {
                appendNal(start: nalStart, end: n)
            } else if n > 0 {
                appendNal(start: 0, end: n)
            }
            return out
        }
    }

    /// Computes window content dimensions matching the phone video aspect ratio for a size preset.
    public static func windowSize(videoWidth: Int, videoHeight: Int, preset: MirrorSizePreset) -> CGSize {
        guard videoWidth > 0, videoHeight > 0 else {
            return CGSize(width: 360, height: 780)
        }
        let w = CGFloat(videoWidth)
        let h = CGFloat(videoHeight)
        let longEdge = max(w, h)
        let scale = preset.targetLongEdge / longEdge
        return CGSize(width: round(w * scale), height: round(h * scale))
    }

    /// Computes the aspect-fit video rect inside a view bounds rectangle.
    public static func aspectFitRect(videoSize: CGSize, in bounds: CGRect) -> CGRect {
        guard videoSize.width > 0, videoSize.height > 0, bounds.width > 0, bounds.height > 0 else {
            return bounds
        }
        let videoAspect = videoSize.width / videoSize.height
        let viewAspect = bounds.width / bounds.height
        if videoAspect > viewAspect {
            let height = bounds.width / videoAspect
            let y = bounds.minY + (bounds.height - height) / 2.0
            return CGRect(x: bounds.minX, y: y, width: bounds.width, height: height)
        } else {
            let width = bounds.height * videoAspect
            let x = bounds.minX + (bounds.width - width) / 2.0
            return CGRect(x: x, y: bounds.minY, width: width, height: bounds.height)
        }
    }
}
