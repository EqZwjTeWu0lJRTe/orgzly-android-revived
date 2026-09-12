package com.orgzly.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParentTaskStateLogicTest {

    private val logic = ParentTaskStateLogic(
        todoKeywords = listOf("TODO"),
        doneKeywords = listOf("DONE"),
        firstTodoState = "TODO",
        firstDoneState = "DONE"
    )

    // --- Cookie appending / skipping ---

    @Test
    fun appendsPercentCookie_whenParentTaskHasUnits() {
        assertEquals("Parent [0%]", logic.titleForTaskProgress("Parent", true, 0, 0, 1))
        assertEquals("Parent [75%]", logic.titleForTaskProgress("Parent", true, 75, 1, 2))
    }

    @Test
    fun doesNotDuplicateCookie_whenAlreadyPresent() {
        assertEquals("Parent [0%]", logic.titleForTaskProgress("Parent [0%]", true, 0, 0, 1))
    }

    @Test
    fun headingWithoutUnits_isLeftUntouched() {
        assertEquals("Plain heading", logic.titleForTaskProgress("Plain heading", false, 0, 0, 0))
    }

    // --- Cookie value updates ---

    @Test
    fun updatesPercentCookie_withAverageCompletion() {
        assertEquals("Parent [50%]", logic.titleForTaskProgress("Parent [0%]", true, 50, 1, 2))
        assertEquals("Parent [100%]", logic.titleForTaskProgress("Parent [50%]", true, 100, 1, 1))
        assertEquals("Parent [0%]", logic.titleForTaskProgress("Parent [100%]", true, 0, 0, 1))
    }

    @Test
    fun fractionCookie_keepsDirectUnitCountSemantics() {
        // 75% average, but only one of the two direct units is fully complete.
        assertEquals("Parent [1/2]", logic.titleForTaskProgress("Parent [0/2]", true, 75, 1, 2))
    }

    @Test
    fun updatesBothCookies() {
        assertEquals(
            "Parent [75%] [1/2]",
            logic.titleForTaskProgress("Parent [0%] [0/2]", true, 75, 1, 2)
        )
    }

    // --- State transitions ---

    @Test
    fun noKeywordHeadingGainsTodo_whenIncomplete() {
        assertEquals("TODO", logic.stateForTaskProgress(null, 0))
        assertEquals("TODO", logic.stateForTaskProgress(null, 50))
    }

    @Test
    fun noKeywordHeadingGainsDone_whenComplete() {
        assertEquals("DONE", logic.stateForTaskProgress(null, 100))
    }

    @Test
    fun transitionsToDone_whenComplete() {
        assertEquals("DONE", logic.stateForTaskProgress("TODO", 100))
        assertNull(logic.stateForTaskProgress("TODO", 50))
    }

    @Test
    fun transitionsBackToTodo_whenIncompleteAgain() {
        assertEquals("TODO", logic.stateForTaskProgress("DONE", 50))
        assertNull(logic.stateForTaskProgress("DONE", 100))
    }

    @Test
    fun intermediateTodoKeywordIsNotDowngraded() {
        val waitLogic = ParentTaskStateLogic(
            todoKeywords = listOf("TODO", "WAIT"),
            doneKeywords = listOf("DONE"),
            firstTodoState = "TODO",
            firstDoneState = "DONE"
        )

        // WAIT is a todo keyword; while incomplete it must not be changed into plain TODO.
        assertNull(waitLogic.stateForTaskProgress("WAIT", 50))

        // ... but it still completes once fully done.
        assertEquals("DONE", waitLogic.stateForTaskProgress("WAIT", 100))
    }

    @Test
    fun customKeywords_areRespected() {
        val custom = ParentTaskStateLogic(
            todoKeywords = listOf("TODO", "WAIT"),
            doneKeywords = listOf("CANCELLED", "DONE"),
            firstTodoState = "TODO",
            firstDoneState = "DONE"
        )

        assertEquals("Parent [0%]", custom.titleForTaskProgress("Parent", true, 0, 0, 1))
        assertEquals("DONE", custom.stateForTaskProgress("TODO", 100))
    }

    // --- Scenario from user feedback: P(A half done, B done) -> 75% ---

    @Test
    fun parentOfHalfDoneAndDoneUnits_shows75() {
        // A = 50% (one of its two leaves done), B = 100%.
        assertEquals("P [75%]", logic.titleForTaskProgress("P", true, 75, 1, 2))
        assertNull(logic.stateForTaskProgress("TODO", 75))
    }
}
