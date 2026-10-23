package com.scviewer.fire.web

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * WebChromeClient that lets the streamingcommunity web player go fullscreen
 * on Fire TV. When the site calls element.webkitRequestFullscreen() on the
 * <video> container, Android WebView hands us the View via onShowCustomView
 * — we attach it to the activity's root so it covers the whole screen.
 *
 * Uses the modern [WindowInsetsControllerCompat] API (the legacy
 * `systemUiVisibility` flags are deprecated on API 30+ and ignored on API 33+).
 *
 * Fire TV has no auto-rotation and no notch, so this is straightforward.
 */
class SCWebChromeClient(
    private val activity: Activity,
    private val root: FrameLayout,
    private val callback: FullscreenCallback
) : WebChromeClient() {

    interface FullscreenCallback {
        fun onFullscreenEntered()
        fun onFullscreenExited()
    }

    private var customView: View? = null
    private var originalOrientation: Int = 0
    private var wasFullscreenBefore: Boolean = false

    /** Site JS errors/warnings surfaced to logcat — Fire TV suppresses
     *  Log.d, so INFO level is the minimum that shows up on-device. */
    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
        val msg = consoleMessage ?: return super.onConsoleMessage(consoleMessage)
        Log.i(TAG, "console[${msg.messageLevel()}] ${msg.message()} @ ${msg.sourceId()}:${msg.lineNumber()}")
        return super.onConsoleMessage(consoleMessage)
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (customView != null) {
            callback?.onCustomViewHidden()
            return
        }
        customView = view
        originalOrientation = activity.requestedOrientation

        root.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Modern fullscreen + immersive-sticky via WindowInsetsControllerCompat.
        // Falls back gracefully on older Fire TV sticks (API 24+).
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val controller = WindowInsetsControllerCompat(activity.window, root)
        wasFullscreenBefore = (controller.systemBarsBehavior ==
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // FLAG_FULLSCREEN is also deprecated but still required on pre-API 30
        // to fully hide the status bar; on API 30+ the WindowInsetsController
        // does the job and the legacy flag is a no-op.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            activity.window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
        }

        this.callback.onFullscreenEntered()
        currentCallback = callback
    }

    override fun onHideCustomView() {
        val v = customView ?: return
        root.removeView(v)
        customView = null

        WindowCompat.setDecorFitsSystemWindows(activity.window, true)
        val controller = WindowInsetsControllerCompat(activity.window, root)
        controller.show(WindowInsetsCompat.Type.systemBars())

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }

        currentCallback?.onCustomViewHidden()
        currentCallback = null
        callback.onFullscreenExited()
    }

    companion object {
        private const val TAG = "SCWebChrome"
        private var currentCallback: CustomViewCallback? = null
    }
}
