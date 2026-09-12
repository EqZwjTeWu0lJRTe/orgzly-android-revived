package com.orgzly.android.ui.notes.today

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.orgzly.R
import com.orgzly.android.db.entity.NoteView
import com.google.android.material.card.MaterialCardView

class TodayAdapter(
    private val onClick: (NoteView) -> Unit,
    private val onDone: (NoteView) -> Unit,
    private val compact: Boolean
) : ListAdapter<TodayAdapter.Row, RecyclerView.ViewHolder>(DIFF) {

    enum class Group(val color: Int) {
        OVERDUE(Color.parseColor("#E53935")),   // red
        DUE(Color.parseColor("#FB8C00")),       // orange
        SCHEDULED(Color.parseColor("#1E88E5")), // blue
        REPEATING(Color.parseColor("#43A047"))  // green
    }

    sealed class Row {
        data class Header(val title: String, val count: Int, val group: Group) : Row()
        data class Task(val noteView: NoteView, val group: Group) : Row()
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_TASK = 1

        private val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(oldItem: Row, newItem: Row): Boolean = when {
                oldItem is Row.Header && newItem is Row.Header -> oldItem.title == newItem.title
                oldItem is Row.Task && newItem is Row.Task -> oldItem.noteView.note.id == newItem.noteView.note.id
                else -> false
            }

            override fun areContentsTheSame(oldItem: Row, newItem: Row): Boolean = oldItem == newItem
        }

        /** Removes the state prefix and a meaningless zero percent cookie. */
        fun cleanTitle(noteView: NoteView): String {
            var title = noteView.note.title
            title = title.replace(Regex("\\[0%]"), " ")
                    .replace(Regex("\\[0/0]"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
            return title
        }

        /** Extracts 0..100 progress from a [N%] or [x/y] cookie, or null when absent/zero. */
        fun progress(noteView: NoteView): Int? {
            val title = noteView.note.title

            Regex("\\[(\\d+)%]").find(title)?.let {
                val value = it.groupValues[1].toIntOrNull() ?: return null
                return value.takeIf { v -> v > 0 }?.coerceAtMost(100)
            }

            Regex("\\[(\\d+)/(\\d+)]").find(title)?.let {
                val done = it.groupValues[1].toIntOrNull() ?: return null
                val total = it.groupValues[2].toIntOrNull() ?: return null
                if (total <= 0 || done <= 0) return null
                return (done * 100 / total).coerceIn(1, 100)
            }

            return null
        }
    }

    override fun getItemViewType(position: Int): Int =
        if (getItem(position) is Row.Header) TYPE_HEADER else TYPE_TASK

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(inflater.inflate(R.layout.item_today_header, parent, false))
        } else {
            TaskHolder(inflater.inflate(R.layout.item_today_task, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Header -> (holder as HeaderHolder).bind(row)
            is Row.Task -> (holder as TaskHolder).bind(row)
        }
    }

    private class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.item_today_header_title)

        fun bind(row: Row.Header) {
            title.text = "${row.title}  ·  ${row.count}"
            title.setTextColor(row.group.color)
        }
    }

    private inner class TaskHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val card: MaterialCardView = view as MaterialCardView
        private val title: TextView = view.findViewById(R.id.item_today_task_title)
        private val meta: TextView = view.findViewById(R.id.item_today_task_book)
        private val progress: ProgressBar = view.findViewById(R.id.item_today_task_progress)
        private val done: View = view.findViewById(R.id.item_today_task_done)

        fun bind(row: Row.Task) {
            val noteView = row.noteView

            val density = itemView.resources.displayMetrics.density
            val vertical = ((if (compact) 1 else 4) * density).toInt()
            (itemView.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.topMargin = vertical
                lp.bottomMargin = vertical
                itemView.layoutParams = lp
            }

            card.strokeWidth = 0
            val surface = com.google.android.material.color.MaterialColors.getColor(
                itemView,
                com.google.android.material.R.attr.colorSurfaceContainer
            )
            card.setCardBackgroundColor(
                androidx.core.graphics.ColorUtils.blendARGB(surface, row.group.color, 0.14f)
            )
            title.text = cleanTitle(noteView)

            if (row.group == Group.OVERDUE) {
                title.setTextColor(row.group.color)
            } else {
                title.setTextColor(title.context.obtainStyledAttributes(
                    intArrayOf(android.R.attr.textColorPrimary)).let { ta ->
                    val color = ta.getColor(0, android.graphics.Color.BLACK)
                    ta.recycle()
                    color
                })
            }

            val baseTime = noteView.scheduledTimeString ?: noteView.deadlineTimeString
            val time = if (row.group == Group.REPEATING) {
                val hm = Regex("\\d{1,2}:\\d{2}").find(baseTime ?: "")?.value
                if (hm != null) "${title.context.getString(R.string.today)} $hm"
                else title.context.getString(R.string.today)
            } else {
                baseTime
            }
            meta.text = if (time.isNullOrBlank()) noteView.bookName else "$time  ·  ${noteView.bookName}"

            val percent = progress(noteView)
            if (percent != null) {
                progress.visibility = View.VISIBLE
                progress.progress = percent
                progress.progressTintList = android.content.res.ColorStateList.valueOf(row.group.color)
            } else {
                progress.visibility = View.GONE
            }

            itemView.setOnClickListener { onClick(noteView) }
            done.setOnClickListener { onDone(noteView) }
        }
    }
}
