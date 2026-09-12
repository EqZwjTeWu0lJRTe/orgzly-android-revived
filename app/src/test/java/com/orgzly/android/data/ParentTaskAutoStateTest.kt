package com.orgzly.android.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.orgzly.android.LocalStorage
import com.orgzly.android.db.OrgzlyDatabase
import com.orgzly.android.repos.RepoFactory
import com.orgzly.android.ui.NotePlace
import com.orgzly.android.ui.Place
import com.orgzly.android.ui.note.NotePayload
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParentTaskAutoStateTest {

    private lateinit var context: Context
    private lateinit var dataRepository: DataRepository
    private lateinit var database: OrgzlyDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        if (!WorkManager.isInitialized()) {
            WorkManager.initialize(context, Configuration.Builder().build())
        }

        database = OrgzlyDatabase.forMemory(context)

        val dbRepoBookRepository = DbRepoBookRepository(database)
        val localStorage = LocalStorage(context)
        val repoFactory = RepoFactory(context, dbRepoBookRepository)

        dataRepository = DataRepository(
            context, database, repoFactory, context.resources, localStorage)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private var bookId = 0L

    private fun newBook(): Long {
        bookId = dataRepository.createBook("book").book.id
        return bookId
    }

    private fun createNote(title: String, state: String?, parentId: Long? = null): Long {
        val place = if (parentId == null) {
            NotePlace(bookId)
        } else {
            NotePlace(bookId, parentId, Place.UNDER)
        }

        return dataRepository.createNote(NotePayload(title = title, state = state), place).id
    }

    private fun noteTitle(id: Long): String = dataRepository.getNote(id)!!.title

    private fun noteState(id: Long): String? = dataRepository.getNote(id)!!.state

    @Test
    fun addingTaskChild_appendsPercentCookieToTodoParent() {
        newBook()
        val parent = createNote("Parent", "TODO")

        createNote("Child TODO", "TODO", parent)

        assertEquals("Parent [0%]", noteTitle(parent))
        assertEquals("TODO", noteState(parent))
    }

    @Test
    fun noStateHeadingWithTaskChild_gainsTodoStateAndCookie() {
        newBook()
        val parent = createNote("Parent", null)

        createNote("Child TODO", "TODO", parent)

        assertEquals("Parent [0%]", noteTitle(parent))
        assertEquals("TODO", noteState(parent))
    }

    @Test
    fun addingPlainChild_doesNotChangeTask() {
        newBook()
        val parent = createNote("Parent", "TODO")

        // A child without a TODO/DONE keyword is not a task, so nothing is aggregated.
        createNote("Just a note", null, parent)

        assertEquals("Parent", noteTitle(parent))
        assertEquals("TODO", noteState(parent))
    }

    @Test
    fun noStateHeadingWithOnlyPlainNotes_remainsUntouched() {
        newBook()
        val parent = createNote("Plain heading", null)

        createNote("Just a note", null, parent)

        val note = dataRepository.getNote(parent)!!
        assertEquals("Plain heading", note.title)
        assertNull(note.state)
    }

    @Test
    fun allChildrenDone_transitionsParentToDoneWith100() {
        newBook()
        val parent = createNote("Parent", "TODO")
        val child1 = createNote("Child 1", "TODO", parent)
        val child2 = createNote("Child 2", "TODO", parent)

        assertEquals("Parent [0%]", noteTitle(parent))

        dataRepository.setNotesState(setOf(child1), "DONE")

        assertEquals("Parent [50%]", noteTitle(parent))
        assertEquals("TODO", noteState(parent))

        dataRepository.setNotesState(setOf(child2), "DONE")

        assertEquals("Parent [100%]", noteTitle(parent))
        assertEquals("DONE", noteState(parent))
    }

    @Test
    fun childRevertingToTodo_transitionsDoneParentBackToTodo() {
        newBook()
        val parent = createNote("Parent", "TODO")
        val child1 = createNote("Child 1", "TODO", parent)
        val child2 = createNote("Child 2", "TODO", parent)

        dataRepository.setNotesState(setOf(child1), "DONE")
        dataRepository.setNotesState(setOf(child2), "DONE")

        assertEquals("DONE", noteState(parent))

        dataRepository.setNotesState(setOf(child1), "TODO")

        assertEquals("TODO", noteState(parent))
        assertEquals("Parent [50%]", noteTitle(parent))
    }

    @Test
    fun editingChildStateViaUpdateNote_updatesParent() {
        newBook()
        val parent = createNote("Parent", "TODO")
        val child = createNote("Child", "TODO", parent)

        dataRepository.updateNote(child, NotePayload(title = "Child", state = "DONE"))

        assertEquals("DONE", noteState(parent))
        assertEquals("Parent [100%]", noteTitle(parent))

        dataRepository.updateNote(child, NotePayload(title = "Child", state = "TODO"))

        assertEquals("TODO", noteState(parent))
        assertEquals("Parent [0%]", noteTitle(parent))
    }

    @Test
    fun deletingChild_updatesParentStateAndCookie() {
        newBook()
        val parent = createNote("Parent", "TODO")
        val child1 = createNote("Child 1", "TODO", parent)
        val child2 = createNote("Child 2", "TODO", parent)

        dataRepository.setNotesState(setOf(child1), "DONE")

        assertEquals("Parent [50%]", noteTitle(parent))
        assertEquals("TODO", noteState(parent))

        // Deleting the remaining TODO child leaves only DONE children, so the parent
        // should complete automatically.
        dataRepository.deleteNotes(dataRepository.getNote(parent)!!.position.bookId, setOf(child2))

        assertEquals("Parent [100%]", noteTitle(parent))
        assertEquals("DONE", noteState(parent))
    }

    @Test
    fun grandchildCompletion_isReflectedInGrandparent() {
        newBook()
        // Father has no keyword, middle child is a TODO, which has two TODO leaves.
        val grand = createNote("Grand", null)
        val mid = createNote("Mid", "TODO", grand)
        val leaf1 = createNote("Leaf 1", "TODO", mid)
        val leaf2 = createNote("Leaf 2", "TODO", mid)

        // Both middle and father become tasks once task descendants exist.
        assertEquals("TODO", noteState(mid))
        assertEquals("Mid [0%]", noteTitle(mid))
        assertEquals("TODO", noteState(grand))
        assertEquals("Grand [0%]", noteTitle(grand))

        // Completing one grandchild moves the middle (and, through it, the father) to 50%.
        dataRepository.setNotesState(setOf(leaf1), "DONE")

        assertEquals("Mid [50%]", noteTitle(mid))
        assertEquals("TODO", noteState(mid))

        // Father has a single child unit (Mid), so it mirrors Mid's progress.
        assertEquals("Grand [50%]", noteTitle(grand))
        assertEquals("TODO", noteState(grand))

        // Completing the second grandchild completes the whole chain bottom-up.
        dataRepository.setNotesState(setOf(leaf2), "DONE")

        assertEquals("Mid [100%]", noteTitle(mid))
        assertEquals("DONE", noteState(mid))
        assertEquals("Grand [100%]", noteTitle(grand))
        assertEquals("DONE", noteState(grand))
    }

    @Test
    fun wholeBookRecompute_fixesParentAfterExternalChange() {
        newBook()
        val parent = createNote("Parent", "TODO")
        val child1 = createNote("Child 1", "TODO", parent)
        val child2 = createNote("Child 2", "TODO", parent)

        dataRepository.setNotesState(setOf(child1, child2), "DONE")

        assertEquals("Parent [100%]", noteTitle(parent))
        assertEquals("DONE", noteState(parent))

        // Simulate an external tool editing the .org file: child2 is reverted to TODO
        // behind Orgzly's back (raw DB update, no cascade).
        database.note().updateStateAndRemoveClosedTime(setOf(child2), "TODO")

        // Parent is now stale until a whole-book recompute happens (on load / open).
        assertEquals("DONE", noteState(parent))

        val changed = dataRepository.recomputeBookProgress(bookId)

        assertTrue(changed > 0)
        assertEquals("TODO", noteState(parent))
        assertEquals("Parent [50%]", noteTitle(parent))

        // A second recompute must be a no-op (stable, no endless write-backs).
        assertEquals(0, dataRepository.recomputeBookProgress(bookId))
    }

    @Test
    fun clock_togglesStartAndStopAndAccumulates() {
        newBook()
        val note = createNote("Timed note", null)

        assertTrue(!dataRepository.isClockRunning(note))

        val (running1, _) = dataRepository.toggleClock(note)
        assertTrue(running1)
        assertTrue(dataRepository.isClockRunning(note))

        val (running2, totalAfterStop) = dataRepository.toggleClock(note)
        assertTrue(!running2)
        assertTrue(!dataRepository.isClockRunning(note))
        assertTrue(totalAfterStop >= 0L)

        // A second cycle keeps accumulating.
        dataRepository.toggleClock(note)
        val (_, totalAfterSecondCycle) = dataRepository.toggleClock(note)
        assertTrue(totalAfterSecondCycle >= totalAfterStop)
    }

    @Test
    fun halfDoneChildAndFullyDoneChild_averageTo75() {
        newBook()
        // P has two children: A (two leaves, one done -> 50%) and B (done -> 100%).
        val p = createNote("P", "TODO")
        val a = createNote("A", "TODO", p)
        val a1 = createNote("A1", "TODO", a)
        val a2 = createNote("A2", "TODO", a)
        val b = createNote("B", "DONE", p)

        // After B is done but none of A's leaves is done, P shows 50%.
        assertEquals("P [50%]", noteTitle(p))
        assertEquals("TODO", noteState(p))

        // A reflects its own leaves.
        assertEquals("A [0%]", noteTitle(a))
        assertEquals("TODO", noteState(a))

        dataRepository.setNotesState(setOf(a1), "DONE")

        // A = 50%, B = 100% -> P = (50 + 100) / 2 = 75%.
        assertEquals("A [50%]", noteTitle(a))
        assertEquals("TODO", noteState(a))
        assertEquals("P [75%]", noteTitle(p))
        assertEquals("TODO", noteState(p))

        dataRepository.setNotesState(setOf(a2), "DONE")

        // Everything done: A and P complete.
        assertEquals("A [100%]", noteTitle(a))
        assertEquals("DONE", noteState(a))
        assertEquals("P [100%]", noteTitle(p))
        assertEquals("DONE", noteState(p))
    }
}
