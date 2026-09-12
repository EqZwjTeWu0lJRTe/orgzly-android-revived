package com.orgzly.android.data

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory cache of per-book "subtree stopwatch totals" (CLOCKED_SECONDS summed over a note
 * and all its descendants). Built once per book by DataRepository (on recompute/load and after
 * a stopwatch toggle) and read synchronously by the list binder, so the UI never runs the
 * expensive aggregation inside the list SQL.
 */
object ClockAggregates {
    private val byBook = ConcurrentHashMap<Long, Map<Long, Long>>()

    /** Total CLOCKED_SECONDS of [noteId]'s subtree in [bookId], or null when not computed. */
    @JvmStatic
    fun aggregate(bookId: Long, noteId: Long): Long? = byBook[bookId]?.get(noteId)

    /** Whether the given book has already been precomputed in this process. */
    @JvmStatic
    fun has(bookId: Long): Boolean = byBook.containsKey(bookId)

    @JvmStatic
    fun put(bookId: Long, totals: Map<Long, Long>) {
        byBook[bookId] = totals
    }

    @JvmStatic
    fun invalidate(bookId: Long) {
        byBook.remove(bookId)
    }
}
