package com.orgzly.android.ui.notes.book

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.ArrayAdapter
import android.widget.MultiAutoCompleteTextView
import androidx.appcompat.widget.SearchView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.orgzly.BuildConfig
import com.orgzly.R
import com.orgzly.android.App
import com.orgzly.android.AppIntent
import com.orgzly.android.BookUtils
import com.orgzly.android.db.NotesClipboard
import com.orgzly.android.db.entity.Book
import com.orgzly.android.db.entity.NoteView
import com.orgzly.android.prefs.AppPreferences
import com.orgzly.android.query.Condition
import com.orgzly.android.query.Query
import com.orgzly.android.query.SimpleFilter
import com.orgzly.android.query.user.InternalQueryBuilder
import com.orgzly.android.query.user.SimpleFilterMapper
import com.orgzly.android.sync.SyncRunner
import com.orgzly.android.ai.AiApi
import com.orgzly.android.ui.CommonActivity
import com.orgzly.android.ui.DisplayManager
import com.orgzly.android.ui.note.NotePayload
import com.orgzly.android.ui.NotePlace
import com.orgzly.android.ui.Place
import com.orgzly.android.ui.dialogs.TimestampDialogFragment
import com.orgzly.android.ui.drawer.DrawerItem
import com.orgzly.android.ui.main.SharedMainActivityViewModel
import com.orgzly.android.ui.main.setupSearchView
import com.orgzly.android.ui.notes.ItemGestureDetector
import com.orgzly.android.ui.notes.NoteItemViewHolder
import com.orgzly.android.ui.notes.NotePopup
import com.orgzly.android.ui.notes.NotesFragment
import com.orgzly.android.ui.notes.book.BookViewModel.Companion.APP_BAR_DEFAULT_MODE
import com.orgzly.android.ui.notes.book.BookViewModel.Companion.APP_BAR_SELECTION_MODE
import com.orgzly.android.ui.notes.book.BookViewModel.Companion.APP_BAR_SELECTION_MOVE_MODE
import com.orgzly.android.ui.refile.RefileFragment
import com.orgzly.android.ui.capture.CaptureTemplate
import com.orgzly.android.ui.capture.CaptureTemplateResolver
import com.orgzly.android.ui.capture.getDisplayName
import com.orgzly.android.ui.capture.normalizeHeadlinePath
import com.orgzly.android.ui.settings.SettingsActivity
import com.orgzly.android.ui.util.ActivityUtils
import com.orgzly.android.ui.util.setDecorFitsSystemWindowsForBottomToolbar
import com.orgzly.android.ui.util.setup
import com.orgzly.android.ui.util.styledAttributes
import com.orgzly.android.util.LogUtils
import com.orgzly.android.util.SpaceTokenizer
import com.orgzly.databinding.FragmentBookBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

enum class ScrollDirection {
    UP,
    DOWN,
}

/**
 * Displays all notes from the notebook.
 * Allows moving, cutting, pasting etc.
 */
