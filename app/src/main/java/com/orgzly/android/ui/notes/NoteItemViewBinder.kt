package com.orgzly.android.ui.notes

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.constraintlayout.widget.ConstraintLayout
import com.orgzly.R
import com.orgzly.android.App
import com.orgzly.android.db.NotesClipboard
import com.orgzly.android.db.entity.Note
import com.orgzly.android.db.entity.NoteView
import com.orgzly.android.db.entity.toList
import com.orgzly.android.prefs.AppPreferences
import com.orgzly.android.ui.TimeType
import com.orgzly.android.ui.util.TitleGenerator
import com.orgzly.android.ui.util.styledAttributes
import com.orgzly.android.usecase.NoteToggleFolding
import com.orgzly.android.usecase.NoteToggleFoldingSubtree
import com.orgzly.android.usecase.NoteUpdateContent
import com.orgzly.android.usecase.UseCaseRunner
import com.orgzly.android.util.UserTimeFormatter
import com.orgzly.databinding.ItemAgendaDividerBinding
import com.orgzly.databinding.ItemHeadBinding

class NoteItemViewBinder(private val context: Context, private val inBook: Boolean) {
    private val attrs: Attrs = Attrs.obtain(context)

    /** Outline tint (accent color + depth below the top-level note) per note id. */
    data class OutlineTint(val color: Int, val depth: Int)

    var outlineTints: Map<Long, OutlineTint> = emptyMap()

    /** Applies the stepped outline color (light fill); skipped for the selected row. */
    fun applyOutlineTint(holder: NoteItemViewHolder, note: Note, selected: Boolean) {
        val tintView = holder.binding.itemHeadTint

        if (selected || !inBook) {
            tintView.background = null
            return
        }

        val tint = outlineTints[note.id]

        if (tint == null) {
            tintView.background = null
            return
        }

        val base = com.google.android.material.color.MaterialColors.getColor(
            tintView,
            com.google.android.material.R.attr.colorSurfaceContainerLow
        )
        // Bigger steps between levels so the hierarchy is obvious.
        val alpha = (0.36f - tint.depth * 0.10f).coerceAtLeast(0.06f)
        val blended = androidx.core.graphics.ColorUtils.blendARGB(base, tint.color, alpha)

        val drawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(blended)
        }

