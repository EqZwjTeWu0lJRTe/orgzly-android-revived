package com.orgzly.android.util

import com.orgzly.android.ui.util.EventInsertIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventInsertIntentTest {

    private val HOUR = 60 * 60 * 1000L
    private val DAY = 24 * HOUR

    @Test
    fun noTime_returnsNull() {
        assertNull(EventInsertIntent.plan(null, null))
        assertNull(EventInsertIntent.plan("", ""))
    }

    @Test
    fun scheduledOnly_startsAtScheduled_andLastsOneHour() {
        val plan = EventInsertIntent.plan("<2026-09-06 Sun 10:00>", null)!!
        assertFalse(plan.allDay)
        assertEquals(HOUR, plan.endMillis - plan.startMillis)
    }

    @Test
    fun deadlineOnly_endsAtDeadline() {
        val plan = EventInsertIntent.plan(null, "<2026-09-06 Sun 18:00>")!!
        assertFalse(plan.allDay)
        // A one-hour event that finishes exactly at the deadline.
        assertEquals(HOUR, plan.endMillis - plan.startMillis)
    }

    @Test
    fun scheduledAndDeadline_mapToStartAndEnd() {
        val plan = EventInsertIntent.plan(
            "<2026-09-06 Sun 10:00>",
            "<2026-09-06 Sun 18:00>"
        )!!
        assertFalse(plan.allDay)
        assertEquals(8 * HOUR, plan.endMillis - plan.startMillis)
    }

    @Test
    fun dateOnlyRanges_areAllDayEvents() {
        val plan = EventInsertIntent.plan("<2026-09-06 Sun>", "<2026-09-07 Mon>")!!
        assertTrue(plan.allDay)
        // scheduled day through the day after the deadline
        assertEquals(2 * DAY, plan.endMillis - plan.startMillis)
    }

    @Test
    fun buildIntent_hasTitleAndTimes() {
        val intent = EventInsertIntent.buildIntent(
            "Buy milk",
            "Some content",
            "<2026-09-06 Sun 10:00>",
            "<2026-09-06 Sun 11:00>"
        )!!

        assertEquals("android.intent.action.INSERT", intent.action)
        assertEquals("vnd.android.cursor.item/event", intent.type)
        assertTrue(intent.hasExtra("title"))
        assertTrue(intent.hasExtra("beginTime"))
        assertTrue(intent.hasExtra("endTime"))
        assertEquals("Buy milk", intent.getStringExtra("title"))
    }
}
