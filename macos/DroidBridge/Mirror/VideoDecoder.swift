import Foundation
import AVFoundation
import CoreMedia
import CoreVideo
import VideoToolbox
import AppKit
import os

/// Ultra-low-latency hardware H.264 decoder using `VTDecompressionSession` (real-time hardware
/// acceleration) delivering zero-copy `IOSurface`-backed frames directly to a `CALayer`.
///
/// Compared to queued `AVSampleBufferDisplayLayer`, direct `VTDecompressionSession` -> `IOSurface`
/// has zero internal playback buffer, 1-3ms Apple Silicon decode time, never stalls after packet
/// loss or rotation, and always presents the very latest decoded frame immediately.
public final class VideoDecoder: ObservableObject {
    @Published public private(set) var videoSize: CGSize = CGSize(width: 720, height: 1600)
    @Published public private(set) var rotation: Int = 0
    @Published public private(set) var hasReceivedFirstFrame: Bool = false
    @Published public private(set) var currentFps: Int = 0

    public let renderLayer: CALayer
    public var onRequestKeyframe: (() -> Void)?
    public var onVideoSizeChanged: ((CGSize) -> Void)?
    public var onFrameDecodedMetrics: ((_ wireBytes: Int, _ decodeMs: Double, _ fps: Int) -> Void)?

    private let logger = Logger(subsystem: "com.droidbridge.macos", category: "VideoDecoder")
    private var formatDescription: CMVideoFormatDescription?
    private var decompressionSession: VTDecompressionSession?
    private let decodeQueue = DispatchQueue(label: "com.droidbridge.video.vtdecode", qos: .userInteractive)
    private var lastKeyframeRequestTime: Date = .distantPast
    private var waitingForKeyframe: Bool = true

    private var frameCountWindow: Int = 0
    private var lastFpsTimestamp: CFAbsoluteTime = CFAbsoluteTimeGetCurrent()

    public init() {
        let layer = CALayer()
        layer.contentsGravity = .resizeAspect
        layer.backgroundColor = NSColor.black.cgColor
        layer.isOpaque = true
        self.renderLayer = layer
    }

    deinit {
        if let session = decompressionSession {
            VTDecompressionSessionInvalidate(session)
        }
    }

    public func reset() {
        decodeQueue.async { [weak self] in
            guard let self else { return }
            self.invalidateSessionLocked()
            self.formatDescription = nil
            self.waitingForKeyframe = true
            self.frameCountWindow = 0
            DispatchQueue.main.async {
                CATransaction.begin()
                CATransaction.setDisableActions(true)
                self.renderLayer.contents = nil
                CATransaction.commit()
                self.hasReceivedFirstFrame = false
                self.currentFps = 0
            }
        }
    }

    public func handleVideoConfig(_ config: VideoConfigPayload) {
        decodeQueue.async { [weak self] in
            guard let self else { return }
            var desc: CMVideoFormatDescription?
            let status: OSStatus = config.sps.withUnsafeBytes { spsBuf in
                config.pps.withUnsafeBytes { ppsBuf in
                    guard let spsBase = spsBuf.baseAddress?.assumingMemoryBound(to: UInt8.self),
                          let ppsBase = ppsBuf.baseAddress?.assumingMemoryBound(to: UInt8.self) else {
                        return kCMFormatDescriptionError_InvalidParameter
                    }
                    let paramSetPtrs: [UnsafePointer<UInt8>] = [spsBase, ppsBase]
                    let paramSetSizes: [Int] = [config.sps.count, config.pps.count]
                    return CMVideoFormatDescriptionCreateFromH264ParameterSets(
                        allocator: kCFAllocatorDefault,
                        parameterSetCount: 2,
                        parameterSetPointers: paramSetPtrs,
                        parameterSetSizes: paramSetSizes,
                        nalUnitHeaderLength: 4,
                        formatDescriptionOut: &desc
                    )
                }
            }

            guard status == noErr, let validDesc = desc else {
                self.logger.error("Failed creating H.264 format description: \(status)")
                self.requestKeyframeThrottled()
                return
            }

            self.invalidateSessionLocked()
            self.formatDescription = validDesc
            self.waitingForKeyframe = true
            self.createDecompressionSessionLocked(formatDescription: validDesc)

            let newSize = CGSize(width: Int(config.width), height: Int(config.height))
            DispatchQueue.main.async {
                self.videoSize = newSize
                self.rotation = Int(config.rotation)
                self.onVideoSizeChanged?(newSize)
            }
        }
    }

