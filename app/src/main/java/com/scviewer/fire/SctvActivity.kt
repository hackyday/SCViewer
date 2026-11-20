package com.scviewer.fire

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.scviewer.fire.bridge.SctvBridge
import com.scviewer.fire.data.DomainRepository
import com.scviewer.fire.data.SettingsStore
import com.scviewer.fire.network.HlsRewriter
import com.scviewer.fire.ui.SettingsDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Nuova interfaccia SCtv (M1): WebView fullscreen che carica l'asset locale
 * `sctv/index.html` (design TV + bridge) e lo alimenta con dati DEMO.
 *
 * Ciclo di vita (piano §1.2):
 * - push nativo→JS SEMPRE da UI thread via `window.__sctvReceive(ev)`;
 * - D-pad → `handleRemoteAction(...)`; BACK → `window.__sctvBackState()`
 *   con callback 'exit' (chiudi app) / 'stay' (gestito dal JS);
 * - onResume: web.onResume + refresh dominio + push progressChanged;
 * - onPause: web.onPause (congela il rendering, il fuoco resta pulito);
 * - onDestroy: scope.cancel + destroy della WebView;
 * - onTrimMemory: clearCache sotto pressione di memoria.
 *
 * Player NATIVO delegato: startPlayback (JS) → AndroidJs.play → stub toast
 * in M1, PlayerActivity in M3. NIENTE player HTML5 nella WebView.
 */
class SctvActivity : Activity() {

    companion object {
        private const val TAG = "SctvActivity"
        private const val BRIDGE_VERSION = 1

        /** Video di prova per gli streamUrl degli episodi demo. */
        private const val SAMPLE_BASE =
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/"
    }

    private lateinit var web: WebView
    private lateinit var loadingOverlay: View
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val domainRepo by lazy { DomainRepository.get(this) }

    /** true dopo il push del primo evento boot: evita push progressChanged
     *  verso una pagina che non ha ancora il dispatcher attivo. */
    @Volatile private var booted = false

