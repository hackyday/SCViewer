package com.scviewer.fire.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.scviewer.fire.data.DomainRepository
import java.util.concurrent.TimeUnit

/**
 * WorkManager periodic worker that re-pulls domains_s.json from AstraeLabs/Domains
 * every ~30 minutes. Minimum WorkManager interval is 15 minutes; we use 30 to match
 * the VibraVid default and to keep the wakeups cheap on the Fire TV Stick.
 *
 * VibraVid/utils/_startup_prefetch.py runs the same fetch on app launch. The
 * one-shot startup fetch is invoked directly from MainActivity (no need to go
 * through WorkManager for that).
 */
class DomainRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            DomainRepository.get(applicationContext).refreshOnline()
            Result.success()
        } catch (t: Throwable) {
            // Soft failure — the cached copy is still valid for the next load.
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "scviewer.domain.refresh"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<DomainRefreshWorker>(
                30, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
