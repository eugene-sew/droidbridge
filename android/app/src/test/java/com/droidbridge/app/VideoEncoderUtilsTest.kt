package com.droidbridge.app

import com.droidbridge.app.mirror.EncodedSize
import com.droidbridge.app.mirror.VideoEncoderUtils
import com.droidbridge.app.mirror.VideoFrameBackpressureQueue
import com.droidbridge.app.net.VideoFramePayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoEncoderUtilsTest {

    @Test
    fun computesScaledEvenAndMultipleOf16Sizes() {
        assertEquals(EncodedSize(720, 1600), VideoEncoderUtils.computeScaledSize(1080, 2400, 1600, 2))
        assertEquals(EncodedSize(1600, 720), VideoEncoderUtils.computeScaledSize(2400, 1080, 1600, 2))

        val oddAligned = VideoEncoderUtils.computeScaledSize(1081, 2401, 1600, 16)
        assertEquals(0, oddAligned.width % 16)
        assertEquals(0, oddAligned.height % 16)
        assertTrue(oddAligned.height <= 1600)
    }

    @Test
    fun extractsSpsAndPpsFromAnnexBCodecConfig() {
        val sps = byteArrayOf(0x67, 0x42, 0xc0.toByte(), 0x28)
        val pps = byteArrayOf(0x68, 0xce.toByte(), 0x38, 0x80.toByte())
        val annexB = byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 1) + pps

        val pair = VideoEncoderUtils.extractSpsAndPps(annexB)
        assertNotNull(pair)
        assertArrayEquals(sps, pair!!.first)
        assertArrayEquals(pps, pair.second)
    }

    @Test
    fun backpressureQueueDropsNonKeyframesUntilNextKeyframe() {
        val q = VideoFrameBackpressureQueue(maxQueuedFrames = 3)
        val kf1 = VideoFramePayload(1, VideoFramePayload.FLAG_KEYFRAME, byteArrayOf(1))
        val p1 = VideoFramePayload(2, 0, byteArrayOf(2))
        val p2 = VideoFramePayload(3, 0, byteArrayOf(3))
        val p3 = VideoFramePayload(4, 0, byteArrayOf(4))
        val p4 = VideoFramePayload(5, 0, byteArrayOf(5))
        val kf2 = VideoFramePayload(6, VideoFramePayload.FLAG_KEYFRAME, byteArrayOf(6))

        assertFalse(q.offer(kf1))
        assertFalse(q.offer(p1))
        assertFalse(q.offer(p2))
        // 4th non-keyframe triggers drop & requests fresh keyframe
        assertTrue(q.offer(p3))
        // Subsequent non-keyframe is ignored while waiting for keyframe
        assertFalse(q.offer(p4))
        assertEquals(1, q.size()) // only kf1 remained
        // Next keyframe resumes streaming
        assertFalse(q.offer(kf2))
        assertEquals(kf1, q.poll())
        assertEquals(kf2, q.poll())
        assertNull(q.poll())
    }
}
