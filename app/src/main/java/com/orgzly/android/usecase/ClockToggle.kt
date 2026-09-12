package com.orgzly.android.usecase

import com.orgzly.android.data.DataRepository

/**
 * Starts or stops the manual stopwatch on a note (toggling CLOCK_START / CLOCKED_SECONDS).
 */
class ClockToggle(val noteId: Long) : UseCase() {
    override fun run(dataRepository: DataRepository): UseCaseResult {
        val state = dataRepository.toggleClock(noteId)

        return UseCaseResult(
                modifiesLocalData = true,
                triggersSync = SYNC_DATA_MODIFIED,
                userData = state
        )
    }
}
