package com.scviewer.fire.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import com.scviewer.fire.R
import com.scviewer.fire.data.SettingsStore
import kotlin.math.max
import kotlin.math.min

/**
 * Virtual mouse cursor driven by the Fire TV D-pad.
 *
 * Replaces the old JS list-based navigation, which was unreliable on the
 * site's SPA pages. This draws a floating pointer over the WebView:
 *
 *  - Arrow keys move the pointer; the longer a direction is held the faster
 *    it goes (smooth acceleration ramp = "velocità variabile"). The base
 *    speed comes from [SettingsStore] and is adjustable in the in-app
 *    "Impostazioni" dialog.
 *  - OK dispatches a real touch (ACTION_DOWN + ACTION_UP) to the WebView at
 *    the pointer position, so everything on the site works: cards, buttons,
 *    video controls, cookie banners, text fields.
 *  - Pushing against the top/bottom edge of the screen scrolls the page.
 *
 * The pointer is attached to the activity's content root with elevation, so
 * it always stays above the WebView and its re-added children (fullscreen
 * video, etc.).
 */
class CursorController(
    private val activity: Activity,
    private val webView: WebView,
    private val container: FrameLayout
) {

    private val handler = Handler(Looper.getMainLooper())
    private var cursor: ImageView? = null

    private var x = 0f
    private var y = 0f
    private var active = false
    private var running = false
    private val heldDirs = ArrayDeque<Int>()
    private var lastHeartbeat = 0L
    private var lastTick = 0L
    private var heldMs = 0L
    private var scrollRemainder = 0f
    private var clickPending = false
    /** Last mousedown counter seen by [verifyAndFallback] — baseline for
     *  deciding whether the next tap actually reached the page. */
    private var lastTouchSeen = 0L
    private var cursorW = 0f
    private var cursorH = 0f
    private var density = 1f

    /** Creates and adds the pointer overlay to [container]. */
    fun attach() {
        if (cursor != null) return
        density = activity.resources.displayMetrics.density
        cursorW = CURSOR_W_DP * density
        cursorH = CURSOR_H_DP * density

        val iv = ImageView(activity)
        iv.setImageResource(R.drawable.cursor_pointer)
        iv.layoutParams = FrameLayout.LayoutParams(
            cursorW.toInt(), cursorH.toInt(), Gravity.TOP or Gravity.START
        )
        iv.alpha = 0.95f
        // Elevation keeps the pointer above every sibling (WebView, overlays,
        // fullscreen video re-added at runtime) without add-order juggling.
        iv.elevation = 8f * density
        iv.visibility = View.GONE
        container.addView(iv)
        cursor = iv

        // Start centred once the container has been measured.
        iv.post {
            x = container.width / 2f - cursorW / 2f
            y = container.height / 2f - cursorH / 2f
            applyPosition()
        }
    }

    fun show() {
        active = true
        cursor?.visibility = View.VISIBLE
    }

    fun hide() {
        active = false
        stopMovement()
        cursor?.visibility = View.GONE
    }

    fun isActive(): Boolean = active

    /** Feed a D-pad direction press. repeatCount > 0 means key auto-repeat. */
    fun onDirectionDown(keyCode: Int, repeatCount: Int) {
        if (!active) return
        lastHeartbeat = SystemClock.uptimeMillis()
        if (repeatCount == 0) {
            // A fresh press always replaces the tracked direction (a D-pad
            // can only hold one arrow at a time; clearing also heals a lost
            // key-up of the previous direction).
            heldDirs.clear()
            heldDirs.addLast(keyCode)
        }
        // (Re)start the loop — idempotent while it runs, and it revives
        // movement if a repeat ever arrives after the watchdog stopped it.
        // CRITICAL: the first system auto-repeat only arrives after ~500 ms
        // of holding, so the loop MUST keep running on its own until then.
        startLoop()
    }

    /** Feed a D-pad direction release. */
    fun onDirectionUp(keyCode: Int) {
        heldDirs.remove(keyCode)
        if (heldDirs.isEmpty()) stopLoop()
    }

    /** Dispatches a tap at the pointer position.
     *
     * Two-stage strategy for maximum reliability:
     *  1. PRIMARY: a real synthetic touch (ACTION_DOWN + ACTION_UP,
     *     touchscreen source, finger tool type) dispatched to the WebView
     *     SYNCHRONOUSLY — no JS round-trip before the DOWN, because slow
     *     renderers can stall evaluateJavascript callbacks and the tap
     *     would feel dead to the user.
     *  2. VERIFY: ~380 ms later the page is asked for its mousedown
     *     counter. If the counter did not advance, the WebView dropped the
     *     synthetic touch and a full DOM-level mouse sequence + click() is
     *     fired on the element under the pointer. The check is fully
     *     self-contained (it re-installs the counter itself if
     *     page_inject.js is missing), so the fallback can never silently
     *     no-op. */
    fun click() {
        val c = cursor ?: return
        if (!active || clickPending) return
        if (webView.parent == null) return   // detached during fullscreen video

        clickPending = true

        val tip = IntArray(2)
        c.getLocationInWindow(tip)
        val wloc = IntArray(2)
        webView.getLocationInWindow(wloc)
        val lx = (tip[0] - wloc[0]).coerceIn(0, max(webView.width - 1, 0)).toFloat()
        val ly = (tip[1] - wloc[1]).coerceIn(0, max(webView.height - 1, 0)).toFloat()
        Log.i(TAG, "click at ($lx, $ly) view=${webView.width}x${webView.height}")

        // 1) Primary path: dispatch the touch stream right now.
        val now = SystemClock.uptimeMillis()
        val down = makeTouch(MotionEvent.ACTION_DOWN, now, now, lx, ly)
        val downOk = webView.dispatchTouchEvent(down)
        down.recycle()
        Log.i(TAG, "touch DOWN consumed=$downOk")

        // Quick pulse so the user sees the click landed.
        c.animate().scaleX(0.72f).scaleY(0.72f).setDuration(50).withEndAction {
            c.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
        }.start()

        handler.postDelayed({
            val t = SystemClock.uptimeMillis()
            val up = makeTouch(MotionEvent.ACTION_UP, now, t, lx, ly)
            val upOk = webView.dispatchTouchEvent(up)
            up.recycle()
            Log.i(TAG, "touch UP consumed=$upOk")

            handler.postDelayed({
                clickPending = false
                if (webView.parent == null) return@postDelayed
                verifyAndFallback(lx, ly, downOk, upOk)
            }, FALLBACK_MS)
        }, TAP_MS)
    }

    /** Post-tap verification + DOM fallback (see [click]). */
    private fun verifyAndFallback(lx: Float, ly: Float, downOk: Boolean, upOk: Boolean) {
        webView.evaluateJavascript(SENTRY_SCRIPT) { res ->
            val r = res?.trim('"', ' ') ?: ""
            Log.i(TAG, "sentry -> '$r' (baseline=$lastTouchSeen downOk=$downOk upOk=$upOk)")
            val touchDropped = !downOk || !upOk
            when {
                r == SENTRY_INSTALLED -> {
                    // The counter was missing (page_inject.js not injected):
                    // it is now installed, but this tap's mousedown was not
                    // counted. Skip the fallback once so a WORKING touch is
                    // never double-fired; from the next tap the counter
                    // verifies normally.
                    lastTouchSeen = 0L
                }
                r.startsWith(SENTRY_VALUE_PREFIX) -> {
                    val seen = r.removePrefix(SENTRY_VALUE_PREFIX).toLongOrNull() ?: -1L
                    if (seen >= 0) {
                        if (seen <= lastTouchSeen || touchDropped) {
                            Log.i(TAG, "tap did not reach the page -> DOM fallback")
                            webView.evaluateJavascript(tapScript(lx, ly), null)
                        }
                        lastTouchSeen = seen
                    }
                }
                else -> Log.i(TAG, "sentry unreadable -> skipping fallback")
            }
        }
    }

    /** Self-contained DOM click: converts view px to CSS px, finds the
     *  element under the pointer and replays mousedown/mouseup/click on it.
     *  Does NOT depend on page_inject.js being present. */
    private fun tapScript(lx: Float, ly: Float): String {
        val vw = webView.width
        val vh = webView.height
        return "(function(x,y,vw,vh){try{var d=document,de=d.documentElement;" +
            "var cw=(de&&de.clientWidth)||vw||1,ch=(de&&de.clientHeight)||vh||1;" +
            "var cx=x*cw/vw,cy=y*ch/vh;" +
            "var el=d.elementFromPoint(cx,cy);if(!el)return 'miss';" +
            "var o={bubbles:true,cancelable:true,view:window,clientX:cx,clientY:cy,button:0};" +
            "['mousedown','mouseup'].forEach(function(t){try{" +
            "el.dispatchEvent(new MouseEvent(t,o));}catch(e){}});" +
            "el.click();try{if(el.focus)el.focus();}catch(e){}" +
            "return 'hit:'+(el.tagName||'?');" +
            "}catch(e){return 'err';}})($lx,$ly,$vw,$vh)"
    }

    /** Builds a well-formed touchscreen event (finger tool type) — some
     *  WebView versions ignore synthetic events without it. */
    private fun makeTouch(
        action: Int, downTime: Long, eventTime: Long, lx: Float, ly: Float
    ): MotionEvent {
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            x = lx
            y = ly
            pressure = 1f
            size = 1f
        }
        return MotionEvent.obtain(
            downTime, eventTime, action, 1,
            arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0
        )
    }

    /** Stops any in-flight movement (activity pause, menu open, fullscreen). */
    fun stopMovement() {
        stopLoop()
        heldDirs.clear()
    }

    fun destroy() {
        stopMovement()
        active = false
        val c = cursor
        cursor = null
        if (c != null) (c.parent as? ViewGroup)?.removeView(c)
    }

    // -- Movement loop -------------------------------------------------------

    private val tickRunnable = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            // Safety: if key repeats stopped arriving (focus lost, a dialog
            // opened over the activity, lost key-up...), halt instead of
            // drifting forever.
            if (!active || heldDirs.isEmpty() ||
                now - lastHeartbeat > HEARTBEAT_TIMEOUT_MS) {
                stopLoop()
                return
            }
            val deltaMs = now - lastTick
            lastTick = now
            val dt = min(deltaMs / 1000f, 0.1f)
            heldMs += deltaMs

            // Variable speed: base value from settings + acceleration ramp.
            val speed = SettingsStore.getCursorSpeed(activity)
            val base = speed * DP_PER_SEC_PER_STEP * density
            val accel = 1f + ACCEL_FACTOR * min(heldMs / RAMP_MS, 1f)
            val v = base * accel

            val dir = heldDirs.last()
            when (dir) {
                KeyEvent.KEYCODE_DPAD_LEFT -> x -= v * dt
                KeyEvent.KEYCODE_DPAD_RIGHT -> x += v * dt
                KeyEvent.KEYCODE_DPAD_UP -> y -= v * dt
                KeyEvent.KEYCODE_DPAD_DOWN -> y += v * dt
            }
            val maxX = max(container.width - cursorW, 0f)
            val maxY = max(container.height - cursorH, 0f)
            x = x.coerceIn(0f, maxX)
            y = y.coerceIn(0f, maxY)
            applyPosition()

            // Edge auto-scroll: push against the top/bottom edge to scroll.
            if (dir == KeyEvent.KEYCODE_DPAD_UP && y <= 0f &&
                webView.canScrollVertically(-1)) {
                scrollPage(-EDGE_SCROLL_DP_PER_SEC * density * dt)
            } else if (dir == KeyEvent.KEYCODE_DPAD_DOWN && y >= maxY && maxY > 0f &&
                webView.canScrollVertically(1)) {
                scrollPage(EDGE_SCROLL_DP_PER_SEC * density * dt)
            }

            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun startLoop() {
        if (running) return
        running = true
        heldMs = 0L
        lastTick = SystemClock.uptimeMillis()
        lastHeartbeat = lastTick
        handler.post(tickRunnable)
    }

    private fun stopLoop() {
        if (!running) return
        running = false
        handler.removeCallbacks(tickRunnable)
        heldMs = 0L
    }

    private fun applyPosition() {
        val c = cursor ?: return
        c.x = x
        c.y = y
    }

    private fun scrollPage(px: Float) {
        scrollRemainder += px
        val step = scrollRemainder.toInt()
        if (step != 0) {
            scrollRemainder -= step
            webView.scrollBy(0, step)
        }
    }

    companion object {
        private const val CURSOR_W_DP = 26f
        private const val CURSOR_H_DP = 28f
        /** Base speed in dp/s contributed by each settings point (1..10). */
        private const val DP_PER_SEC_PER_STEP = 42f
        /** Extra multiplier once fully ramped (total = 1 + ACCEL_FACTOR). */
        private const val ACCEL_FACTOR = 1.25f
        private const val RAMP_MS = 1400f
        private const val TICK_MS = 16L
        /** Generous watchdog: must exceed the system key-repeat initial delay
         *  (~500 ms) by a wide margin. In normal conditions only a lost
         *  key-up or an explicit stopMovement() halts the movement. */
        private const val HEARTBEAT_TIMEOUT_MS = 5000L
        private const val EDGE_SCROLL_DP_PER_SEC = 520f
        private const val TAG = "SCCursor"
        private const val TAP_MS = 80L
        /** Delay before verifying whether the tap reached the page. */
        private const val FALLBACK_MS = 300L
        /** Sentry replies: "I" = counter was missing and got installed,
         *  "V<n>" = mousedown counter value (see page_inject.js). */
        private const val SENTRY_INSTALLED = "I"
        private const val SENTRY_VALUE_PREFIX = "V"
        /** Asks the page for its mousedown counter, installing the counter
         *  itself if the injection script is missing (self-healing). */
        private const val SENTRY_SCRIPT =
            "(function(){try{if(typeof window.__scvTouchSeen==='undefined'){" +
            "window.__scvTouchSeen=0;" +
            "document.addEventListener('mousedown',function(){" +
            "window.__scvTouchSeen=(window.__scvTouchSeen|0)+1;},true);" +
            "return 'I';}" +
            "return 'V'+(window.__scvTouchSeen-0);}catch(e){return 'E'+e;}})()"
    }
}