    // -- Creazione ---------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sctv)
        web = findViewById(R.id.sctvWeb)
        loadingOverlay = findViewById(R.id.sctvLoading)
        configureWebView()
        // Cache-busting via versionCode: aggiornare l'asset invalida la URL.
        web.loadUrl("file:///android_asset/sctv/index.html?v=" + BuildConfig.VERSION_CODE)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true            // localStorage profili (fix TV #11)
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true              // asset file:// + <img> https ok
            // Lo stick ha viewport ~960 CSS px: il design è pensato per 1280.
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = 100
        }
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)

        val bridge = SctvBridge(
            onBootReady = { scope.launch { pushBootPayload() } },
            onLoadTab = { tab ->
                Log.i(TAG, "loadTab($tab): M2 — dati reali dal repository")
            },
            onLoadDetail = { titleId, req ->
                Log.i(TAG, "loadDetail($titleId, req=$req): M2 — episodi reali")
            },
            onSearch = { query, req ->
                Log.i(TAG, "search('$query', req=$req): M2 — ricerca server")
            },
            onToggleMyList = { titleId ->
                Log.i(TAG, "toggleMyList($titleId): M2 — FavoritesStore")
            },
            onGetContinueWatching = {
                Log.i(TAG, "getContinueWatching(): M2 — ProgressStore")
            },
            onReportProgress = { titleId, season, episode, pos, dur ->
                Log.i(TAG, "reportProgress($titleId, S$season:E$episode, $pos/$dur): M3")
            },
            onPlay = { titleId, season, episode ->
                scope.launch { onPlayRequested(titleId, season, episode) }
            },
            onOpenSettings = { scope.launch { openNativeSettings() } },
            onOpenSiteInterface = { scope.launch { switchToSite() } },
            onRetryConnection = { scope.launch { pushBootPayload() } }
        )
        web.addJavascriptInterface(bridge, "AndroidJs")
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // L'HTML mostra il suo splash+gate: via l'overlay nativo.
                loadingOverlay.visibility = View.GONE
            }
        }
    }

    // -- Push nativo→JS ------------------------------------------------------------

    /** Unico entry nativo→JS: envelope `{"t","req","d"}` valutato sulla pagina.
     *  SEMPRE dal thread UI (evaluateJavascript non è thread-safe). */
    private fun push(type: String, d: JSONObject, req: Int? = null) {
        val ev = JSONObject()
            .put("t", type)
            .put("req", req ?: JSONObject.NULL)
            .put("d", d)
        runOnUiThread {
            if (BuildConfig.DEBUG) Log.d(TAG, "push $type")
            web.evaluateJavascript("window.__sctvReceive(" + ev.toString() + ")", null)
        }
    }

    private fun pushBootPayload() {
        scope.launch {
            val payload = withContext(Dispatchers.IO) {
                runCatching { buildBootPayload() }
                    .onFailure { Log.e(TAG, "boot payload failed", it) }
                    .getOrNull()
            }
            if (payload != null) {
                booted = true
                push("boot", payload)
            } else {
                push(
                    "error",
                    JSONObject().put("message", "Avvio catalogo non riuscito")
                )
            }
        }
    }

    // -- Payload DEMO (M2: sostituito da SctvRepository) ---------------------------

    /** Costruisce l'evento boot con dati demo completi: 13 titoli (2 serie
     *  multi-stagione con episodi + streamUrl di prova, film, coming soon,
     *  top10, originals, kids), rail fake per home/tv/movie, continua a
     *  guardare finto. In modalità kids il JS filtra da solo per isKids. */
    private fun buildBootPayload(): JSONObject {
        val host = runCatching { Uri.parse(domainRepo.currentDomain()).host ?: "" }
            .getOrDefault("")
        return JSONObject()
            .put("bridgeVersion", BRIDGE_VERSION)
            .put("uiMode", SettingsStore.getUiMode(this))
            .put("domainHost", host)
            .put("myList", JSONArray(listOf("site-4101", "site-4106")))
            .put("continueWatching", buildContinueWatching())
            .put("catalog", buildDemoCatalog())
            .put("rows", buildDemoRows())
            .put("heroTitleId", "site-4101")
            .put("settings", JSONObject().put("uiAudio", true))
    }

    private fun buildContinueWatching(): JSONArray {
        val cw = JSONArray()
        // Episodio S1:E3 di Ombra di Sangue al 42%
        cw.put(JSONObject()
            .put("id", "site-4101")
            .put("titleId", 4101)
            .put("name", "Ombra di Sangue")
            .put("tag", "S1:E3")
            .put("progress", 0.42)
            .put("label", "S1:E3 · 28 min rimasti")
            .put("poster", demoArt("site-4101", "Ombra di Sangue", 400, 600))
            .put("season", 1)
            .put("episode", 3)
            .put("positionSec", 1180)
            .put("durationSec", 2030)
            .put("url", "/it/watch/4101"))
        // Episodio S1:E2 di Astral Express al 65%
        cw.put(JSONObject()
            .put("id", "site-4103")
            .put("titleId", 4103)
            .put("name", "Astral Express")
            .put("tag", "S1:E2")
            .put("progress", 0.65)
            .put("label", "S1:E2 · 8 min rimasti")
            .put("poster", demoArt("site-4103", "Astral Express", 400, 600))
            .put("season", 1)
            .put("episode", 2)
            .put("positionSec", 850)
            .put("durationSec", 1290)
            .put("url", "/it/watch/4103"))
        return cw
    }

    private fun buildDemoRows(): JSONObject {
        val all = JSONArray(listOf(
            "site-4101", "site-4102", "site-4103", "site-4104", "site-4105",
            "site-4106", "site-4107", "site-4108", "site-4109", "site-4110", "site-4111"
        ))
        val home = JSONArray().apply {
            put(JSONObject()
                .put("label", "Continua a guardare")
                .put("ids", JSONArray(listOf("site-4101", "site-4103"))))
            put(JSONObject()
                .put("label", "Trending adesso")
                .put("ids", JSONArray(listOf(
                    "site-4101", "site-4104", "site-4102", "site-4110", "site-4106"))))
            put(JSONObject()
                .put("label", "Top 10 oggi")
                .put("isTop10", true)
                .put("ids", JSONArray(listOf(
                    "site-4101", "site-4102", "site-4103",
                    "site-4104", "site-4105", "site-4106"))))
            put(JSONObject()
                .put("label", "SCtv Originals")
                .put("ids", JSONArray(listOf(
                    "site-4101", "site-4103", "site-4104",
                    "site-4107", "site-4110", "site-4113"))))
            put(JSONObject().put("label", "Tutto il catalogo").put("ids", all))
        }
        val tv = JSONArray().apply {
            put(JSONObject()
                .put("label", "Top 10 Serie oggi")
                .put("isTop10", true)
                .put("ids", JSONArray(listOf("site-4101", "site-4103"))))
            put(JSONObject()
                .put("label", "Tutte le serie")
                .put("ids", JSONArray(listOf(
                    "site-4101", "site-4103", "site-4107", "site-4110"))))
        }
        val movie = JSONArray().apply {
            put(JSONObject()
                .put("label", "Top 10 Film oggi")
                .put("isTop10", true)
                .put("ids", JSONArray(listOf(
                    "site-4102", "site-4104", "site-4105", "site-4106"))))
            put(JSONObject()
                .put("label", "Tutti i film")
                .put("ids", JSONArray(listOf(
                    "site-4102", "site-4104", "site-4105",
                    "site-4106", "site-4108", "site-4109", "site-4111"))))
        }
        return JSONObject()
            .put("home", home)
            .put("tv", tv)
            .put("movie", movie)
    }

    private fun buildDemoCatalog(): JSONArray {
        val arr = JSONArray()
        for (t in DEMO_TITLES) arr.put(demoTitleJson(t))
        return arr
    }

    private fun demoTitleJson(t: DemoTitle): JSONObject {
        val eps = JSONArray()
        for (e in DEMO_EPISODES[t.titleId].orEmpty()) {
            eps.put(JSONObject()
                .put("ep", e.ep)
                .put("id", t.titleId * 100 + e.season * 10 + e.ep)
                .put("season", e.season)
                .put("title", e.title)
                .put("duration", e.duration)
                .put("desc", e.desc)
                .put("streamUrl", e.streamUrl)
                .put("thumb", demoArt("${t.titleId}-s${e.season}e${e.ep}", "S${e.season}:E${e.ep}", 640, 360)))
        }
        val slug = slugify(t.title)
        val item = JSONObject()
            .put("id", "site-" + t.titleId)
            .put("titleId", t.titleId)
            .put("title", t.title)
            .put("type", t.type)
            .put("isOriginal", t.isOriginal)
            .put("isKids", t.isKids)
            .put("match", t.match)
            .put("year", t.year)
            .put("maturity", t.maturity)
            .put("durationLabel", t.durationLabel)
            .put("badges", JSONArray(t.badges))
            .put("genres", JSONArray(t.genres))
            .put("synopsis", t.synopsis)
            .put("cast", JSONArray(t.cast))
            .put("posterUrl", demoArt("site-" + t.titleId, t.title, 400, 600))
            .put("backdropUrl", demoArt("site-" + t.titleId, t.title, 1280, 720))
            .put("url", "/it/titles/${t.titleId}-$slug")
            .put("comingSoon", t.comingSoon)
            .put("seasons", t.seasons)
            .put("episodes", eps)
        t.top10Rank?.let { item.put("top10Rank", it) }
        t.creator?.let { item.put("creator", it) }
        t.releaseDate?.let { item.put("releaseDate", it) }
        return item
    }

    private fun slugify(s: String): String =
        s.lowercase()
            .replace("à", "a").replace("è", "e").replace("é", "e")
            .replace("ì", "i").replace("ò", "o").replace("ù", "u")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

    // -- Catalogo demo -------------------------------------------------------------

    /** Copertina/backdrop generati come SVG inline (data URI): il demo gira
     *  anche offline, senza CDN immagini. Stesso schema lato JS. */
    private val artPalette = listOf(
        "#E50914" to "#3B0A0D", "#2563EB" to "#111B45", "#059669" to "#052E24",
        "#D97706" to "#3B1F04", "#7C3AED" to "#2A0D45", "#DB2777" to "#450A26",
        "#0EA5E9" to "#0B2540", "#84CC16" to "#1F2B06"
    )

    private fun demoArt(seed: String, label: String, w: Int, h: Int): String {
        var hash = 2166136261L
        for (c in seed) {
            hash = hash xor c.code.toLong()
            hash *= 16777619L
        }
        val (c1, c2) = artPalette[((hash ushr 16) % artPalette.size).toInt()]
        val fs = (w * 1.7f / label.length.coerceAtLeast(4)).toInt()
            .coerceIn(18, if (w > 800) 96 else 52)
        val svg = "<svg xmlns='http://www.w3.org/2000/svg' width='$w' height='$h'>" +
            "<defs><linearGradient id='g' x1='0' y1='0' x2='1' y2='1'>" +
            "<stop offset='0' stop-color='$c1'/><stop offset='1' stop-color='$c2'/>" +
            "</linearGradient></defs>" +
            "<rect width='$w' height='$h' fill='url(#g)'/>" +
            "<text x='6%' y='88%' font-family='sans-serif' font-weight='800' " +
            "font-size='$fs' fill='rgba(255,255,255,0.88)'>$label</text></svg>"
        return "data:image/svg+xml;utf8," + Uri.encode(svg, "-_.!~*'()")
    }

    private class DemoTitle(
        val titleId: Int,
        val title: String,
        val type: String,               // "tv" | "movie"
        val match: Int,
        val year: Int,
        val maturity: String,
        val durationLabel: String,
        val genres: List<String>,
        val synopsis: String,
        val top10Rank: Int? = null,
        val isOriginal: Boolean = false,
        val isKids: Boolean = false,
        val creator: String? = null,
        val cast: List<String> = emptyList(),
        val badges: List<String> = listOf("HD"),
        val seasons: Int = 0,
        val comingSoon: Boolean = false,
        val releaseDate: String? = null
    )

    private class DemoEpisode(
        val season: Int,
        val ep: Int,
        val title: String,
        val duration: String,
        val desc: String,
        val streamUrl: String
    )

    private val DEMO_TITLES = listOf(
        DemoTitle(
            4101, "Ombra di Sangue", "tv", 97, 2025, "16+", "2 stagioni",
            listOf("Thriller", "Crime", "Drama"),
            "Una detective della squadra mobile torna in città dopo dieci anni " +
                "per indagare su una serie di omicidi che replicano un caso " +
                "mai chiuso: quello che l'ha costretta a andarsene.",
            top10Rank = 1, isOriginal = true, creator = "Sara Donati",
            cast = listOf("Aurora Vecchi", "Marco Rinaldi", "Elena Bruni"),
            badges = listOf("4K", "DUB"), seasons = 2
        ),
        DemoTitle(
            4102, "Ferro e Velluto", "movie", 91, 2024, "14+", "1h 58min",
            listOf("Drama", "Romance"),
            "Due famiglie di allenatori, un cavallo solo: al palio di settembre " +
                "una rivalità decennale si trasforma in qualcosa di più " +
                "pericoloso dell'ambizione.",
            top10Rank = 2,
            cast = listOf("Giulia Ferrero", "Paolo Sant'Elia")
        ),
        DemoTitle(
            4103, "Astral Express", "tv", 95, 2025, "T", "2 stagioni",
            listOf("Animation", "Adventure", "Family"),
            "Un treno che viaggia tra le stelle, un equipaggio di bambini " +
                "coraggiosi e una mappa di galassie da salvare, una stazione " +
                "alla volta.",
            top10Rank = 3, isOriginal = true, isKids = true,
            badges = listOf("4K", "DUB"), seasons = 2
        ),
        DemoTitle(
            4104, "L'Ultima Fiamma", "movie", 88, 2025, "16+", "2h 04min",
            listOf("Action", "Thriller"),
            "Un pompiere in congedo riconosce l'incendiarista che ha distrutto " +
                "la sua squadra: adesso la caccia è personale, e la città " +
                "brucia di nuovo.",
            top10Rank = 4, isOriginal = true,
            cast = listOf("Riccardo Fanti", "Serena Lupi")
        ),
        DemoTitle(
            4105, "Neviche Stagioni", "movie", 84, 2023, "T", "1h 47min",
            listOf("Comedy", "Romance"),
            "Due sconosciuti bloccati da una bufera in un rifugio di montagna " +
                "scoprono di essere, ognuno per conto proprio, il motivo per " +
                "cui l'altro è lì.",
            top10Rank = 5,
            cast = listOf("Marta Cei", "Luca Vanni")
        ),
        DemoTitle(
            4106, "Il Sarto di Palermo", "movie", 90, 2024, "14+", "2h 11min",
            listOf("Crime", "Drama"),
            "Il miglior sarto della città cuce abiti per la mafia finché una " +
                "giornalista non gli chiede di scegliere: il silenzio o il " +
                "testimone.",
            top10Rank = 6,
            cast = listOf("Vito Arena", "Chiara Lupo", "Nino Ferrara")
        ),
        DemoTitle(
            4107, "Deep Circle", "tv", 86, 2024, "16+", "1 stagione",
            listOf("Sci-Fi", "Mystery"),
            "Una stazione subacquea privata smette di rispondere: i sei " +
                "sopravvissuti scoprono che il cerchio di dosti che li " +
                "proteggeva è stato chiuso dall'interno.",
            isOriginal = true, creator = "Marco Petti",
            cast = listOf("Irene Bassi", "Tommaso Riva"), seasons = 1
        ),
        DemoTitle(
            4108, "Risata Criminale", "movie", 79, 2022, "14+", "1h 39min",
            listOf("Comedy", "Crime"),
            "Un comico fallito viene scambiato per un killer in fuga e " +
                "scopre che lo stand-up nella malavita è molto più semplice: " +
                "lì il pubblico non fischietta.",
            cast = listOf("Bruno Meli", "Sofia Renda")
        ),
        DemoTitle(
            4109, "Piccoli Eroi", "movie", 92, 2025, "T", "1h 32min",
            listOf("Family", "Adventure"),
            "Quando il parco del quartiere viene chiuso, quattro amici di " +
                "dieci anni organizzano la più disordinata (e efficace) " +
                "campagna civica della storia.",
            isKids = true, badges = listOf("4K", "DUB")
        ),
        DemoTitle(
            4110, "La Torre Silente", "tv", 93, 2025, "16+", "1 stagione",
            listOf("Mystery", "Drama"),
            "In un grattacielo dove nessuno si parla da vent'anni, una " +
                "ragazza inizia a ricevere biglietti che descrivono la sua " +
                "giornata… un giorno prima che accada.",
            isOriginal = true, creator = "Elena Grasso",
            cast = listOf("Noemi Dati", "Andrea Villoresi"), seasons = 1
        ),
        DemoTitle(
            4111, "Velocità Estrema", "movie", 77, 2021, "16+", "1h 52min",
            listOf("Action"),
            "Un pilota ritirato accetta una corsa attraverso tre confini in " +
                "una notte: il carico è illegale, il passeggero è peggio.",
            cast = listOf("Dario Costa", "Mila Renzi")
        ),
        DemoTitle(
            4112, "Dopo il Silenzio", "movie", 0, 2026, "16+", "TBA",
            listOf("Thriller"),
            "Dodici anni dopo la scomparsa della sorella, un'ingegnere " +
                "acustica riceve una registrazione dove la voce di lei " +
                "chiama per nome la sua città.",
            comingSoon = true, releaseDate = "5 Dic 2025"
        ),
        DemoTitle(
            4113, "Astral Express: Il Film", "movie", 0, 2026, "T", "1h 36min",
            listOf("Animation", "Family"),
            "Il viaggio più lungo della Express: oltre l'ultima stazione " +
                "della mappa c'è il mondo che ha costruito il treno.",
            isOriginal = true, isKids = true, comingSoon = true,
            releaseDate = "12 Gen 2026"
        )
    )

    private val DEMO_EPISODES: Map<Int, List<DemoEpisode>> = mapOf(
        4101 to listOf(
            DemoEpisode(1, 1, "Il primo scomparso", "52min",
                "Il ritorno in città coincide con un caso che ha l'odore del vecchio diario.",
                SAMPLE_BASE + "BigBuckBunny.mp4"),
            DemoEpisode(1, 2, "Sangue freddo", "48min",
                "La lista dei sospetti si accorcia di un nome troppo presto.",
                SAMPLE_BASE + "ElephantsDream.mp4"),
            DemoEpisode(1, 3, "La talpa", "47min",
                "Chiunque consegni le foto alla stampa è dentro la squadra.",
                SAMPLE_BASE + "ForBiggerBlazes.mp4"),
            DemoEpisode(1, 4, "Fuori dal buio", "55min",
                "Il caso del 2015 si chiude su se stesso: tutto torna, perfino l'errore.",
                SAMPLE_BASE + "ForBiggerEscapes.mp4"),
            DemoEpisode(2, 1, "Nuova linfa", "50min",
                "Una nuova recluta porta con sé un fascicolo che doveva restare chiuso.",
                SAMPLE_BASE + "ForBiggerFun.mp4"),
            DemoEpisode(2, 2, "Il patto", "46min",
                "Il sindaco offre la verità in cambio di un silenzio di tre mesi.",
                SAMPLE_BASE + "ForBiggerJoyrides.mp4"),
            DemoEpisode(2, 3, "Conto alla rovescia", "58min",
                "L'ultimo atto si gioca in centrale: ormai chi è la talpa lo sanno tutti.",
                SAMPLE_BASE + "ForBiggerMeltdowns.mp4")
        ),
        4103 to listOf(
            DemoEpisode(1, 1, "Partenza!", "22min",
                "La Express riceve il primo biglietto a bordo di un passeggero fantasma.",
                SAMPLE_BASE + "Sintel.mp4"),
            DemoEpisode(1, 2, "La stazione lunare", "21min",
                "Una deviazione imprevista porta l'equipaggio dentro una tempesta di polvere.",
                SAMPLE_BASE + "TearsOfSteel.mp4"),
            DemoEpisode(1, 3, "Il passeggero senza nome", "24min",
                "Il biglietto si rinnova da solo e la mappa cresce di una galassia.",
                SAMPLE_BASE + "VolkswagenGTIReview.mp4"),
            DemoEpisode(2, 1, "Rotazione imprevista", "23min",
                "Il treno impara a tornare indietro nel tempo di una fermata.",
                SAMPLE_BASE + "WeAreGoingOnBullrun.mp4"),
            DemoEpisode(2, 2, "La cometa blu", "22min",
                "La cometa blu chiede un aiuto che l'equipaggio non sa ancora dare.",
                SAMPLE_BASE + "WhatCarCanYouGetForAGrand.mp4")
        ),
        4107 to listOf(
            DemoEpisode(1, 1, "Immersione", "45min",
                "La Base Cerchio perde i contatti con la superficie a 3.100 metri.",
                SAMPLE_BASE + "BigBuckBunny.mp4"),
            DemoEpisode(1, 2, "Il cerchio", "43min",
                "Il sistema di bordo riconosce un equipaggio che non è mai salito.",
                SAMPLE_BASE + "ElephantsDream.mp4"),
            DemoEpisode(1, 3, "Superficie", "47min",
                "L'unico modo per uscire è diventare quello che il cerchio aspetta.",
                SAMPLE_BASE + "ForBiggerBlazes.mp4")
        ),
        4110 to listOf(
            DemoEpisode(1, 1, "Apertura", "44min",
                "Il primo biglietto descrive una giornata che non è ancora successa.",
                SAMPLE_BASE + "Sintel.mp4"),
            DemoEpisode(1, 2, "Piano dopo piano", "42min",
                "Le videocamere della torre mostrano sempre lo stesso corridoio.",
                SAMPLE_BASE + "TearsOfSteel.mp4"),
            DemoEpisode(1, 3, "L'ultima porta", "49min",
                "Il piano che non esiste nelle planimetrie risponde al citofono.",
                SAMPLE_BASE + "VolkswagenGTIReview.mp4")
        )
    )

    // -- Handler bridge M1 ---------------------------------------------------------

    /** M1 stub: il player nativo arriva in M3 — per ora confermiamo solo che
     *  il flusso JS→nativo funziona, con i parametri giusti. */
    private fun onPlayRequested(titleId: Int, season: Int, episode: Int) {
        val t = DEMO_TITLES.firstOrNull { it.titleId == titleId }
        val what = if (t != null && t.type == "tv") " S$season:E$episode" else ""
        val name = t?.title ?: "Titolo #$titleId"
        Toast.makeText(
            this,
            "Riproduzione: $name$what (player nativo in arrivo — M3)",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun openNativeSettings() {
        SettingsDialog(
            this,
            onSaved = { modeChanged ->
                // Il cap qualità vive in HlsRewriter (già attivo per il sito).
                HlsRewriter.maxHeightOverride = SettingsStore.qualityCapHeight(this)
                // Dal dialog aperto dentro SCtv l'unico cambio possibile è
                // verso il sito: routing immediato come da sidebar sito.
                if (modeChanged) switchToSite()
            }
        ).show()
    }

    private fun switchToSite() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
        )
        finish()
    }

    // -- D-pad → handleRemoteAction / BACK → __sctvBackState ------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
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
                // Il JS decide: 'exit' → chiudiamo l'app, 'stay' → ha già
                // gestito (modal aperta, tab diversa da home, gate profili).
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

    // -- Ciclo di vita ---------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        web.onResume()
        // Mantieni il dominio fresco anche qui (la modalità sito lo fa in
        // onResume): serve a M2, già inoffensivo in M1.
        scope.launch { runCatching { domainRepo.refreshOnline() } }
        // Rail "Continua a guardare" aggiornata a ogni ritorno dal player.
        if (booted) {
            push(
                "progressChanged",
                JSONObject().put("continueWatching", buildContinueWatching())
            )
        }
    }

    override fun onPause() {
        super.onPause()
        web.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        web.apply {
            stopLoading()
            removeAllViews()
            destroy()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            web.clearCache(false)
        }
    }
}
