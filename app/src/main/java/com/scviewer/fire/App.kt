package com.scviewer.fire

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import com.scviewer.fire.BuildConfig
import com.scviewer.fire.work.DomainRefreshScheduler

/**
 * Application class. Implements [Configuration.Provider] so WorkManager
 * auto-initializes lazily on first access with our config — do NOT call
 * `WorkManager.initialize()` here, that would conflict with the default
 * androidx.startup initializer shipped with `work-runtime-ktx` and crash
 * the app on launch with "WorkManager is already initialized".
 *
 * The 30-min periodic domain refresh is scheduled from MainActivity.onCreate
 * instead, so it only fires after the activity (and therefore WorkManager)
 * is up.
 */
class App : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Install a global uncaught-exception handler so any crash leaves a
        // readable breadcrumb in logcat (tag SCViewer-Crash). The handler
        // re-throws to the default JVM behaviour after logging.
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("SCViewer-Crash", "Uncaught on ${thread.name}", throwable)
            // Persist the stack for phone-side diagnosis (no ADB needed:
            // the touch menu offers "Segnala errore" when a crash exists).
            runCatching {
                filesDir.resolve("last_crash.txt").writeText(
                    buildString {
                        appendLine("time: " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date()))
                        appendLine("version: " + BuildConfig.VERSION_NAME)
                        appendLine("thread: " + thread.name)
                        appendLine(android.util.Log.getStackTraceString(throwable))
                    }
                )
            }
            // Defer to the previous handler (the default Android one will
            // surface the standard "app keeps stopping" dialog and kill the
            // process).
            previousHandler?.uncaughtException(thread, throwable)
        }

        // Don't call WorkManager.initialize() here — Configuration.Provider
        // takes care of it lazily on first getInstance() call.
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    companion object {
        @Volatile lateinit var instance: App
            private set

        /** Captured at class init so the crash handler can re-throw to the
         *  previous default handler after logging. */
        private val previousHandler: Thread.UncaughtExceptionHandler? =
            Thread.getDefaultUncaughtExceptionHandler()
    }
}
