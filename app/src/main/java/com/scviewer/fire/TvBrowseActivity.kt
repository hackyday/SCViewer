package com.scviewer.fire

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.util.Log
import android.webkit.WebViewClient
import com.scviewer.fire.data.DomainRepository
import com.scviewer.fire.data.ProgressStore
import com.scviewer.fire.data.SiteCatalog
import com.scviewer.fire.data.TvTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Interfaccia TV nativa "SCtv" (prototipo): l'interfaccia è il design HTML
 * in assets/tv/index.html renderizzato in un WebView a tutto schermo; i
 * DATI sono reali, scaricati dal JSON Inertia del sito (SiteCatalog) e
 * iniettati via bootRealData(). Il D-pad è pontato su handleRemoteAction()
 * del design; OK/Guarda apre il flusso esistente MainActivity (Riprendi,
 * skip intro, ecc.).
 */
class TvBrowseActivity : Activity() {

    private lateinit var web: WebView
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** true quando l'utente sta scrivendo nel campo di ricerca: il D-pad
     *  allora serve il testo (via IME), non la navigazione spaziale. */
    @Volatile private var typing = false

    private inner class TvBridge {
        /** Guarda: apre la schermata di streaming SCtv (PlayerActivity) sul
         *  watch URL reale del titolo. */
        @JavascriptInterface
        fun playTitle(url: String) {
            if (url.isBlank()) return
            val domain = DomainRepository.get(this@TvBrowseActivity).currentDomain()
            val full = if (url.startsWith("http")) url else domain.trimEnd('/') + url
            runOnUiThread {
                startActivity(
                    Intent(this@TvBrowseActivity, PlayerActivity::class.java).apply {
                        putExtra("url", full)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        }

        @JavascriptInterface
        fun setTyping(value: Boolean) {
            typing = value
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            // Lo stick ha viewport 960 CSS px (densità 2.0): il design è
            // pensato per ~1280 — forza il layout largo e rimpicciolisci.
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = 100
        }
        web.addJavascriptInterface(TvBridge(), "AndroidJs")
        web.webChromeClient = WebChromeClient()
        web.webViewClient = WebViewClient()
        setContentView(web)
        web.loadUrl("file:///android_asset/tv/index.html")

        // Dati reali: fetch in parallelo e iniezione nel design.
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { buildBootPayload() } }
            result.onSuccess { payload ->
                Log.i("TvBrowse", "boot payload: ${payload.length()} char")
                web.evaluateJavascript("bootRealData($payload)", null)
            }.onFailure { Log.e("TvBrowse", "payload build failed", it) }
        }
    }

    /** Costruisce il payload bootRealData: catalogo (dedup per id) + righe
     *  reali per home/film/serie + Continua a guardare dal ProgressStore. */
    private fun buildBootPayload(): JSONObject {
        val domain = DomainRepository.get(this).currentDomain()
        val catalog = LinkedHashMap<String, JSONObject>()
        val rows = JSONObject()

        fun addSliders(path: String, key: String) {
            val props = SiteCatalog.fetchProps(path, domain) ?: return
            val sliders = props.optJSONArray("sliders") ?: return
            val arr = JSONArray()
            for (i in 0 until sliders.length()) {
                val s = sliders.optJSONObject(i) ?: continue
                val titles = s.optJSONArray("titles") ?: continue
                val ids = JSONArray()
                for (k in 0 until titles.length()) {
                    val t = SiteCatalog.parseTitle(
                        titles.optJSONObject(k) ?: continue, props, domain
                    ) ?: continue
                    val keyId = "site-" + t.id
                    if (!catalog.containsKey(keyId)) catalog[keyId] = titleJson(t)
                    ids.put(keyId)
                }
                arr.put(
                    JSONObject().put("label", s.optString("label"))
                        .put("name", s.optString("name"))
                        .put("ids", ids)
                )
            }
            if (arr.length() > 0) rows.put(key, arr)
        }

        addSliders("/", "home")
        addSliders("/it/movies", "movies")
        addSliders("/it/tv-shows", "series")

        // Continua a guardare reale (posizione, ep, label) dal ProgressStore.
        val wp = JSONObject()
        for (p in ProgressStore.get(this).resumeable()) {
            val keyId = "site-" + p.titleId
            val tagStr = p.seasonEpisodeTag()
            if (!catalog.containsKey(keyId)) {
                val tag = p.seasonEpisodeTag()
                catalog[keyId] = JSONObject()
                    .put("id", keyId)
                    .put("title", p.name)
                    .put("type", p.type)
                    .put("posterUrl", p.poster ?: "")
                    .put("backdropUrl", p.poster ?: "")
                    .put("url", p.url)
                    .put("match", 0)
                    .put("year", 0)
                    .put("maturity", "NR")
                    .put("durationLabel", "")
                    .put("genres", JSONArray())
                    .put("cast", JSONArray())
                    .put("badges", JSONArray())
                    .put("synopsis", tagStr)
                    .put("tagline", tagStr)
                    .put("isOriginal", false)
                    .put("isKids", false)
                    .put("seasons", 0)
                    .put("episodes", JSONArray())
                    .put("comingSoon", false)
            }
            val ratio = if (p.durationSec > 0)
                (p.positionSec.toDouble() / p.durationSec).coerceIn(0.0, 1.0) else 0.0
            wp.put(keyId, JSONObject()
                .put("progress", ratio)
                .put("label", (tagStr + " · ").takeIf { tagStr.isNotEmpty() }.orEmpty() + p.positionLabel() + " rimasti")
            )
        }

        val hero = rows.optJSONArray("home")?.optJSONObject(0)?.optJSONArray("ids")?.optString(0) ?: ""
        return JSONObject()
            .put("catalog", JSONArray(catalog.values))
            .put("rows", rows)
            .put("watchProgress", wp)
            .put("heroTitleId", hero)
    }

    private fun titleJson(t: TvTitle): JSONObject = JSONObject()
        .put("id", "site-" + t.id)
        .put("title", t.name)
        .put("type", t.type)
        .put("posterUrl", t.posterUrl ?: "")
        .put("backdropUrl", t.backgroundUrl ?: t.posterUrl ?: "")
        // Play → /it/watch/<id>: funziona per film e serie (il sito serve
        // da solo il primo episodio; il prototipo risolve ?s= nel player).
        .put("url", "/it/watch/" + t.id)
        .put("match", t.score?.toDoubleOrNull()?.times(10)?.toInt()?.coerceIn(0, 100) ?: 80)
        .put("year", t.year ?: 0)
        .put("maturity", "NR")
        .put("durationLabel", t.seasonsCount?.let { "$it stagioni" } ?: "")
        .put("genres", JSONArray())
        .put("cast", JSONArray())
        .put("badges", JSONArray())
        .put("synopsis", t.plot ?: "")
        .put("tagline", "")
        .put("isOriginal", false)
        .put("isKids", false)
        .put("seasons", t.seasonsCount ?: 0)
        .put("episodes", JSONArray())
        .put("comingSoon", false)

    // -- D-pad → handleRemoteAction del design --------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && !typing) {
            val action = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> "up"
                KeyEvent.KEYCODE_DPAD_DOWN -> "down"
                KeyEvent.KEYCODE_DPAD_LEFT -> "left"
                KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> "ok"
                else -> null
            }
            if (action != null) {
                web.evaluateJavascript("handleRemoteAction('$action')", null)
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                web.evaluateJavascript(
                    "window.__sctvBackState ? __sctvBackState() : 'exit'"
                ) { result ->
                    if (result?.contains("exit") == true) finish()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        web.destroy()
    }
}
