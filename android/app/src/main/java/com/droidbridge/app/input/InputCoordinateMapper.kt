package com.droidbridge.app.input

import com.droidbridge.app.net.InputScrollPayload
import kotlin.math.hypot

data class PixelPoint(val x: Float, val y: Float)

data class SwipeSegment(
    val start: PixelPoint,
    val end: PixelPoint,
    val durationMs: Long
)

/**
 * Pure Kotlin coordinate mapping between normalized [0, 1] coordinates and real Android display pixels.
 */
object InputCoordinateMapper {

    fun toPixels(normX: Double, normY: Double, displayWidth: Int, displayHeight: Int): PixelPoint {
        val w = displayWidth.coerceAtLeast(1)
        val h = displayHeight.coerceAtLeast(1)
        val cx = normX.coerceIn(0.0, 1.0)
        val cy = normY.coerceIn(0.0, 1.0)
        return PixelPoint(
            x = (cx * (w - 1)).toFloat(),
            y = (cy * (h - 1)).toFloat()
        )
    }

    /**
     * Converts a macOS scroll event into a short swipe gesture anchored at the cursor position.
     */
    fun scrollToSwipe(
        scroll: InputScrollPayload,
        displayWidth: Int,
        displayHeight: Int
    ): SwipeSegment? {
        val anchor = toPixels(scroll.x, scroll.y, displayWidth, displayHeight)
        val maxSpan = minOf(displayWidth, displayHeight) * 0.35f
        val rawDx = (scroll.dx * 8.0).toFloat().coerceIn(-maxSpan, maxSpan)
        val rawDy = (scroll.dy * 8.0).toFloat().coerceIn(-maxSpan, maxSpan)

        if (hypot(rawDx.toDouble(), rawDy.toDouble()) < 4.0) {
            return null
        }

        val endX = (anchor.x + rawDx).coerceIn(1f, (displayWidth - 2).coerceAtLeast(1).toFloat())
        val endY = (anchor.y + rawDy).coerceIn(1f, (displayHeight - 2).coerceAtLeast(1).toFloat())
        return SwipeSegment(
            start = anchor,
            end = PixelPoint(endX, endY),
            durationMs = 120L
        )
    }
}
