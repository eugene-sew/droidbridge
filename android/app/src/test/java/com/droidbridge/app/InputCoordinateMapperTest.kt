package com.droidbridge.app

import com.droidbridge.app.input.InputCoordinateMapper
import com.droidbridge.app.input.PixelPoint
import com.droidbridge.app.net.InputScrollPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputCoordinateMapperTest {

    @Test
    fun mapsNormalizedCoordinatesToPixelsAndClamps() {
        assertEquals(PixelPoint(0f, 0f), InputCoordinateMapper.toPixels(-0.5, -0.1, 1080, 2400))
        assertEquals(PixelPoint(1079f, 2399f), InputCoordinateMapper.toPixels(1.5, 1.2, 1080, 2400))
        val center = InputCoordinateMapper.toPixels(0.5, 0.5, 1080, 2400)
        assertEquals(539.5f, center.x, 0.1f)
        assertEquals(1199.5f, center.y, 0.1f)
    }

    @Test
    fun convertsScrollIntoClampedSwipeSegment() {
        val seg = InputCoordinateMapper.scrollToSwipe(
            InputScrollPayload(dx = 0.0, dy = 20.0, x = 0.5, y = 0.5),
            displayWidth = 1080,
            displayHeight = 2400
        )
        assertNotNull(seg)
        assertTrue(seg!!.end.y > seg.start.y)
        assertTrue(seg.end.y < 2400f)

        // Tiny sub-threshold scroll produces null
        val tiny = InputCoordinateMapper.scrollToSwipe(
            InputScrollPayload(dx = 0.0, dy = 0.1, x = 0.5, y = 0.5),
            displayWidth = 1080,
            displayHeight = 2400
        )
        assertNull(tiny)
    }
}