        tintView.background = drawable
    }

    private val titleGenerator: TitleGenerator

    private val userTimeFormatter: UserTimeFormatter

    // Level offset for narrowing - null when not narrowed, offset value when narrowed
    var levelOffset: Int? = null

    init {

        val titleAttributes = TitleGenerator.TitleAttributes(
                attrs.todoColor,
                attrs.doneColor,
                attrs.postTitleTextSize,
                attrs.postTitleTextColor)

        titleGenerator = TitleGenerator(context, inBook, titleAttributes)

        userTimeFormatter = UserTimeFormatter(context)
    }

    fun bind(holder: NoteItemViewHolder, noteView: NoteView, agendaTimeType: TimeType? = null) {
        setupTitle(holder, noteView)
        setupBookName(holder, noteView)
        setupPlanningTimes(holder, noteView, agendaTimeType)
        setupContent(holder, noteView.note)
        setupIndent(holder, noteView.note)
        setupLevel(holder, noteView.note)
        setupBullet(holder, noteView.note)
        setupFoldingButtons(holder, noteView.note)
        setupClock(holder, noteView)
        setupAlpha(holder, noteView)
    }

    private fun setupBookName(holder: NoteItemViewHolder, noteView: NoteView) {
        if (inBook) {
            holder.binding.itemHeadBookNameIcon.visibility = View.GONE
            holder.binding.itemHeadBookNameText.visibility = View.GONE
            holder.binding.itemHeadBookNameBeforeNoteText.visibility = View.GONE

        } else {
            when (Integer.valueOf(AppPreferences.bookNameInSearchResults(context))) {
                0 -> { // Hide
                    holder.binding.itemHeadBookNameIcon.visibility = View.GONE
                    holder.binding.itemHeadBookNameText.visibility = View.GONE

                    holder.binding.itemHeadBookNameBeforeNoteText.visibility = View.GONE
                }

                1 -> { // Show before note
                    holder.binding.itemHeadBookNameIcon.visibility = View.GONE
                    holder.binding.itemHeadBookNameText.visibility = View.GONE

                    holder.binding.itemHeadBookNameBeforeNoteText.text = noteView.bookName
                    holder.binding.itemHeadBookNameBeforeNoteText.visibility = View.VISIBLE
                }

                2 -> { // Show under note
                    holder.binding.itemHeadBookNameText.text = noteView.bookName
                    holder.binding.itemHeadBookNameText.visibility = View.VISIBLE
                    holder.binding.itemHeadBookNameIcon.visibility = View.VISIBLE

                    holder.binding.itemHeadBookNameBeforeNoteText.visibility = View.GONE
                }
            }
        }
    }

    private fun setupTitle(holder: NoteItemViewHolder, noteView: NoteView) {
        holder.binding.itemHeadTitle.setVisibleText(generateTitle(noteView))
    }

    fun generateTitle(noteView: NoteView): CharSequence {
        return titleGenerator.generateTitle(noteView)
    }

    private fun setupContent(holder: NoteItemViewHolder, note: Note) {
        if (note.hasContent() && titleGenerator.shouldDisplayContent(note)) {
            if (AppPreferences.isFontMonospaced(context)) {
                holder.binding.itemHeadContent.setTypeface(Typeface.MONOSPACE)
            }

            holder.binding.itemHeadContent.setSourceText(note.content)

            /* If content changes (for example by toggling the checkbox), update the note. */
            holder.binding.itemHeadContent.setOnUserTextChangeListener { str ->
                val useCase = NoteUpdateContent(note.id, str)

                App.EXECUTORS.diskIO().execute {
                    UseCaseRunner.run(useCase)
                }
            }

            holder.binding.itemHeadContent.visibility = View.VISIBLE

        } else {
            holder.binding.itemHeadContent.visibility = View.GONE
        }
    }

    private fun setupPlanningTimes(holder: NoteItemViewHolder, noteView: NoteView, agendaTimeType: TimeType?) {

        fun setupPlanningTime(textView: TextView, iconView: ImageView, value: String?) {
            if (value != null && AppPreferences.displayPlanning(context)) {
                val range = com.orgzly.org.datetime.OrgRange.parse(value)
                textView.text = userTimeFormatter.formatAll(range)
                textView.visibility = View.VISIBLE
                iconView.visibility = View.VISIBLE

            } else {
                textView.visibility = View.GONE
                iconView.visibility = View.GONE
            }
        }

        var scheduled = noteView.scheduledRangeString
        var deadline = noteView.deadlineRangeString
        var event = noteView.eventString

        // In Agenda only display time responsible for item's presence
        when (agendaTimeType) {
            TimeType.SCHEDULED -> {
                deadline = null
                event = null
            }
            TimeType.DEADLINE -> {
                scheduled = null
                event = null
            }
            TimeType.EVENT -> {
                scheduled = null
                deadline = null
            }
            else -> {
            }
        }

        setupPlanningTime(
                holder.binding.itemHeadScheduledText,
                holder.binding.itemHeadScheduledIcon,
                scheduled)

        setupPlanningTime(
                holder.binding.itemHeadDeadlineText,
                holder.binding.itemHeadDeadlineIcon,
                deadline)

        setupPlanningTime(
                holder.binding.itemHeadEventText,
                holder.binding.itemHeadEventIcon,
                event)

        setupPlanningTime(
                holder.binding.itemHeadClosedText,
                holder.binding.itemHeadClosedIcon,
                noteView.closedRangeString)
    }

    /** Manual stopwatch control: icon on every row, tap to start/stop, ticking while running. */
    private fun setupClock(holder: NoteItemViewHolder, noteView: NoteView) {
        val binding = holder.binding

        holder.stopClockTicker()

        binding.itemHeadClock.visibility = View.VISIBLE

        // Subtree stopwatch total comes from the in-memory per-book cache (kept fresh by
        // DataRepository); fall back to this note's own CLOCKED_SECONDS when not computed yet.
        val accumulated = com.orgzly.android.data.ClockAggregates.aggregate(
                noteView.note.position.bookId, noteView.note.id)
                ?: noteView.clockedSeconds?.toLongOrNull()
                ?: 0L
        val startMillis = noteView.clockStart?.toLongOrNull()
        val running = startMillis != null

        binding.itemHeadClockText.text = clockText(accumulated, startMillis)
        binding.itemHeadClockText.visibility = if (accumulated > 0L || running) View.VISIBLE else View.GONE

        binding.itemHeadClockIcon.imageAlpha = if (running) 255 else 120

        binding.itemHeadClock.setOnClickListener {
            toggleClock(noteView.note.id)
        }

        if (running && startMillis != null) {
            startClockTicker(holder, accumulated, startMillis)
        }
    }

    private fun clockText(accumulatedSeconds: Long, startMillis: Long?): String {
        val elapsed = startMillis?.let { ((System.currentTimeMillis() - it) / 1000).coerceAtLeast(0L) } ?: 0L
        return formatClock(accumulatedSeconds + elapsed)
    }

    private fun formatClock(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    }

    private fun startClockTicker(holder: NoteItemViewHolder, accumulatedSeconds: Long, startMillis: Long) {
        val textView = holder.binding.itemHeadClockText

        val ticker = object : Runnable {
            override fun run() {
                textView.text = clockText(accumulatedSeconds, startMillis)
                textView.postDelayed(this, 1000)
            }
        }

        holder.clockTicker = ticker
        ticker.run()
    }

    private fun toggleClock(noteId: Long) {
        App.EXECUTORS.diskIO().execute {
            UseCaseRunner.run(com.orgzly.android.usecase.ClockToggle(noteId))
        }
    }

    /** Set alpha for done and archived items. */
    private fun setupAlpha(holder: NoteItemViewHolder, noteView: NoteView) {
        val state = noteView.note.state
        val tags = noteView.note.tags.toList()
        val inheritedTags = noteView.getInheritedTagsList()

        val isDone = state != null && AppPreferences.doneKeywordsSet(context).contains(state)
        val isArchived = tags.contains(ARCHIVE_TAG) || inheritedTags.contains(ARCHIVE_TAG)
        val isCut = NotesClipboard.cutNoteIds().contains(noteView.note.id)

        val alphaValue = if (isDone || isArchived || isCut) {
            0.45f
        } else {
            1.0f
        }

        holder.binding.run {
            listOf(
                itemHeadTitle,
                itemHeadLevel,
                itemHeadBookNameIcon,
                itemHeadBookNameText,
                itemHeadBookNameBeforeNoteText,
                itemHeadScheduledIcon,
                itemHeadScheduledText,
                itemHeadDeadlineIcon,
                itemHeadDeadlineText,
                itemHeadEventIcon,
                itemHeadEventText,
                itemHeadClosedText,
                itemHeadClosedIcon,
                itemHeadClock,
                itemHeadContent
            ).forEach {
                it.alpha = alphaValue
            }
        }
    }

    /** Heading level marker (H1, H2, ...). */
    private fun setupLevel(holder: NoteItemViewHolder, note: Note) {
        holder.binding.itemHeadLevel.text = "H" + note.position.level
    }

    /**
     * Set indentation views, depending on note level.
     * When narrowed, levelOffset hides parent indentation so narrowed note appears as root.
     */
    private fun setupIndent(holder: NoteItemViewHolder, note: Note) {
        val container = holder.binding.itemHeadIndentContainer

        // Ensure level is never negative
        val level = if (inBook) {
            maxOf(0, note.position.level - 1 - (levelOffset ?: 0))
        } else {
            0
        }

        when {
            container.childCount < level -> { // More levels needed
                // Make all existing levels visible
                for (i in 1..container.childCount) {
                    container.getChildAt(i - 1).visibility = View.VISIBLE
                }

                // Inflate the rest
                (container.childCount + 1..level).forEach { i ->
                    View.inflate(container.context, R.layout.indent, container)
                }
            }

            level < container.childCount -> { // Too many levels
                // Make required levels visible
                for (i in 1..level) {
                    container.getChildAt(i - 1).visibility = View.VISIBLE
                }

                // Hide the rest
                for (i in level + 1..container.childCount) {
                    container.getChildAt(i - 1).visibility = View.GONE
                }
            }

            else -> // Make all visible
                for (i in 1..container.childCount) {
                    container.getChildAt(i - 1).visibility = View.VISIBLE
                }
        }

        container.tag = level
    }

    /**
     * Change bullet appearance depending on folding state and number of descendants.
     */
    private fun setupBullet(holder: NoteItemViewHolder, note: Note) {
        if (!inBook) {
            holder.binding.itemHeadBullet.visibility = View.GONE
            return
        }

        if (note.position.descendantsCount > 0) { // With descendants
            if (note.position.isFolded) { // Folded
                holder.binding.itemHeadBullet.setImageResource(R.drawable.bullet_folded)
            } else { // Not folded
                holder.binding.itemHeadBullet.setImageResource(R.drawable.bullet)
            }
        } else { // No descendants
            holder.binding.itemHeadBullet.setImageResource(R.drawable.bullet)
        }

        holder.binding.itemHeadBullet.visibility = View.VISIBLE

    }

    private fun setupFoldingButtons(holder: NoteItemViewHolder, note: Note) {
        if (updateFoldingButtons(context, note, holder)) {
            // Folding button
            holder.binding.itemHeadFoldButton.setOnClickListener {
                toggleFoldedState(note.id)
            }
            holder.binding.itemHeadFoldButton.setOnLongClickListener {
                toggleFoldedStateForSubtree(note.id)
            }

            // Bullet
            holder.binding.itemHeadBullet.setOnClickListener {
                toggleFoldedState(note.id)
            }
            holder.binding.itemHeadBullet.setOnLongClickListener {
                toggleFoldedStateForSubtree(note.id)
            }

        } else {
            // Folding button
            holder.binding.itemHeadFoldButton.setOnClickListener(null)
            holder.binding.itemHeadFoldButton.setOnLongClickListener(null)

            // Bullet
            holder.binding.itemHeadBullet.setOnClickListener(null)
            holder.binding.itemHeadBullet.setOnLongClickListener(null)
        }
    }

    /**
     * Change folding button appearance.
     */
    private fun updateFoldingButtons(context: Context, note: Note, holder: NoteItemViewHolder): Boolean {
        var isVisible = false

        if (AppPreferences.isSearchFoldable(context) || inBook) {
            val contentFoldable = note.hasContent() &&
                    AppPreferences.isNotesContentFoldable(context) &&
                    AppPreferences.isNotesContentDisplayedInList(context)

            if (note.position.descendantsCount > 0 || contentFoldable) {
                isVisible = true

                /* Type of the fold button. */
                if (note.position.isFolded) {
                    holder.binding.itemHeadFoldButtonText.setText(R.string.unfold_button_character)
                } else {
                    holder.binding.itemHeadFoldButtonText.setText(R.string.fold_button_character)
                }
            }
        }

        if (isVisible) {
            holder.binding.itemHeadFoldButton.visibility = View.VISIBLE
            holder.binding.itemHeadFoldButtonText.visibility = View.VISIBLE
        } else {
            if (AppPreferences.isSearchFoldable(context) || inBook) { // Leave invisible for padding
                holder.binding.itemHeadFoldButton.visibility = View.INVISIBLE
                holder.binding.itemHeadFoldButtonText.visibility = View.INVISIBLE
            } else {
                holder.binding.itemHeadFoldButton.visibility = View.GONE
                holder.binding.itemHeadFoldButtonText.visibility = View.GONE
            }
        }

        // Add horizontal padding when in search results (no bullet, no folding button)
        val horizontalPadding = context.resources.getDimension(R.dimen.screen_edge).toInt()
        if (!(inBook || AppPreferences.isSearchFoldable(context))) {
            holder.binding.itemHeadContainer.setPadding(
                    horizontalPadding,
                    holder.binding.itemHeadContainer.paddingTop,
                    horizontalPadding,
                    holder.binding.itemHeadContainer.paddingBottom)

//            (holder.binding.itemHeadContainer.layoutParams as ConstraintLayout.LayoutParams).apply {
//                val right = if (holder.binding.itemHeadFoldButtonText.visibility == View.GONE) {
//                    context.resources.getDimension(R.dimen.screen_edge).toInt()
//                } else {
//                    0
//                }
//
//                setMargins(leftMargin, topMargin, right, bottomMargin)
//            }
        } else {
            holder.binding.itemHeadContainer.setPadding(
                    horizontalPadding,
                    holder.binding.itemHeadContainer.paddingTop,
                    holder.binding.itemHeadContainer.paddingRight,
                    holder.binding.itemHeadContainer.paddingBottom)
        }

        return isVisible
    }

    // TODO: Move out
    private fun toggleFoldedState(id: Long) {
        App.EXECUTORS.diskIO().execute {
            UseCaseRunner.run(NoteToggleFolding(id))
        }
    }

    private fun toggleFoldedStateForSubtree(id: Long): Boolean {
        App.EXECUTORS.diskIO().execute {
            UseCaseRunner.run(NoteToggleFoldingSubtree(id))
        }

        return true
    }

    companion object {
        const val ARCHIVE_TAG = "ARCHIVE"

        /**
         * Setup margins or padding for different list density settings.
         */
        fun setupSpacingForDensitySetting(context: Context, binding: ItemAgendaDividerBinding) {
            val margins = getMarginsForListDensity(context)

            binding.root.setPadding(
                    binding.root.paddingLeft,
                    margins.first,
                    binding.root.paddingRight,
                    margins.first)

        }
        fun setupSpacingForDensitySetting(context: Context, binding: ItemHeadBinding) {
            val margins = getMarginsForListDensity(context)

            // Whole item margins
            listOf(binding.itemHeadTop, binding.itemHeadBottom).forEach {
                (it.layoutParams as ConstraintLayout.LayoutParams).apply {
                    height = margins.first
                }
            }

            // Spacing for views inside the item
            val views = arrayOf(
                    binding.itemHeadScheduledText,
                    binding.itemHeadScheduledIcon,
                    binding.itemHeadDeadlineText,
                    binding.itemHeadDeadlineIcon,
                    binding.itemHeadEventText,
                    binding.itemHeadEventIcon,
                    binding.itemHeadClosedIcon,
                    binding.itemHeadClosedText,
                    binding.itemHeadContent)

            for (view in views) {
                (view.layoutParams as ConstraintLayout.LayoutParams).apply {
                    setMargins(leftMargin, margins.second, rightMargin, bottomMargin)
                }
            }
        }

        private fun getMarginsForListDensity(context: Context): Pair<Int, Int> {
            val itemMargins: Int
            val belowTitleMargins: Int

            val density = AppPreferences.notesListDensity(context)

            val res = context.resources

            when (density) {
                context.getString(R.string.pref_value_list_density_comfortable) -> { // Comfortable
                    itemMargins = res.getDimension(R.dimen.item_head_padding_comfortable).toInt()
                    belowTitleMargins = res.getDimension(R.dimen.item_head_below_title_padding_comfortable).toInt()
                }

                context.getString(R.string.pref_value_list_density_compact) -> { // Compact
                    itemMargins = res.getDimension(R.dimen.item_head_padding_compact).toInt()
                    belowTitleMargins = res.getDimension(R.dimen.item_head_below_title_padding_compact).toInt()
                }

                else -> { // Cozy
                    itemMargins = res.getDimension(R.dimen.item_head_padding_cozy).toInt()
                    belowTitleMargins = res.getDimension(R.dimen.item_head_below_title_padding_cozy).toInt()
                }
            }

            return Pair(itemMargins, belowTitleMargins)
        }
    }


    private data class Attrs(
        @param:ColorInt val todoColor: Int,
        @param:ColorInt val doneColor: Int,
        val postTitleTextSize: Int,
        @param:ColorInt val postTitleTextColor: Int
    ) {
        companion object {
            @SuppressWarnings("ResourceType")
            fun obtain(context: Context): Attrs {
                return context.styledAttributes(
                    intArrayOf(
                        R.attr.item_head_state_todo_color,
                        R.attr.item_head_state_done_color,
                        R.attr.item_head_post_title_text_size,
                        android.R.attr.textColorTertiary)) { typedArray ->

                    Attrs(
                        typedArray.getColor(0, 0),
                        typedArray.getColor(1, 0),
                        typedArray.getDimensionPixelSize(2, 0),
                        typedArray.getColor(3, 0))
                }
            }
        }
    }
}