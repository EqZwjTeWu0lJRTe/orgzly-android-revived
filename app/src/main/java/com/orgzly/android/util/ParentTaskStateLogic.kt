package com.orgzly.android.util

import kotlin.math.roundToInt

/**
 * Pure logic for automatically managing a parent task's statistics cookie and its
 * TODO/DONE state.
 *
 * Completion is computed bottom-up as the *average completion of the heading's direct
 * child task-units* (a unit is a direct child that is itself a task, or whose subtree
 * contains tasks). This keeps progress from being diluted by intermediate containers,
 * e.g. P -> (A -> [A1 done, A2 todo] = 50%, B done = 100%) gives P = 75%.
 *
 * [%] cookies always show the average completion percentage; [/] cookies keep showing the
 * number of fully-completed direct child units out of the total direct child units. This
 * class performs no I/O so it can be unit tested.
 */
class ParentTaskStateLogic(
    private val todoKeywords: Collection<String>,
    private val doneKeywords: Collection<String>,
    private val firstTodoState: String?,
    private val firstDoneState: String?
) {
    private val percentageRegex = Regex("\\[\\d*%]")
    private val fractionRegex = Regex("\\[\\d*/\\d*]")

    private fun isTaskState(state: String?): Boolean =
        todoKeywords.contains(state) || doneKeywords.contains(state)

    /**
     * Returns the heading title with its statistics cookies rewritten:
     *
     * - [N%]  -> round([completionPct]%)
     * - [N/N] -> "[doneUnits/totalUnits]" (count of fully completed direct child units)
     * - when no cookie exists yet, one is appended as "[round(completionPct)%]".
     *
     * [hasUnits] is false for headings without any task descendants; such headings are
     * left untouched.
     */
    fun titleForTaskProgress(
        title: String,
        hasUnits: Boolean,
        completionPct: Int,
        doneUnits: Int,
        totalUnits: Int
    ): String {
        if (!hasUnits) {
            return title
        }

        val hasPercent = percentageRegex.containsMatchIn(title)
        val hasFraction = fractionRegex.containsMatchIn(title)

        if (hasPercent || hasFraction) {
            var newTitle = title

            if (hasPercent) {
                newTitle = newTitle.replace(percentageRegex, "[$completionPct%]")
            }

            if (hasFraction) {
                newTitle = newTitle.replace(fractionRegex, "[$doneUnits/$totalUnits]")
            }

            return newTitle
        }

        return title.trimEnd() + " [$completionPct%]"
    }

    /**
     * Returns the state this heading should transition to (null when unchanged):
     *
     * - a heading without a keyword gains TODO (DONE when everything below is complete),
     * - todo keywords -> DONE once the subtree is fully complete,
     * - DONE -> TODO as soon as the subtree is incomplete again.
     * Intermediate todo keywords (e.g. WAIT/NEXT) are never downgraded while incomplete.
     */
    fun stateForTaskProgress(
        parentState: String?,
        completionPct: Int
    ): String? {
        val isComplete = completionPct >= 100

        return when {
            parentState == null -> if (isComplete) firstDoneState else firstTodoState

            todoKeywords.contains(parentState) && isComplete &&
                    firstDoneState != null && firstDoneState != parentState -> firstDoneState

            doneKeywords.contains(parentState) && !isComplete &&
                    firstTodoState != null && firstTodoState != parentState -> firstTodoState

            else -> null
        }
    }

    /** Convenience helper: is [state] a configured task (todo or done) keyword? */
    fun isTask(state: String?): Boolean = isTaskState(state)
}
