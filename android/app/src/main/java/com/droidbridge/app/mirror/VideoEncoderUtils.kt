package com.droidbridge.app.mirror

import com.droidbridge.app.net.VideoFramePayload
import kotlin.math.max
import kotlin.math.roundToInt

data class EncodedSize(val width: Int, val height: Int)

/**
 * Pure Kotlin helpers for H.264 encoder sizing, SPS/PPS extraction from Annex-B CSD,
 * and low-latency frame backpressure dropping.
 */
object VideoEncoderUtils {

    /**
     * Scales raw display dimensions so the longest side is <= [maxLongSide] while
     * preserving aspect ratio and aligning both dimensions down to a multiple of [alignment]
     * (2 by default, 16 as encoder compatibility fallback).
     */
    fun computeScaledSize(
        rawWidth: Int,
        rawHeight: Int,
        maxLongSide: Int = 1600,
        alignment: Int = 2
    ): EncodedSize {
        require(rawWidth > 0 && rawHeight > 0) { "Invalid display size: ${rawWidth}x${rawHeight}" }
        require(alignment >= 2 && alignment % 2 == 0) { "Alignment must be an even positive integer" }

        val longSide = max(rawWidth, rawHeight)
        val scale = if (longSide > maxLongSide) maxLongSide.toDouble() / longSide.toDouble() else 1.0

        val scaledW = (rawWidth * scale).roundToInt()
        val scaledH = (rawHeight * scale).roundToInt()

        val alignedW = ((scaledW / alignment) * alignment).coerceAtLeast(alignment)
        val alignedH = ((scaledH / alignment) * alignment).coerceAtLeast(alignment)
        return EncodedSize(alignedW, alignedH)
    }

    /**
     * Splits an Annex-B byte buffer into raw NAL units (without start codes).
     */
    fun splitAnnexBNalUnits(data: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Pair<Int, Int>>() // (startCodeOffset, nalStartOffset)
        var i = 0
        while (i < data.size - 2) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) {
                    starts.add(i to (i + 3))
                    i += 3
                    continue
                } else if (i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    starts.add(i to (i + 4))
                    i += 4
                    continue
                }
            }
            i++
        }
        if (starts.isEmpty()) {
            return if (data.isNotEmpty()) listOf(data) else emptyList()
        }
        val result = ArrayList<ByteArray>(starts.size)
        for (idx in starts.indices) {
            val nalStart = starts[idx].second
            val nalEnd = if (idx + 1 < starts.size) starts[idx + 1].first else data.size
            if (nalEnd > nalStart) {
                result.add(data.copyOfRange(nalStart, nalEnd))
            }
        }
        return result
    }

    /**
     * Extracts raw SPS (NAL type 7) and PPS (NAL type 8) bytes without start codes
     * from a MediaCodec codec-config buffer or concatenated csd-0/csd-1.
     */
    fun extractSpsAndPps(codecConfigBytes: ByteArray): Pair<ByteArray, ByteArray>? {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (nal in splitAnnexBNalUnits(codecConfigBytes)) {
            if (nal.isEmpty()) continue
            val nalType = nal[0].toInt() and 0x1F
            if (nalType == 7 && sps == null) sps = nal
            if (nalType == 8 && pps == null) pps = nal
        }
        return if (sps != null && pps != null) sps to pps else null
    }
}

/**
 * Bounded frame queue enforcing low-latency backpressure:
 * when more than [maxQueuedFrames] frames are pending, non-keyframes are dropped
 * and subsequent non-keyframes are skipped until the next keyframe arrives.
 */
class VideoFrameBackpressureQueue(
    private val maxQueuedFrames: Int = 3
) {
    private val lock = Any()
    private val queue = ArrayDeque<VideoFramePayload>()
    private var waitingForKeyframe = false

    /**
     * Enqueues a frame. Returns true if the caller should request a fresh keyframe
     * because frames had to be dropped under backpressure.
     */
    fun offer(frame: VideoFramePayload): Boolean = synchronized(lock) {
        if (waitingForKeyframe) {
            if (!frame.isKeyframe) {
                return false
            }
            waitingForKeyframe = false
        }

        if (queue.size >= maxQueuedFrames) {
            if (frame.isKeyframe) {
                // Drop stale queued frames and keep the fresh keyframe
                queue.clear()
                queue.addLast(frame)
                return false
            } else {
                // Drop non-keyframes until the next keyframe so latency never builds up
                queue.removeAll { !it.isKeyframe }
                if (queue.size >= maxQueuedFrames) {
                    queue.clear()
                }
                waitingForKeyframe = true
                return true
            }
        }

        queue.addLast(frame)
        false
    }

    fun poll(): VideoFramePayload? = synchronized(lock) {
        if (queue.isEmpty()) null else queue.removeFirst()
    }

    fun clear() = synchronized(lock) {
        queue.clear()
        waitingForKeyframe = false
    }

    fun size(): Int = synchronized(lock) { queue.size }
}
