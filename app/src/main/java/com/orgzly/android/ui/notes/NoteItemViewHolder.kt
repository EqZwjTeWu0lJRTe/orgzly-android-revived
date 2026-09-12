package com.orgzly.android.ui.notes

import android.util.Log
import androidx.recyclerview.widget.RecyclerView
import android.view.View
import com.orgzly.databinding.ItemHeadBinding

class NoteItemViewHolder(val binding: ItemHeadBinding, private val clickListener: ClickListener) :
        RecyclerView.ViewHolder(binding.root),
        View.OnClickListener,
        View.OnLongClickListener {

    /** Active 1-second ticker for the stopwatch text (only while a clock runs). */
    var clockTicker: Runnable? = null

    fun stopClockTicker() {
        clockTicker?.let { binding.itemHeadClockText.removeCallbacks(it) }
        clockTicker = null
    }

    init {
        binding.root.setOnClickListener(this)
        binding.root.setOnLongClickListener(this)
    }

    override fun onClick(view: View) {
        bindingAdapterPosition.let { position ->
            if (position != RecyclerView.NO_POSITION) {
                clickListener.onClick(view, position)
            } else {
                Log.e(TAG, "Adapter position for $view not available")
            }
        }
    }

    override fun onLongClick(view: View): Boolean {
        bindingAdapterPosition.let { position ->
            return if (position != RecyclerView.NO_POSITION) {
                clickListener.onLongClick(view, position)
                true
            } else {
                Log.e(TAG, "Adapter position for $view not available")
                false
            }
        }
    }

    interface ClickListener {
        fun onClick(view: View, position: Int)
        fun onLongClick(view: View, position: Int)
    }

    companion object {
        private val TAG = NoteItemViewHolder::class.java.name
    }
}