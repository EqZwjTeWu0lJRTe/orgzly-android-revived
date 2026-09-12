package com.orgzly.android.usecase

import com.orgzly.android.data.DataRepository

/**
 * Recomputes and writes back parent-task statistics cookies/states for the whole book.
 * Triggers a sync only when something actually changed, so opening a book is cheap when
 * the outline is already up to date.
 */
class BookRecomputeProgress(val bookId: Long) : UseCase() {
    override fun run(dataRepository: DataRepository): UseCaseResult {
        val changed = dataRepository.recomputeBookProgress(bookId)

        return UseCaseResult(
                modifiesLocalData = changed > 0,
                triggersSync = if (changed > 0) SYNC_DATA_MODIFIED else SYNC_NOT_REQUIRED,
                userData = changed
        )
    }
}
