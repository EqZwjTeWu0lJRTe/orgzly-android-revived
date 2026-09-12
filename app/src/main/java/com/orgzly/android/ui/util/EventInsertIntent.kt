package com.orgzly.android.ui.util

import android.content.Intent
import android.provider.CalendarContract
import com.orgzly.org.datetime.OrgRange
import java.util.Calendar

/**
 * Builds a system-calendar "add event" intent (ACTION_INSERT) from an Orgzly note.
 *
 * Mapping (matches an ADB-verified run on a stock calendar app):
 *
 * - note title                        -> calendar event title
 * - SCHEDULED:                        -> event start time
 * - DEADLINE:                         -> event end time (the event finishes by the deadline)
 * - when only one of the two exists   -> the other defaults to a one hour duration
 *
 * Returns null when the note carries neither SCHEDULED nor DEADLINE.
 */
object EventInsertIntent {

    private const val HOUR_IN_MILLIS = 60 * 60 * 1000L
    private const val DAY_IN_MILLIS = 24 * HOUR_IN_MILLIS

    data class Plan(
        val startMillis: Long,
        val endMillis: Long,
        val allDay: Boolean
    )

    private fun hasTimePart(str: String?): Boolean = !str.isNullOrEmpty() && str.contains(":")

    private fun startMillis(range: OrgRange): Long = range.startTime.calendar.timeInMillis

    private fun endMillis(range: OrgRange): Long? = range.endTime?.calendar?.timeInMillis

    /** First moment (local midnight) of the day containing [millis]. */
    private fun startOfDay(millis: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = millis
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /**
     * Pure mapping from note's SCHEDULED/DEADLINE range strings to an event plan.
     * Kept separate from the Intent so it can be unit tested.
     */
    fun plan(scheduled: String?, deadline: String?): Plan? {
        val schedRange = OrgRange.parseOrNull(scheduled)
        val deadlineRange = OrgRange.parseOrNull(deadline)

        if (schedRange == null && deadlineRange == null) {
            return null
        }

        // All-day only when every provided range is a bare date (no clock time).
        val allDay = hasTimePart(scheduled).not() && hasTimePart(deadline).not()

        if (allDay) {
            val schedDayStart = schedRange?.let { startOfDay(startMillis(it)) }
            val deadlineDayStart = deadlineRange?.let { startOfDay(startMillis(it)) }

            // Event spans from the scheduled day to the day after the deadline.
            var begin = schedDayStart ?: deadlineDayStart!!
            var end = (deadlineDayStart ?: schedDayStart!!) + DAY_IN_MILLIS
            if (end <= begin) end = begin + DAY_IN_MILLIS
            return Plan(begin, end, true)
        }

        // Timed event.
        val schedStart = schedRange?.let { startMillis(it) }
        val schedEnd = schedRange?.let { endMillis(it) }
        val deadlineStart = deadlineRange?.let { startMillis(it) }
        val deadlineEnd = deadlineRange?.let { endMillis(it) } ?: deadlineStart

        // SCHEDULED defines the start; DEADLINE defines the end.
        var begin = schedStart
        var end = deadlineEnd

        if (begin == null && end != null) {
            // Only a deadline: a one-hour event finishing right at the deadline.
            begin = end - HOUR_IN_MILLIS
        }
        if (end == null && begin != null) {
            // Only a scheduled time: one hour starting then (or the range's own end).
            end = schedEnd ?: begin + HOUR_IN_MILLIS
        }

        if (begin == null || end == null) {
            return null
        }

        // Never offer an inverted/zero-length range to the calendar app.
        if (end <= begin) {
            end = begin + HOUR_IN_MILLIS
        }

        return Plan(begin, end, false)
    }

    /** Builds the ACTION_INSERT intent, or null when there is no time to export. */
    fun buildIntent(title: String, content: String?, scheduled: String?, deadline: String?): Intent? {
        val plan = plan(scheduled, deadline) ?: return null

        return Intent(Intent.ACTION_INSERT).apply {
            type = "vnd.android.cursor.item/event"
            // Note: "title"/"description" have no CalendarContract constants; these are the
            // literal keys that calendar apps (verified on ColorOS calendar) read.
            putExtra("title", title)
            putExtra("description", content?.trim() ?: "")
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, plan.startMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, plan.endMillis)
            if (plan.allDay) {
                putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
            }
        }
    }
}
