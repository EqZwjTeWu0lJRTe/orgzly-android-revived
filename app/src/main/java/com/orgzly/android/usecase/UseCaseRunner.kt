package com.orgzly.android.usecase

import com.orgzly.BuildConfig
import com.orgzly.android.App
import com.orgzly.android.data.DataRepository
import com.orgzly.android.prefs.AppPreferences
import com.orgzly.android.reminders.RemindersScheduler
import com.orgzly.android.sync.AutoSync
import com.orgzly.android.sync.SyncRunner
import com.orgzly.android.SharingShortcutsManager
import com.orgzly.android.util.LogUtils
import com.orgzly.android.widgets.ListWidgetProvider
import javax.inject.Inject


object UseCaseRunner {
    private val TAG = UseCaseRunner::class.java.name

    // FIXME: Make UserActionRunner a class
    class Factory {
        @Inject
        lateinit var autoSync: AutoSync

        @Inject
        lateinit var dataRepository: DataRepository

        init {
            App.appComponent.inject(this)
        }
    }

    @JvmStatic
    fun run(action: UseCase): UseCaseResult {
        val startedAt = System.currentTimeMillis()

        val factory = Factory()

        val result = action.run(factory.dataRepository)

        when (result.triggersSync) {
            UseCase.SYNC_DATA_MODIFIED -> triggerSync(factory, AutoSync.Type.DATA_MODIFIED)
            UseCase.SYNC_NOTE_CREATED -> triggerSync(factory, AutoSync.Type.NOTE_CREATED)
        }

        if (result.modifiesLocalData) {
            RemindersScheduler.notifyDataSetChanged(App.getAppContext())
            ListWidgetProvider.notifyDataSetChanged(App.getAppContext())
            SharingShortcutsManager().replaceDynamicShortcuts(App.getAppContext())
        }

        if (result.modifiesListWidget) {
            ListWidgetProvider.update(App.getAppContext())
        }

        if (BuildConfig.LOG_DEBUG) {
            val ms = System.currentTimeMillis() - startedAt
            LogUtils.d(TAG, "Finished $action in ${ms}ms")
        }

        return result
    }

    /**
     * When the independent "Sync after changes" switch is on, sync immediately with manual
     * (non-auto) semantics - regardless of the auto-sync master switch. Otherwise fall back
     * to the regular auto-sync trigger (which is gated by the auto-sync settings).
     */
    private fun triggerSync(factory: Factory, type: AutoSync.Type) {
        if (AppPreferences.syncOnNoteChange(App.getAppContext())) {
            SyncRunner.startSync(false)
        } else {
            factory.autoSync.trigger(type)
        }
    }
}