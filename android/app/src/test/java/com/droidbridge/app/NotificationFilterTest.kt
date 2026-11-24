package com.droidbridge.app

import com.droidbridge.app.net.NotifMessage
import com.droidbridge.app.net.NotifPostedPayload
import com.droidbridge.app.notify.NotificationFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFilterTest {

    private fun sampleNotif(
        key: String = "0|com.whatsapp|1",
        title: String? = "Kofi",
        text: String? = "Are we still on for 6?",
        postTime: Long = 1_700_000_000_000L,
        messages: List<NotifMessage> = emptyList()
    ) = NotifPostedPayload(
        key = key,
        pkg = "com.whatsapp",
        appName = "WhatsApp",
        title = title,
        text = text,
        postTime = postTime,
        messages = messages
    )

    @Test
    fun skipsOngoingGroupSummaryOwnPackageAndEmpty() {
        val filter = NotificationFilter(ownPackageName = "com.droidbridge.app")
        assertTrue(filter.shouldSkip("com.droidbridge.app", false, false, "Title", "Body", false))
        assertTrue(filter.shouldSkip("com.spotify", true, false, "Playing", "Track", false))
        assertTrue(filter.shouldSkip("com.whatsapp", false, true, "WhatsApp", "2 messages", false))
        assertTrue(filter.shouldSkip("com.example", false, false, null, "", false))
        assertFalse(filter.shouldSkip("com.whatsapp", false, false, "Kofi", "Hey", false))
    }

    @Test
    fun dedupesIdenticalContentAndAllowsUpdates() {
        val filter = NotificationFilter("com.droidbridge.app")
        val n1 = sampleNotif()
        assertTrue(filter.acceptIfChanged(n1))
        assertFalse(filter.acceptIfChanged(n1))

        val n2 = sampleNotif(text = "Running 5 mins late")
        assertTrue(filter.acceptIfChanged(n2))

        // After removal, same content should be accepted again
        filter.onRemoved(n2.key)
        assertTrue(filter.acceptIfChanged(n2))
    }

    @Test
    fun offlineQueueCapsAt100AndDropsExpiredOver1Hour() {
        val filter = NotificationFilter("com.droidbridge.app", maxQueueSize = 100, maxQueueAgeMs = 3_600_000L)
        val t0 = 10_000_000L

        // Enqueue an old item (>1 hour before drain)
        filter.enqueueOffline(sampleNotif(key = "old", text = "expired"), nowMs = t0)
        // Enqueue 105 recent items
        val tRecent = t0 + 4_000_000L
        for (i in 1..105) {
            filter.enqueueOffline(sampleNotif(key = "k$i", text = "msg $i"), nowMs = tRecent + i)
        }

        val drained = filter.drainOfflineQueue(nowMs = tRecent + 1_000L)
        assertEquals(100, drained.size)
        assertEquals("k6", drained.first().key)
        assertEquals("k105", drained.last().key)
        assertTrue(filter.drainOfflineQueue(nowMs = tRecent + 2_000L).isEmpty())
    }
}