class BookFragment :
        NotesFragment(),
        TimestampDialogFragment.OnDateTimeSetListener,
        DrawerItem,
        BookAdapter.OnClickListener {

    private lateinit var binding: FragmentBookBinding

    private var listener: Listener? = null

    private lateinit var viewAdapter: BookAdapter

    private lateinit var layoutManager: LinearLayoutManager

    private lateinit var sharedMainActivityViewModel: SharedMainActivityViewModel

    private lateinit var viewModel: BookViewModel

    @Inject lateinit var simpleFilterMapper: SimpleFilterMapper
    @Inject lateinit var queryBuilder: InternalQueryBuilder

    private val RECOMPUTE_DELAY_MS = 500L

    /** Note to scroll to & highlight once it becomes visible (ancestors unfolded). */
    private var pendingScrollNoteId: Long = 0

    private var hideButtonJob: Job? = null

    private var jumpButtonDirection = ScrollDirection.DOWN

    private val clipboardChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (::viewAdapter.isInitialized) {
                viewAdapter.notifyDataSetChanged()
            }
        }
    }

    override fun getAdapter(): BookAdapter? {
        return if (::viewAdapter.isInitialized) viewAdapter else null
    }

    override fun getCurrentListener(): NotesFragment.Listener? {
        return listener
    }

    // TODO: Move to ViewModel

    var currentBook: Book? = null

    private var mBookId: Long = 0

    private val appBarBackPressHandler = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (viewModel.isNarrowed()) {
                viewModel.widenView()
            } else {
                viewModel.appBar.handleOnBackPressed()
            }
        }
    }

    /**
     * Mandatory empty constructor for the fragment manager to instantiate the
     * fragment (e.g. upon screen orientation changes).
     */
    init {
        if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG)
    }


    override fun onAttach(context: Context) {
        super.onAttach(context)
        App.appComponent.inject(this)

        if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG, context)

        listener = activity as Listener
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG, savedInstanceState)

        sharedMainActivityViewModel = ViewModelProvider(requireActivity())
                .get(SharedMainActivityViewModel::class.java)

        parseArguments()

        arguments?.getLong(ARG_NOTE_ID, 0L)?.takeIf { it > 0 }?.let { noteId ->
            pendingScrollNoteId = noteId
            App.EXECUTORS.diskIO().execute { dataRepository.unfoldToNote(noteId) }
        }

        val factory = BookViewModelFactory.forBook(dataRepository, mBookId)
        viewModel = ViewModelProvider(this, factory).get(BookViewModel::class.java)

        requireActivity().onBackPressedDispatcher.addCallback(this, appBarBackPressHandler)
        requireActivity().onBackPressedDispatcher.addCallback(this, notePopupDismissOnBackPress)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG, savedInstanceState)

        binding = FragmentBookBinding.inflate(inflater, container, false)

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG, savedInstanceState)

        viewAdapter = BookAdapter(mBookId, binding.root.context, this, inBook = true).apply {
            setHasStableIds(true)
        }

        // Restores selection, requires adapter
        super.onViewCreated(view, savedInstanceState)

        layoutManager = LinearLayoutManager(context)

        binding.fragmentBookRecyclerView.let { rv ->
            rv.layoutManager = layoutManager
            rv.adapter = viewAdapter

            /*
             * Disable item animator (DefaultItemAnimator).
             * Animation is too slow.  And if animations are off in developer options, items flicker.
             * TODO: Do for query too?
             */
            rv.itemAnimator = null

            rv.addOnItemTouchListener(ItemGestureDetector(rv.context, object: ItemGestureDetector.Listener {
                override fun onSwipe(direction: Int, e1: MotionEvent, e2: MotionEvent) {
                    rv.findChildViewUnder(e1.x, e1.y)?.let { itemView ->
                        rv.findContainingViewHolder(itemView)?.let { vh ->
                            (vh as? NoteItemViewHolder)?.let {
                                // Disable swipe popup for narrowed root note - tap-to-edit still works
                                if (viewModel.isNarrowed() && vh.itemId == viewModel.narrowedNoteId.value) {
                                    return@let
                                }

                                showPopupWindow(vh.itemId, NotePopup.Location.BOOK, direction, itemView, e1, e2) { noteId, buttonId ->
                                    handleActionItemClick(setOf(noteId), buttonId)
                                }
                            }
                        }
                    }
                }
            }))

            // Add scroll listener for jump-to-end button
            setupJumpToEndButton(rv)
        }

        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
                clipboardChangedReceiver,
                IntentFilter(AppIntent.ACTION_CLIPBOARD_CHANGED))

        binding.swipeContainer.setup()

        viewModel.flipperDisplayedChild.observe(viewLifecycleOwner, Observer { child ->
            if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG, "Observed flipper displayed child: $child")

            binding.fragmentBookViewFlipper.apply {
                displayedChild = when (child) {
                    BookViewModel.FlipperDisplayedChild.LOADING -> 0
                    BookViewModel.FlipperDisplayedChild.LOADED -> 1
                    BookViewModel.FlipperDisplayedChild.EMPTY -> 2
                    BookViewModel.FlipperDisplayedChild.DOES_NOT_EXIST -> 3
                    else -> 1
                }
            }
        })

        viewModel.data.observe(viewLifecycleOwner, Observer { data ->
            if (BuildConfig.LOG_DEBUG)
                LogUtils.d(TAG, "Observed data: book ${data.book} and ${data.notes?.size} notes")

            val book = data.book
            val notes = data.notes

            this.currentBook = book

            if (notes != null) {
                if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG, "Submitting list")

                // Compute and apply outline colors *before* submitting, so rows appear
                // already colored (no "content first, color later" flicker).
                val tintsChanged = viewAdapter.setOutlineTints(computeOutlineTints(notes))

                viewAdapter.submitList(notes, viewModel.levelOffset(notes))

                if (tintsChanged) {
                    binding.root.post {
                        if (isAdded && ::viewAdapter.isInitialized) {
                            viewAdapter.notifyDataSetChanged()
                        }
                    }
                }

                val ids = notes.mapTo(hashSetOf()) { it.note.id }

                viewAdapter.getSelection().removeNonExistent(ids)

                viewModel.appBar.toModeFromSelectionCount(viewAdapter.getSelection().count)

                if (pendingScrollNoteId > 0) {
                    tryScrollToNote(pendingScrollNoteId)
                }
            }

            viewAdapter.setPreface(book)

            setFlipperDisplayedChild(notes)
        })

        viewModel.refileRequestEvent.observeSingle(viewLifecycleOwner, Observer {
            RefileFragment.getInstance(it.selected, it.count)
                    .show(childFragmentManager, RefileFragment.FRAGMENT_TAG)
        })

        viewModel.notesDeleteRequest.observeSingle(viewLifecycleOwner, Observer { pair ->
            val ids = pair.first
            val count = pair.second

            val question = resources.getQuantityString(
                    R.plurals.delete_note_or_notes_with_count_question, count, count)

            dialog = MaterialAlertDialogBuilder(requireContext())
                    .setTitle(question)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        listener?.onNotesDeleteRequest(mBookId, ids)
                    }
                    .setNegativeButton(R.string.cancel) { _, _ -> }
                    .show()
        })

        viewModel.appBar.mode.observeSingle(viewLifecycleOwner) { mode ->
            when (mode) {
                APP_BAR_DEFAULT_MODE -> {
                    viewAdapter.clearSelection()

                    topToolbarToDefault()
                    bottomToolbarToDefault()

                    binding.fab.run {
                        if (currentBook != null) {
                            setOnClickListener {
                                val narrowedId = viewModel.narrowedNoteId.value
                                val notePlace = if (narrowedId != null) {
                                    NotePlace(mBookId, narrowedId, Place.UNDER)
                                } else {
                                    NotePlace(mBookId)
                                }
                                val bookName = currentBook?.name
                                val templates = AppPreferences.captureTemplates(requireContext())
                                    .filter { it.targetBook.isBlank() || it.targetBook == bookName }
                                if (templates.isEmpty()) {
                                    listener?.onNoteNewRequest(notePlace)
                                } else {
                                    showCaptureTemplateChooser(templates, notePlace)
                                }
                            }
                            show()
                        } else {
                            hide()
                        }
                    }

                    sharedMainActivityViewModel.unlockDrawer()

                    appBarBackPressHandler.isEnabled = viewModel.isNarrowed()
                }

                APP_BAR_SELECTION_MODE -> {
                    topToolbarToMainSelection()
                    bottomToolbarToMainSelection()

                    binding.fab.hide()

                    sharedMainActivityViewModel.lockDrawer()

                    appBarBackPressHandler.isEnabled = true
                }

                APP_BAR_SELECTION_MOVE_MODE -> {
                    topToolbarToNextSelection()
                    bottomToolbarToNextSelection()

                    binding.fab.hide()

                    sharedMainActivityViewModel.lockDrawer()

                    appBarBackPressHandler.isEnabled = true
                }
            }
        }

        // Update widen button visibility and back handler when narrowed state changes
        viewModel.narrowedNoteId.observe(viewLifecycleOwner) {
            if (viewModel.appBar.mode.value != APP_BAR_DEFAULT_MODE) return@observe
            binding.topToolbar.menu.findItem(R.id.books_options_menu_item_widen_view)?.isVisible = viewModel.isNarrowed()
            appBarBackPressHandler.isEnabled = viewModel.isNarrowed()
        }
    }

    private fun setFlipperDisplayedChild(notes: List<NoteView>?) {
        if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG)

        if (currentBook == null) {
            viewModel.setFlipperDisplayedChild(BookViewModel.FlipperDisplayedChild.DOES_NOT_EXIST)

        } else if (notes == null) {
            viewModel.setFlipperDisplayedChild(BookViewModel.FlipperDisplayedChild.LOADING)

        } else if (notes.isNotEmpty() || viewAdapter.isPrefaceDisplayed()) {
            viewModel.setFlipperDisplayedChild(BookViewModel.FlipperDisplayedChild.LOADED)

        } else {
            viewModel.setFlipperDisplayedChild(BookViewModel.FlipperDisplayedChild.EMPTY)
        }
    }

    override fun onResume() {
        super.onResume()

        sharedMainActivityViewModel.setCurrentFragment(FRAGMENT_TAG)

        // Opening a notebook must stay instant: all heavy computation happens at app
        // foreground (MainActivity prewarm) or when a file is loaded. Here we only make sure
        // the in-memory stopwatch totals exist, in a lazy, non-blocking way.
        if (!com.orgzly.android.data.ClockAggregates.has(mBookId)) {
            binding.root.postDelayed({
                App.EXECUTORS.diskIO().execute {
                    dataRepository.prewarmClockForBook(mBookId)
                    binding.root.post {
                        if (isAdded && ::viewAdapter.isInitialized) {
                            viewAdapter.notifyDataSetChanged()
                        }
                    }
                }
            }, RECOMPUTE_DELAY_MS)
        }
    }

    override fun onDestroyView() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(clipboardChangedReceiver)

        super.onDestroyView()

        if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG)

        // Clean up coroutine job
        hideButtonJob?.cancel()
        hideButtonJob = null
    }

    override fun onDetach() {
        super.onDetach()

        if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG)

        listener = null
    }

    private fun parseArguments() {
        arguments?.let {
            require(it.containsKey(ARG_BOOK_ID)) {
                "No book id passed"
            }

            mBookId = it.getLong(ARG_BOOK_ID)

            require(mBookId > 0) {
                "Passed book id $mBookId is not valid"
            }
        } ?: throw IllegalArgumentException("No arguments passed")
    }

    /*
     * Actions
     */

    private fun newNoteRelativeToSelection(place: Place, noteId: Long) {
        listener?.onNoteNewRequest(NotePlace(mBookId, noteId, place))
    }

    private fun aiDecomposeSelected(noteId: Long) {
        if (AppPreferences.aiApiBase(requireContext()).isBlank() || AppPreferences.aiApiKey(requireContext()).isBlank()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ai_config)
                .setMessage(R.string.ai_need_config)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val noteTitle = dataRepository.getNoteView(noteId)?.note?.title.orEmpty()
        val hasChildren = dataRepository.getNoteChildren(noteId).isNotEmpty()

        showAiOptions(noteId, noteTitle, hasChildren)
    }

    /** First decomposition: ask for number/hint, then confirm overwrite when children exist. */
    private fun showAiOptions(noteId: Long, noteTitle: String, hasChildren: Boolean) {
        openAiOptionsDialog(noteId, noteTitle, AppPreferences.aiSubtaskCount(requireContext()), "") { count, hint ->
            if (hasChildren) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.ai_has_children_title)
                    .setMessage(R.string.ai_has_children_message)
                    .setPositiveButton(R.string.ai_overwrite) { _, _ -> aiRun(noteId, noteTitle, true, count, hint) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                aiRun(noteId, noteTitle, false, count, hint)
            }
        }
    }

    /** Regenerate with editable options; keeps the previously chosen overwrite decision. */
    private fun showAiOptionsAgain(noteId: Long, noteTitle: String, overwrite: Boolean, count: Int, hint: String) {
        openAiOptionsDialog(noteId, noteTitle, count, hint) { newCount, newHint ->
            aiRun(noteId, noteTitle, overwrite, newCount, newHint)
        }
    }

    private fun openAiOptionsDialog(
            noteId: Long,
            noteTitle: String,
            initialCount: Int,
            initialHint: String,
            onConfirm: (Int, String) -> Unit) {
        val context = requireContext()
        val density = context.resources.displayMetrics.density

        fun label(text: String): android.widget.TextView = android.widget.TextView(context).apply {
            setText(text)
        }

        val countInput = android.widget.EditText(context).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(initialCount.toString())
        }

        val hintInput = android.widget.EditText(context).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 4
            hint = getString(R.string.ai_hint_hint)
            if (initialHint.isNotEmpty()) setText(initialHint)
        }

        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((18 * density).toInt(), (12 * density).toInt(), (18 * density).toInt(), 0)
            addView(label(getString(R.string.ai_count_label)))
            addView(countInput)
            addView(label(getString(R.string.ai_hint_label)))
            addView(hintInput)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_options_title)
            .setMessage(noteTitle)
            .setView(layout)
            .setPositiveButton(R.string.ai_start) { _, _ ->
                val count = countInput.text.toString().toIntOrNull()?.coerceIn(1, 20)
                        ?: AppPreferences.aiSubtaskCount(context)
                val hint = hintInput.text.toString().trim()
                onConfirm(count, hint)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Ancestor headline chain + this note's content, used as AI context (title already sent). */
    private fun aiContextFor(noteId: Long): String? {
        val noteView = dataRepository.getNoteView(noteId) ?: return null

        val chain = dataRepository.getNoteAncestors(noteId)
                .mapNotNull { it.title.takeIf { title -> title.isNotBlank() } }
                .joinToString(" → ")
                .takeIf { it.isNotEmpty() }

        val content = noteView.note.content?.trim()?.take(2000)?.takeIf { it.isNotEmpty() }

        val parts = mutableListOf<String>()
        chain?.let { parts.add("父任务链：$it") }
        content?.let { parts.add("任务正文：\n$it") }

        return parts.joinToString("\n\n").ifEmpty { null }
    }

    private fun aiRun(noteId: Long, noteTitle: String, overwrite: Boolean, count: Int, hint: String?) {
        val context = requireContext()

        val progress = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_loading_title)
            .setMessage(getString(R.string.ai_loading_message, noteTitle))
            .setNegativeButton(R.string.ai_cancel) { dialog, _ -> dialog.cancel() }
            .setCancelable(true)
            .show()

        val base = AppPreferences.aiApiBase(context)
        val key = AppPreferences.aiApiKey(context)
        val model = AppPreferences.aiModel(context)
        val contextForAi = aiContextFor(noteId)

        App.EXECUTORS.diskIO().execute {
            val result: Result<List<com.orgzly.android.ai.AiSubtask>> = try {
                Result.success(AiApi.requestSubtasks(base, key, model, noteTitle, count, contextForAi, hint))
            } catch (e: Exception) {
                Result.failure(e)
            }

            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread

                if (progress.isShowing) progress.dismiss()

                result.fold(
                    onSuccess = { titles ->
                        if (titles.isEmpty()) {
                            showAiError(getString(R.string.ai_request_failed, "empty"))
                        } else {
                            aiPreview(noteId, noteTitle, titles, overwrite, count, hint)
                        }
                    },
                    onFailure = { e -> showAiError(e.message ?: getString(R.string.ai_no_network)) }
                )
            }
        }
    }

    private fun aiPreview(
        noteId: Long,
        noteTitle: String,
        titles: List<com.orgzly.android.ai.AiSubtask>,
        overwrite: Boolean,
        count: Int,
        hint: String?
    ) {
        val context = requireContext()

        val lines = titles.joinToString("\n\n") { subtask ->
            val content = subtask.content?.let { "\n    $it" } ?: ""
            "•  ${subtask.title}$content"
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_preview_title)
            .setMessage(getString(R.string.ai_preview_message, titles.size, noteTitle) + "\n\n" + lines)
            .setNegativeButton(R.string.ai_regenerate) { _, _ ->
                showAiOptionsAgain(noteId, noteTitle, overwrite, count, hint ?: "")
            }
            .setNeutralButton(R.string.cancel, null)
            .setPositiveButton(R.string.ai_confirm_insert) { _, _ -> aiInsert(noteId, titles, overwrite) }
            .show()
    }

    private fun aiInsert(noteId: Long, titles: List<com.orgzly.android.ai.AiSubtask>, overwrite: Boolean) {
        App.EXECUTORS.diskIO().execute {
            try {
                if (overwrite) {
                    val children = dataRepository.getNoteChildren(noteId).map { it.id }.toSet()
                    if (children.isNotEmpty()) dataRepository.deleteNotes(mBookId, children)
                }

                val todoState = AppPreferences.getFirstTodoState(requireContext())

                for (subtask in titles) {
                    dataRepository.createNote(
                        NotePayload(
                            title = subtask.title.trim(),
                            content = subtask.content,
                            state = todoState
                        ),
                        NotePlace(mBookId, noteId, Place.UNDER)
                    )
                }
            } catch (e: Exception) {
                requireActivity().runOnUiThread { showAiError(e.message ?: getString(R.string.ai_request_failed, e.javaClass.simpleName)) }
            }

            requireActivity().runOnUiThread {
                if (isAdded) {
                    Toast.makeText(requireContext(), getString(R.string.ai_inserted, titles.size), Toast.LENGTH_SHORT).show()
                    viewModel.appBar.toMode(APP_BAR_DEFAULT_MODE)
                }
            }
        }
    }

    private fun showAiError(message: String) {
        if (!isAdded) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.ai_request_failed, message))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** AI polish: send the whole note subtree, get an improved Org outline, replace on confirm. */
    private fun aiPolishSelected(noteId: Long) {
        if (AppPreferences.aiApiBase(requireContext()).isBlank() || AppPreferences.aiApiKey(requireContext()).isBlank()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ai_config)
                .setMessage(R.string.ai_need_config)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val context = requireContext()
        val noteTitle = dataRepository.getNoteView(noteId)?.note?.title.orEmpty()

        val hintInput = android.widget.EditText(context).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 4
            hint = getString(R.string.ai_hint_hint)
        }

        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val density = context.resources.displayMetrics.density
            setPadding((18 * density).toInt(), (6 * density).toInt(), (18 * density).toInt(), 0)
            addView(android.widget.TextView(context).apply { setText(getString(R.string.ai_hint_label)) })
            addView(hintInput)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_polish)
            .setMessage(getString(R.string.ai_polish_note) + "\n\n“$noteTitle”")
            .setView(layout)
            .setPositiveButton(R.string.ai_start) { _, _ ->
                val hint = hintInput.text.toString().trim()
                aiPolishRun(noteId, hint)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun aiPolishRun(noteId: Long, hint: String) {
        val context = requireContext()

        val progress = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.ai_polish_loading)
            .setNegativeButton(R.string.ai_cancel) { dialog, _ -> dialog.cancel() }
            .setCancelable(true)
            .show()

        val base = AppPreferences.aiApiBase(context)
        val key = AppPreferences.aiApiKey(context)
        val model = AppPreferences.aiModel(context)

        App.EXECUTORS.diskIO().execute {
            val result: Result<String> = try {
                val outline = dataRepository.exportNoteSubtreeAsOrg(noteId)
                if (outline.isBlank()) {
                    Result.failure(RuntimeException(getString(R.string.ai_no_network)))
                } else {
                    Result.success(AiApi.polish(base, key, model, outline, hint))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }

            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread

                if (progress.isShowing) progress.dismiss()

                result.fold(
                    onSuccess = { polished ->
                        if (polished.isBlank()) showAiError(getString(R.string.ai_request_failed, "empty"))
                        else aiPolishPreview(noteId, polished)
                    },
                    onFailure = { e -> showAiError(e.message ?: getString(R.string.ai_no_network)) }
                )
            }
        }
    }

    private fun aiPolishPreview(noteId: Long, polished: String) {
        val preview = polished.take(6000) + if (polished.length > 6000) "\n\n…" else ""

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ai_polish_preview_title)
            .setMessage(preview)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ai_polish_apply) { _, _ ->
                aiPolishReplace(noteId, polished)
            }
            .show()
    }

    private fun aiPolishReplace(noteId: Long, polished: String) {
        App.EXECUTORS.diskIO().execute {
            val error: String? = try {
                dataRepository.replaceNoteSubtreeFromOrg(noteId, polished)
                null
            } catch (e: Exception) {
                e.message ?: getString(R.string.ai_request_failed, e.javaClass.simpleName)
            }

            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread

                if (error != null) {
                    showAiError(error)
                } else {
                    Toast.makeText(requireContext(), R.string.ai_polish_preview_title, Toast.LENGTH_SHORT).show()
                    viewModel.appBar.toMode(APP_BAR_DEFAULT_MODE)
                }
            }
        }
    }

    private fun applyTemplateInBook(template: CaptureTemplate, contextualPlace: NotePlace) {
        if (normalizeHeadlinePath(template.targetHeadline) != null) {
            // Template has explicit headline — use the resolver (creates heading if missing)
            val result = CaptureTemplateResolver.resolve(requireContext(), dataRepository, template)
            if (result.warning == "notebook_not_found") {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.capture_template_target_book_not_found, template.targetBook),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            listener?.onNoteNewRequestWithTemplate(result.notePlace, template)
        } else {
            // No headline — use contextual placement (narrowed view or book root)
            listener?.onNoteNewRequestWithTemplate(contextualPlace, template)
        }
    }

    private fun showCaptureTemplateChooser(
        templates: List<CaptureTemplate>,
        notePlace: NotePlace
    ) {
        val items = (listOf(getString(R.string.new_note)) + templates.map {
            it.getDisplayName(getString(R.string.capture_template))
        }).toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_capture_template)
            .setItems(items) { _, index ->
                if (index == 0) {
                    listener?.onNoteNewRequest(notePlace)
                } else {
                    applyTemplateInBook(templates[index - 1], notePlace)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun moveNotes(offset: Int) {
        /* Sanity check. Should not ever happen. */
        if (viewAdapter.getSelection().count == 0) {
            Log.e(TAG, "Trying to move notes up while there are no notes selected")
            return
        }

        listener?.onNotesMoveRequest(mBookId, viewAdapter.getSelection().getIds(), offset)
    }

    /**
     * Paste notes.
     * @param place [Place]
     */
    private fun pasteNotes(place: Place, noteId: Long) {
        viewAdapter.clearSelection()

        listener?.onNotesPasteRequest(mBookId, noteId, place)
    }

    /** Builds the per-note outline tint (top-level ancestor color + depth) synchronously. */
    private fun computeOutlineTints(notes: List<NoteView>): Map<Long, com.orgzly.android.ui.notes.NoteItemViewBinder.OutlineTint> {
        val stack = HashMap<Int, Long>()
        val topByNote = HashMap<Long, Pair<Long, Int>>()

        notes.sortedBy { it.note.position.lft }.forEach { noteView ->
            val level = noteView.note.position.level
            val top = if (level <= 1) noteView.note.id else stack[level - 1] ?: noteView.note.id

            stack[level] = top
            val iterator = stack.keys.iterator()
            while (iterator.hasNext()) {
                if (iterator.next() > level) iterator.remove()
            }

            topByNote[noteView.note.id] = top to (level - 1).coerceAtLeast(0)
        }

        val topColors = HashMap<Long, Int?>()
        topByNote.values.map { it.first }.distinct().forEach { top ->
            topColors[top] = dataRepository.getOutlineColor(top)
        }

        val tints = HashMap<Long, com.orgzly.android.ui.notes.NoteItemViewBinder.OutlineTint>()
        topByNote.forEach { (noteId, pair) ->
            val (top, depth) = pair
            topColors[top]?.let { color ->
                tints[noteId] = com.orgzly.android.ui.notes.NoteItemViewBinder.OutlineTint(color, depth)
            }
        }

        return tints
    }

    private fun showOutlineColorPicker(noteId: Long) {
        val colors = listOf(
            0xFF3B82F6.toInt(), // blue
            0xFF14B8A6.toInt(), // teal
            0xFF22C55E.toInt(), // green
            0xFF84CC16.toInt(), // lime
            0xFFF59E0B.toInt(), // amber
            0xFFF97316.toInt(), // orange
            0xFFEF4444.toInt(), // red
            0xFFEC4899.toInt(), // pink
            0xFF8B5CF6.toInt(), // violet
            0xFF6366F1.toInt()  // indigo
        )

        val items = arrayOf(
            getString(R.string.outline_color_blue),
            getString(R.string.outline_color_teal),
            getString(R.string.outline_color_green),
            getString(R.string.outline_color_lime),
            getString(R.string.outline_color_amber),
            getString(R.string.outline_color_orange),
            getString(R.string.outline_color_red),
            getString(R.string.outline_color_pink),
            getString(R.string.outline_color_violet),
            getString(R.string.outline_color_indigo),
            getString(R.string.outline_color_none)
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.outline_color_title)
            .setItems(items) { _, which ->
                val color = if (which < colors.size) colors[which] else null
                App.EXECUTORS.diskIO().execute {
                    dataRepository.setOutlineColor(noteId, color)
                }
            }
            .show()
    }

    fun scrollToNoteIfSet(noteId: Long) {
        if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG, noteId)

        if (noteId > 0) {
            pendingScrollNoteId = noteId

            // Make sure the note is visible (its ancestors might be folded).
            App.EXECUTORS.diskIO().execute {
                dataRepository.unfoldToNote(noteId)
                binding.root.post { tryScrollToNote(noteId) }
            }
        }
    }

    private fun tryScrollToNote(noteId: Long) {
        if (noteId <= 0) return

        for (i in 0 until viewAdapter.itemCount) {
            if (viewAdapter.getItemId(i) == noteId) {
                scrollToPosition(i)

                binding.fragmentBookRecyclerView.post {
                    spotlightScrolledToView(i)
                }

                pendingScrollNoteId = 0
                arguments?.remove(ARG_NOTE_ID)

                /* Make sure we don't scroll again (for example after configuration change). */
                Handler().postDelayed({ arguments?.remove(ARG_NOTE_ID) }, 500)

                return
            }
        }
    }

    private fun scrollToPosition(position: Int) {
        if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG, position)
        layoutManager.scrollToPositionWithOffset(position, 0)
    }

    private fun spotlightScrolledToView(position: Int) {
        layoutManager.findViewByPosition(position)?.let {
            highlightScrolledToView(it)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun highlightScrolledToView(view: View) {
        val selectionBgColor = view.context.styledAttributes(intArrayOf(R.attr.colorSurface)) { typedArray ->
            typedArray.getColor(0, 0)
        }

        view.setBackgroundColor(selectionBgColor)

        // Reset background color on touch
        (activity as? CommonActivity)?.apply {
            runOnTouchEvent = Runnable {
                view.setBackgroundColor(0)
                runOnTouchEvent = null
            }
        }
    }

    private fun delete(ids: Set<Long>) {
        viewModel.requestNotesDelete(ids)
    }

    override fun getCurrentDrawerItemId(): String {
        return getDrawerItemId(mBookId)
    }

    override fun onNoteClick(view: View, position: Int, noteView: NoteView) {
        // Disable selection on narrowed root note - opening for edit still works
        if (viewModel.isNarrowed() && noteView.note.id == viewModel.narrowedNoteId.value) {
            if (!AppPreferences.isReverseNoteClickAction(context) && viewAdapter.getSelection().count == 0) {
                openNote(noteView.note.id)  // Allow edit in default mode when no selection
            }
            return
        }

        if (!AppPreferences.isReverseNoteClickAction(context)) {
            if (viewAdapter.getSelection().count > 0) {
                toggleNoteSelection(position, noteView)
            } else {
                openNote(noteView.note.id)
            }
        } else {
            toggleNoteSelection(position, noteView)
        }
    }

    override fun onNoteLongClick(view: View, position: Int, noteView: NoteView) {
        // Top-level note: offer outline color while keeping the original long-press actions.
        if (noteView.note.position.level == 1 && viewAdapter.getSelection().count == 0) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(noteView.note.title)
                .setItems(arrayOf(
                    getString(R.string.outline_color_title),
                    getString(R.string.select_note)
                )) { _, which ->
                    if (which == 0) {
                        showOutlineColorPicker(noteView.note.id)
                    } else {
                        performNoteLongClick(view, position, noteView)
                    }
                }
                .show()
            return
        }

        performNoteLongClick(view, position, noteView)
    }

    private fun performNoteLongClick(view: View, position: Int, noteView: NoteView) {
        // Disable selection on narrowed root note - opening for edit still works
        if (viewModel.isNarrowed() && noteView.note.id == viewModel.narrowedNoteId.value) {
            if (AppPreferences.isReverseNoteClickAction(context)) {
                openNote(noteView.note.id)  // Allow edit
            }
            return
        }

        if (!AppPreferences.isReverseNoteClickAction(context)) {
            toggleNoteSelection(position, noteView)
        } else {
            openNote(noteView.note.id)
        }
    }

    private fun openNote(id: Long) {
        listener?.onNoteOpen(id)
    }

    private fun toggleNoteSelection(position: Int, noteView: NoteView) {
        val noteId = noteView.note.id

        viewAdapter.getSelection().toggle(noteId)
        viewAdapter.notifyItemChanged(position)

        viewModel.appBar.toModeFromSelectionCount(viewAdapter.getSelection().count)
    }

    override fun onPrefaceClick() {
        if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG)

        currentBook?.let {
            listener?.onBookPrefaceEditRequest(it)
        }
    }

    private fun showFiletagsDialog() {
        val book = currentBook ?: return

        val view = layoutInflater.inflate(R.layout.dialog_filetags, null, false)
        val input = view.findViewById<MultiAutoCompleteTextView>(R.id.filetags_input)

        input.setTokenizer(SpaceTokenizer())

        // Pre-fill with current filetags
        book.filetags?.let { tags ->
            if (tags.isNotEmpty()) {
                input.setText(tags.toString())
            }
        }

        // Set up autocomplete from all known tags
        viewModel.tags.observe(viewLifecycleOwner) { tags ->
            context?.let {
                input.setAdapter(ArrayAdapter(it, R.layout.dropdown_item, tags))
            }
        }

        dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.file_tags)
            .setView(view)
            .setPositiveButton(R.string.set) { _, _ ->
                val newTagsText = input.text.toString().trim()
                val newPreface = updateFiletagsInPreface(book.preface, newTagsText)
                listener?.onBookPrefaceUpdate(mBookId, newPreface)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateFiletagsInPreface(preface: String?, tagsText: String): String {
        val filetagsLine = if (tagsText.isNotBlank()) {
            val tags = tagsText.split("\\s+".toRegex()).filter { it.isNotBlank() }
            "#+FILETAGS: :${tags.joinToString(":")}:"
        } else {
            null
        }

        if (preface.isNullOrBlank()) {
            return filetagsLine ?: ""
        }

        val lines = preface.lines().toMutableList()
        val existingIndex = lines.indexOfFirst {
            it.trimStart().startsWith("#+FILETAGS:", ignoreCase = true)
        }

        if (existingIndex >= 0) {
            if (filetagsLine != null) {
                lines[existingIndex] = filetagsLine
            } else {
                lines.removeAt(existingIndex)
            }
        } else if (filetagsLine != null) {
            lines.add(0, filetagsLine)
        }

        return lines.joinToString("\n")
    }

    private fun topToolbarToDefault() {
        if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG)

        binding.topToolbar.run {
            menu.clear()
            inflateMenu(R.menu.book_actions)

            ActivityUtils.keepScreenOnUpdateMenuItem(activity, menu)

            setNavigationIcon(R.drawable.ic_menu)

            setNavigationOnClickListener {
                sharedMainActivityViewModel.openDrawer()
            }

            if (currentBook == null || viewAdapter.getDataItemCount() == 0) {
                menu.removeItem(R.id.books_options_menu_item_cycle_visibility)
            }

            if (currentBook == null) {
                menu.removeItem(R.id.books_options_menu_book_preface)
                menu.removeItem(R.id.books_options_menu_book_filetags)
            }

            // Show/hide widen button based on narrowed state
            menu.findItem(R.id.books_options_menu_item_widen_view)?.isVisible = viewModel.isNarrowed()

            // Hide paste button if clipboard is empty, update title if not
            menu.findItem(R.id.book_actions_paste)?.apply {
                val count = NotesClipboard.count()

                if (count == 0) {
                    isVisible = false

                } else {
                    title = resources.getQuantityString(
                        R.plurals.paste_note_or_notes_with_count, count, count)

                    isVisible = true
                }
            }

            binding.topToolbar.setOnMenuItemClickListener { menuItem ->
                handleActionItemClick(menuItem.itemId, menuItem)
                true
            }

            val isAdvanced = AppPreferences.isDefaultToAdvancedQueryEnabled(requireContext())
            val activity = requireActivity()
            activity.setupSearchView(menu)
            val searchItem = menu.findItem(R.id.search_view)
            val searchView = searchItem.actionView as SearchView

            searchView.setOnSearchClickListener {
                searchView.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
                if (isAdvanced) {
                    val query = queryBuilder.build(Query(Condition.InBook(currentBook?.name ?: "")))
                    searchView.setQuery("$query ", false)
                }
            }

            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextChange(str: String?): Boolean {
                    return false
                }

                override fun onQueryTextSubmit(str: String): Boolean {
                    // Close search
                    searchItem.collapseActionView()
                    DisplayManager.displayQuery(
                        activity.supportFragmentManager,
                        when (isAdvanced) {
                            true -> str
                            else -> queryBuilder.build(
                                simpleFilterMapper.toQuery(
                                    str,
                                    SimpleFilter(
                                        books = setOfNotNull(currentBook?.name)
                                    )
                                )
                            )
                        },
                        null,
                        true,
                        true
                    )

                    return true
                }
            })

            setOnClickListener {
                scrollToPosition(0)
            }

            title = BookUtils.getFragmentTitleForBook(currentBook)
        }
    }

    private fun bottomToolbarToDefault() {
        if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG)

        binding.bottomToolbar.visibility = View.GONE

        activity?.setDecorFitsSystemWindowsForBottomToolbar(binding.bottomToolbar.visibility)
    }

    private fun topToolbarToMainSelection() {
        if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG)

        binding.topToolbar.run {
            menu.clear()
            inflateMenu(R.menu.book_cab_top)
            hideMenuItemsBasedOnSelection(menu)

            setNavigationIcon(R.drawable.ic_arrow_back)

            setNavigationOnClickListener {
                viewModel.appBar.handleOnBackPressed()
            }

            setOnMenuItemClickListener { menuItem ->
                handleActionItemClick(viewAdapter.getSelection().getIds(), menuItem.itemId, menuItem)
                true
            }

            setOnClickListener(null)

            title = viewAdapter.getSelection().count.toString()
        }
    }

    private fun bottomToolbarToMainSelection() {
        if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG)

        binding.bottomToolbar.run {
            menu.clear()
            inflateMenu(R.menu.book_cab_bottom)
            hideMenuItemsBasedOnSelection(menu)

            setOnMenuItemClickListener { menuItem ->
                handleActionItemClick(viewAdapter.getSelection().getIds(), menuItem.itemId, menuItem)
                true
            }

            visibility = View.VISIBLE

            activity?.setDecorFitsSystemWindowsForBottomToolbar(visibility)
        }
    }

    private fun topToolbarToNextSelection() {
        if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG)

        binding.topToolbar.run {
            menu.clear()
            inflateMenu(R.menu.book_cab_moving)
            hideMenuItemsBasedOnSelection(menu)

            setNavigationIcon(R.drawable.ic_arrow_back)

            setNavigationOnClickListener {
                viewModel.appBar.handleOnBackPressed()
            }

            setOnMenuItemClickListener { menuItem ->
                handleActionItemClick(
                    viewAdapter.getSelection().getIds(),
                    menuItem.itemId,
                    menuItem
                )
                true
            }

            setOnClickListener(null)

            title = viewAdapter.getSelection().count.toString()
        }
    }

    private fun bottomToolbarToNextSelection() {
        if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG)

        binding.bottomToolbar.run {
            menu.clear()
            inflateMenu(R.menu.book_cab_bottom)
            hideMenuItemsBasedOnSelection(menu)

            setOnMenuItemClickListener { menuItem ->
                handleActionItemClick(viewAdapter.getSelection().getIds(), menuItem.itemId, menuItem)
                false
            }

            visibility = View.VISIBLE

            activity?.setDecorFitsSystemWindowsForBottomToolbar(visibility)
        }
    }

    private fun hideMenuItemsBasedOnSelection(menu: Menu) {
        // Hide buttons that can't be used when multiple notes are selected
        for (id in listOf(R.id.paste, R.id.new_note, R.id.ai_decompose, R.id.ai_polish)) {
            menu.findItem(id)?.isVisible = viewAdapter.getSelection().count == 1
        }
    }

    private fun handleActionItemClick(ids: Set<Long>, itemId: Int, item: MenuItem? = null) {
        if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG, ids, itemId, item)

        if (ids.isEmpty()) {
            Log.e(TAG, "Cannot handle action when there are no items selected")
            viewModel.appBar.toMode(APP_BAR_DEFAULT_MODE)
            return
        }

        when (itemId) {
            R.id.ai_decompose -> {
                aiDecomposeSelected(ids.first())
                viewModel.appBar.toMode(APP_BAR_DEFAULT_MODE)
            }

            R.id.ai_polish -> {
                aiPolishSelected(ids.first())
                viewModel.appBar.toMode(APP_BAR_DEFAULT_MODE)
            }

            R.id.note_popup_new_above,
            R.id.new_note_above -> {
                newNoteRelativeToSelection(Place.ABOVE, ids.first())
                viewModel.appBar.toMode(APP_BAR_DEFAULT_MODE)
            }

            R.id.note_popup_new_under,
            R.id.new_note_under -> {
                newNoteRelativeToSelection(Place.UNDER, ids.first())
                viewModel.appBar.toMode(APP_BAR_DEFAULT_MODE)
            }

            R.id.note_popup_new_below,
            R.id.new_note_below -> {
                newNoteRelativeToSelection(Place.BELOW, ids.first())
                viewModel.appBar.toMode(APP_BAR_DEFAULT_MODE)
            }

            R.id.move -> {
                viewModel.appBar.toMode(APP_BAR_SELECTION_MOVE_MODE)
            }

            in scheduledTimeButtonIds(),
            in deadlineTimeButtonIds() ->
                displayTimestampDialog(itemId, ids)

            R.id.note_popup_delete,
            R.id.delete_note -> {
                delete(ids)

                // TODO: Wait for user confirmation (dialog close) before doing this
                // TODO: Don't do it if canceled
                viewModel.appBar.toMode(APP_BAR_DEFAULT_MODE)
            }

            R.id.share_note -> {
                shareNoteParts(ids, SharePart.NOTE)
                viewModel.appBar.toMode(APP_BAR_DEFAULT_MODE)
            }

            R.id.share_title -> {
                shareNoteParts(ids, SharePart.TITLE)
                viewModel.appBar.toMode(APP_BAR_DEFAULT_MODE)
            }

            R.id.share_content -> {
                shareNoteParts(ids, SharePart.CONTENT)
                viewModel.appBar.toMode(APP_BAR_DEFAULT_MODE)
            }

            R.id.cut -> {
                listener?.onNotesCutRequest(mBookId, ids)
                viewModel.appBar.toMode(APP_BAR_DEFAULT_MODE)
            }

            R.id.copy -> {
                listener?.onNotesCopyRequest(mBookId, ids)
                viewModel.appBar.toMode(APP_BAR_DEFAULT_MODE)
            }

            R.id.paste_above -> {
                pasteNotes(Place.ABOVE, ids.first())
                viewModel.appBar.toMode(APP_BAR_DEFAULT_MODE)
            }

            R.id.note_popup_refile,
            R.id.refile ->
                viewModel.refile(ids)

            R.id.paste_under -> {
                pasteNotes(Place.UNDER, ids.first())
                viewModel.appBar.toMode(APP_BAR_DEFAULT_MODE)
            }

            R.id.paste_below -> {
                pasteNotes(Place.BELOW, ids.first())
                viewModel.appBar.toMode(APP_BAR_DEFAULT_MODE)
            }

            R.id.notes_action_move_up ->
                moveNotes(-1)

            R.id.notes_action_move_down ->
                moveNotes(1)

            R.id.notes_action_move_left ->
                listener?.onNotesPromoteRequest(ids)

            R.id.notes_action_move_right ->
                listener?.onNotesDemoteRequest(ids)

            R.id.note_popup_set_state,
            R.id.state ->
                listener?.let {
                    openNoteStateDialog(it, ids, null)
                }

            R.id.note_popup_toggle_state,
            R.id.toggle_state -> {
                listener?.onStateToggleRequest(ids)
            }

            R.id.note_popup_clock_in,
            R.id.clock_in -> {
                listener?.onClockIn(ids)
            }

            R.id.note_popup_clock_out,
            R.id.clock_out -> {
                listener?.onClockOut(ids)
            }

            R.id.note_popup_clock_cancel,
            R.id.clock_cancel -> {
                listener?.onClockCancel(ids)
            }

            R.id.note_popup_focus,
            R.id.focus ->
                listener?.onNoteFocusInBookRequest(ids.first())

            R.id.note_popup_narrow ->
                viewModel.narrowToSubtree(ids.first())
        }
    }

    private fun handleActionItemClick(itemId: Int, item: MenuItem? = null) {
        if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG, itemId, item)

        when (itemId) {
            R.id.books_options_menu_item_cycle_visibility -> {
                viewModel.cycleVisibility()
            }

            R.id.books_options_menu_item_widen_view -> {
                viewModel.widenView()
            }

            R.id.book_actions_paste -> {
                pasteNotes(Place.UNDER, 0)
            }

            R.id.books_options_menu_book_preface -> {
                onPrefaceClick()
            }

            R.id.books_options_menu_book_filetags -> {
                showFiletagsDialog()
            }

            R.id.keep_screen_on -> {
                if (item != null) {
                    dialog = ActivityUtils.keepScreenOnToggle(activity, item)
                }
            }

            R.id.sync -> {
                SyncRunner.startSync()
            }

            R.id.activity_action_settings -> {
                startActivity(Intent(context, SettingsActivity::class.java))
            }
        }
    }

    private fun setupJumpToEndButton(recyclerView: RecyclerView) {
        // Initially hide the button
        binding.jumpToEndFab.hide()

        // Set up the click listener
        binding.jumpToEndFab.run {
            setOnClickListener {
                val adapter = binding.fragmentBookRecyclerView.adapter
                val targetPosition: Int? =
                    when (jumpButtonDirection) {
                        ScrollDirection.UP -> 0
                        ScrollDirection.DOWN -> adapter?.itemCount?.minus(1)?.let {
                            if (it <= 0)
                                null
                            else
                                it
                        }
                    }
                if (targetPosition == null) return@setOnClickListener // Nothing to scroll to

                val layoutManager = binding.fragmentBookRecyclerView.layoutManager as? LinearLayoutManager
                if (layoutManager == null) {
                    // Fallback or log error if layout manager is not LinearLayoutManager
                    binding.fragmentBookRecyclerView.smoothScrollToPosition(targetPosition)
                    return@setOnClickListener
                }

                val currentPosition = layoutManager.findFirstVisibleItemPosition()
                if (currentPosition == RecyclerView.NO_POSITION) {
                    // If current position is unknown, maybe just smooth scroll
                    binding.fragmentBookRecyclerView.smoothScrollToPosition(targetPosition)
                    return@setOnClickListener
                }


                // --- Conditional Logic ---
                val totalItemCount = adapter?.itemCount ?: 0
                val scrollDistance = abs(targetPosition - currentPosition)

                // Define thresholds (adjust as needed)
                val sizeThreshold = 500 // Jump instantly if total items > threshold
                val distanceThreshold = 50 // Jump instantly if distance to scroll > threshold

                if (totalItemCount > sizeThreshold || scrollDistance > distanceThreshold) {
                    // Jump instantly for large lists or long distances
                    layoutManager.scrollToPositionWithOffset(targetPosition, 0) // Or just scrollToPosition(targetPosition)
                } else {
                    // Smooth scroll for smaller lists/distances
                    binding.fragmentBookRecyclerView.smoothScrollToPosition(targetPosition)
                }
            }
        }

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                
                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                val lastVisibleItem = layoutManager.findLastCompletelyVisibleItemPosition()
                val totalItemCount = layoutManager.itemCount

                when {
                    // At bottom - hide button
                    lastVisibleItem >= totalItemCount - 1 -> {
                        binding.jumpToEndFab.hide()
                    }
                    // Scrolling fast - show button
                    abs(dy) > SCROLL_SPEED_THRESHOLD -> {
                        binding.jumpToEndFab.show()
                        scheduleButtonHide()

                        if (dy > 0) {
                            jumpButtonDirection = ScrollDirection.DOWN
                            binding.jumpToEndFab.setRotation(0f)
                        } else {
                            jumpButtonDirection = ScrollDirection.UP
                            binding.jumpToEndFab.setRotation(180f)
                        }
                    }
                }
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                when (newState) {
                    RecyclerView.SCROLL_STATE_IDLE -> {
                        // Schedule hide when scrolling stops
                        scheduleButtonHide()
                    }
                    RecyclerView.SCROLL_STATE_DRAGGING -> {
                        // Cancel scheduled hide when user starts dragging
                        hideButtonJob?.cancel()
                    }
                }
            }
        })
    }

    private fun scheduleButtonHide() {
        hideButtonJob?.cancel()
        hideButtonJob = lifecycleScope.launch {
            delay(FADE_DELAY)
            binding.jumpToEndFab.hide()
        }
    }

    interface Listener : NotesFragment.Listener {
        fun onBookPrefaceEditRequest(book: Book)

        fun onBookPrefaceUpdate(bookId: Long, preface: String)

        fun onNotesDeleteRequest(bookId: Long, noteIds: Set<Long>)

        fun onNotesCutRequest(bookId: Long, noteIds: Set<Long>)
        fun onNotesCopyRequest(bookId: Long, noteIds: Set<Long>)
        fun onNotesPasteRequest(bookId: Long, noteId: Long, place: Place)

        fun onNotesPromoteRequest(noteIds: Set<Long>)
        fun onNotesDemoteRequest(noteIds: Set<Long>)

        fun onNotesMoveRequest(bookId: Long, noteIds: Set<Long>, offset: Int)
    }

    companion object {
        private val TAG = BookFragment::class.java.name

        /** Name used for [android.app.FragmentManager].  */
        @JvmField
        val FRAGMENT_TAG: String = BookFragment::class.java.name

        /* Jump to Bottom Consts */
        private const val FADE_DELAY = 2000L
        private const val SCROLL_SPEED_THRESHOLD = 50

        /* Arguments. */
        private const val ARG_BOOK_ID = "bookId"
        private const val ARG_NOTE_ID = "noteId"

        /**
         * @param bookId Book ID
         * @param noteId Set position (scroll to) this note, if greater then zero
         */
        @JvmStatic
        fun getInstance(bookId: Long, noteId: Long): BookFragment {
            val fragment = BookFragment()

            val args = Bundle()
            args.putLong(ARG_BOOK_ID, bookId)
            args.putLong(ARG_NOTE_ID, noteId)

            fragment.arguments = args

            return fragment
        }

        @JvmStatic
        fun getDrawerItemId(bookId: Long): String {
            return "$TAG $bookId"
        }
    }
}
