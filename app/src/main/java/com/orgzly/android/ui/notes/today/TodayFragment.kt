package com.orgzly.android.ui.notes.today

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.orgzly.android.App
import com.orgzly.android.AppIntent
import com.orgzly.R
import com.orgzly.android.data.DataRepository
import com.orgzly.android.prefs.AppPreferences
import com.orgzly.android.db.entity.NoteView
import com.orgzly.android.ui.DisplayManager
import com.orgzly.android.ui.drawer.DrawerItem
import com.orgzly.databinding.FragmentTodayBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Landing page: a "today" overview aggregated from all notebooks.
 */
class TodayFragment : Fragment(), DrawerItem {

    @Inject lateinit var dataRepository: DataRepository

    private lateinit var binding: FragmentTodayBinding

    private lateinit var adapter: TodayAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        App.appComponent.inject(this)
        binding = FragmentTodayBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(binding.root)

        binding.todayList.layoutManager = LinearLayoutManager(requireContext())

        adapter = TodayAdapter(
            onClick = { noteView -> openNote(noteView) },
            onDone = { noteView -> markDone(noteView) },
            compact = AppPreferences.todayDensity(requireContext()).contains("compact")
        )
        binding.todayList.adapter = adapter

        binding.todayDate.text = SimpleDateFormat("yyyy-MM-dd EEEE", Locale.getDefault()).format(Date())

        binding.todayViewAll.setOnClickListener {
            LocalBroadcastManager.getInstance(requireContext())
                .sendBroadcast(android.content.Intent(AppIntent.ACTION_OPEN_BOOKS))
        }

        binding.todaySettings.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), com.orgzly.android.ui.settings.SettingsActivity::class.java))
        }

        binding.todaySwipe.setOnRefreshListener { load() }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        App.EXECUTORS.diskIO().execute {
            val tasks = dataRepository.selectTodayTasks()

            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread

                val rows = ArrayList<TodayAdapter.Row>()

                fun addGroup(group: TodayAdapter.Group, title: String, notes: List<NoteView>) {
                    if (notes.isEmpty()) return
                    rows.add(TodayAdapter.Row.Header(title, notes.size, group))
                    notes.forEach { rows.add(TodayAdapter.Row.Task(it, group)) }
                }

                addGroup(TodayAdapter.Group.OVERDUE, getString(R.string.today_group_overdue), tasks.overdue)
                addGroup(TodayAdapter.Group.DUE, getString(R.string.today_group_due), tasks.dueToday)
                addGroup(TodayAdapter.Group.SCHEDULED, getString(R.string.today_group_scheduled), tasks.scheduledToday)
                addGroup(TodayAdapter.Group.REPEATING, getString(R.string.today_group_repeating), tasks.repeatingToday)

                adapter.submitList(rows)

                val base = getString(R.string.today_stats, tasks.doneCount, tasks.totalCount)
                binding.todayStats.text = base + "  ·  " + getString(
                    R.string.today_time_stats,
                    minutesText(tasks.estimatedMinutes),
                    minutesText(tasks.spentMinutes)
                )
                binding.todayEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                binding.todayList.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
                binding.todaySwipe.isRefreshing = false
            }
        }
    }

    private fun minutesText(minutes: Long): String {
        if (minutes <= 0) return "0h"

        val tenths = kotlin.math.round(minutes / 6.0).toLong()
        val hours = tenths / 10
        val fraction = tenths % 10

        return if (fraction == 0L) "${hours}h" else "$hours.${fraction}h"
    }

    private fun markDone(noteView: NoteView) {
        App.EXECUTORS.diskIO().execute {
            com.orgzly.android.usecase.UseCaseRunner.run(
                com.orgzly.android.usecase.NoteUpdateStateToggle(setOf(noteView.note.id))
            )

            activity?.runOnUiThread {
                if (isAdded) load()
            }
        }
    }

    private fun openNote(noteView: NoteView) {
        DisplayManager.displayExistingNote(
            parentFragmentManager,
            noteView.note.position.bookId,
            noteView.note.id
        )
    }

    override fun getCurrentDrawerItemId(): String = drawerItemId

    companion object {
        @JvmField
        val FRAGMENT_TAG: String = TodayFragment::class.java.name

        @JvmField
        val drawerItemId = "today"

        @JvmStatic
        fun getInstance(): TodayFragment = TodayFragment()
    }
}