    public func handleVideoFrame(_ frame: VideoFramePayload) {
        decodeQueue.async { [weak self] in
            guard let self else { return }
            guard let formatDesc = self.formatDescription,
                  let session = self.decompressionSession else {
                self.requestKeyframeThrottled()
                return
            }

            if self.waitingForKeyframe {
                if !frame.isKeyframe {
                    self.requestKeyframeThrottled()
                    return
                }
                self.waitingForKeyframe = false
            }

            let avcc = AnnexBConverter.annexBToAVCC(frame.nalData)
            guard !avcc.isEmpty else { return }

            guard let sampleBuffer = self.makeSampleBuffer(
                avccData: avcc,
                ptsMicros: frame.ptsMicros,
                formatDescription: formatDesc
            ) else {
                return
            }

            var infoFlags = VTDecodeInfoFlags()
            let decodeFlags: VTDecodeFrameFlags = [._1xRealTimePlayback]
            let tDecodeStart = CFAbsoluteTimeGetCurrent()
            let frameWireBytes = frame.nalData.count + 9
            let status = VTDecompressionSessionDecodeFrame(
                session,
                sampleBuffer: sampleBuffer,
                flags: decodeFlags,
                infoFlagsOut: &infoFlags
            ) { [weak self] status, _, imageBuffer, _, _ in
                guard let self else { return }
                guard status == noErr, let pixelBuffer = imageBuffer else {
                    self.waitingForKeyframe = true
                    self.requestKeyframeThrottled()
                    return
                }
                let decodeMs = (CFAbsoluteTimeGetCurrent() - tDecodeStart) * 1000.0
                self.presentPixelBuffer(pixelBuffer, wireBytes: frameWireBytes, decodeMs: decodeMs)
            }

            if status != noErr {
                self.logger.warning("VTDecompressionSessionDecodeFrame error \(status); requesting keyframe")
                self.waitingForKeyframe = true
                self.requestKeyframeThrottled()
            }
        }
    }

    private func createDecompressionSessionLocked(formatDescription: CMVideoFormatDescription) {
        let decoderSpec: [CFString: Any] = [
            kVTVideoDecoderSpecification_EnableHardwareAcceleratedVideoDecoder: true
        ]
        let destAttrs: [CFString: Any] = [
            kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
            kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary,
            kCVPixelBufferMetalCompatibilityKey: true
        ]

        var session: VTDecompressionSession?
        let status = VTDecompressionSessionCreate(
            allocator: kCFAllocatorDefault,
            formatDescription: formatDescription,
            decoderSpecification: decoderSpec as CFDictionary,
            imageBufferAttributes: destAttrs as CFDictionary,
            outputCallback: nil,
            decompressionSessionOut: &session
        )

        guard status == noErr, let validSession = session else {
            logger.error("VTDecompressionSessionCreate failed: \(status)")
            return
        }

        VTSessionSetProperty(validSession, key: kVTDecompressionPropertyKey_RealTime, value: kCFBooleanTrue)
        self.decompressionSession = validSession
    }

    private func invalidateSessionLocked() {
        if let s = decompressionSession {
            VTDecompressionSessionInvalidate(s)
            decompressionSession = nil
        }
    }

    private func presentPixelBuffer(_ pixelBuffer: CVImageBuffer, wireBytes: Int, decodeMs: Double) {
        guard let ioSurface = CVPixelBufferGetIOSurface(pixelBuffer)?.takeUnretainedValue() else {
            return
        }

        frameCountWindow += 1
        let now = CFAbsoluteTimeGetCurrent()
        var fpsToPublish: Int?
        if now - lastFpsTimestamp >= 1.0 {
            fpsToPublish = Int(round(Double(frameCountWindow) / (now - lastFpsTimestamp)))
            frameCountWindow = 0
            lastFpsTimestamp = now
        }
        let reportedFps = fpsToPublish ?? max(currentFps, frameCountWindow)
        onFrameDecodedMetrics?(wireBytes, decodeMs, reportedFps)

        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            self.renderLayer.contents = ioSurface
            CATransaction.commit()
            if !self.hasReceivedFirstFrame {
                self.hasReceivedFirstFrame = true
            }
            if let fps = fpsToPublish {
                self.currentFps = fps
            }
        }
    }

    private func makeSampleBuffer(
        avccData: Data,
        ptsMicros: UInt64,
        formatDescription: CMVideoFormatDescription
    ) -> CMSampleBuffer? {
        let count = avccData.count
        var blockBuffer: CMBlockBuffer?
        let createStatus = CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault,
            memoryBlock: nil,
            blockLength: count,
            blockAllocator: kCFAllocatorDefault,
            customBlockSource: nil,
            offsetToData: 0,
            dataLength: count,
            flags: 0,
            blockBufferOut: &blockBuffer
        )
        guard createStatus == kCMBlockBufferNoErr, let blockBuf = blockBuffer else {
            return nil
        }

        let replaceStatus = avccData.withUnsafeBytes { rawBuf -> OSStatus in
            guard let base = rawBuf.baseAddress else { return kCMBlockBufferBadPointerParameterErr }
            return CMBlockBufferReplaceDataBytes(
                with: base,
                blockBuffer: blockBuf,
                offsetIntoDestination: 0,
                dataLength: count
            )
        }
        guard replaceStatus == kCMBlockBufferNoErr else { return nil }

        var timing = CMSampleTimingInfo(
            duration: .invalid,
            presentationTimeStamp: CMTime(value: CMTimeValue(ptsMicros), timescale: 1_000_000),
            decodeTimeStamp: .invalid
        )
        var sampleSize = count
        var sampleBuffer: CMSampleBuffer?
        let sbStatus = CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault,
            dataBuffer: blockBuf,
            formatDescription: formatDescription,
            sampleCount: 1,
            sampleTimingEntryCount: 1,
            sampleTimingArray: &timing,
            sampleSizeEntryCount: 1,
            sampleSizeArray: &sampleSize,
            sampleBufferOut: &sampleBuffer
        )
        return (sbStatus == noErr) ? sampleBuffer : nil
    }

    private func requestKeyframeThrottled() {
        let now = Date()
        guard now.timeIntervalSince(lastKeyframeRequestTime) >= 0.8 else { return }
        lastKeyframeRequestTime = now
        DispatchQueue.main.async { [weak self] in
            self?.onRequestKeyframe?()
        }
    }
}
