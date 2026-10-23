package com.scviewer.fire.work

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager

/**
 * Thin wrapper so the App.onCreate doesn't have to know the work name / interval.
 *
 * WorkManager.getInstance() will trigger the App's Configuration.Provider
 * (lazy initialization) on first call. If anything goes wrong there (rare on
 * Fire TV but possible on sticks with very tight memory), we catch and log
 * so the rest of the app keeps working — domain refresh will fall back to
 * the on-launch + on-resume one-shot fetches in MainActivity.
 */
object DomainRefreshScheduler {

    private const val WORK_NAME = "scviewer.domain.refresh"
    private const val TAG = "DomainRefreshScheduler"

    fun schedule(context: Context) {
        try {
            DomainRefreshWorker.schedule(context)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to schedule domain refresh worker", t)
        }
    }

    fun cancel(context: Context) {
        try {
            WorkManager.getInstance(context)
                .cancelUniqueWork(WORK_NAME)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to cancel domain refresh worker", t)
        }
    }
}
