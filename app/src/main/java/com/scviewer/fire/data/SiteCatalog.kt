package com.scviewer.fire.data

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Un titolo per l'interfaccia TV nativa: i campi serviti dalle righe
 *  di poster e dall'hero. I dati vengono dal JSON Inertia (data-page)
 *  delle pagine del sito — stesso reverse engineering del PageInfo JS.
 *
 *  I campi con default (M2) sono popolati SOLO quando il JSON di origine li
 *  contiene: i titoli degli slider sono "lean" (senza genres/dub/quality),
 *  quelli della pagina dettaglio hanno i metadati completi. */
data class TvTitle(
    val id: Int,
    val slug: String,
    val name: String,
    val type: String,          // "tv" | "movie"
    val score: String?,
    val plot: String?,
    val seasonsCount: Int?,
    val year: Int?,
    val posterUrl: String?,
    val backgroundUrl: String?,
    val url: String,           // percorso relativo della pagina titolo
    // -- Metadati estesi (M2) ------------------------------------------------
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val creator: String? = null,
    val maturity: String? = null,      // "12+", "T"
    val badges: List<String> = emptyList(),  // ["HD", "DUB", "SUB"]
    val durationLabel: String? = null, // "1h 58min" | "2 stagioni"
    val match: Int? = null,            // score 0-10 → percentuale 0-100
    val top10Rank: Int? = null,        // top10_index della slider top10 (1-based)
    val comingSoon: Boolean = false,
    val releaseDate: String? = null,   // data di uscita formattata (coming soon)
    val isKids: Boolean = false,       // euristica età/generi (il sito non ha flag)
    val runtimeMin: Int? = null,
    val hasFullMeta: Boolean = false   // true se parsato dalla pagina dettaglio
)

/** Una riga orizzontale della home TV. */
data class TvRow(val label: String, val items: List<TvTitle>)

/** Client dati per l'interfaccia TV: scarica le pagine del sito (HTML SSR)
 *  ed estrae il JSON Inertia dall'attributo data-page — nessun endpoint
 *  privato, stesse pagine che visita il WebView. */
object SiteCatalog {

    private const val TAG = "SiteCatalog"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** GET path (relativo, es. "/it/movies") sul dominio corrente e parse
     *  del JSON data-page → JSONObject completo di props. */
    fun fetchProps(path: String, domain: String): JSONObject? {
        val url = if (path.startsWith("http")) path else domain.trimEnd('/') + path
        return runCatching {
            client.newCall(
                Request.Builder().url(url)
                    .header("User-Agent", DESKTOP_UA)
                    .header("Accept", "text/html")
                    .build()
            ).execute().use { resp ->
                Log.i(TAG, "fetchProps: HTTP ${resp.code} url=${resp.request.url}")
                if (!resp.isSuccessful) return@use null
                val html = resp.body?.string() ?: return@use null
                val m = Regex("data-page=\"([^\"]+)\"").find(html) ?: return@use null
                val parsed = JSONObject(htmlUnescaped(m.groupValues[1]))
                Log.i(TAG, "fetchProps: component=${parsed.optString("component")}")
                parsed.optJSONObject("props")
            }
        }.onFailure { Log.w(TAG, "fetchProps($path) failed", it) }.getOrNull()
    }

    private fun htmlUnescaped(raw: String): String = raw
        .replace("&quot;", "\"")
        .replace("&#0?39;".toRegex(), "'")
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&amp;", "&")

    /** Busta Inertia COMPLETA (component + version + props): serve a M2 per
     *  leggere `version`, indispensabile per i partial reload del cambio
     *  stagione (header X-Inertia-Version). */
    fun fetchEnvelope(path: String, domain: String): JSONObject? {
        val url = if (path.startsWith("http")) path else domain.trimEnd('/') + path
        return runCatching {
            client.newCall(
                Request.Builder().url(url)
                    .header("User-Agent", DESKTOP_UA)
                    .header("Accept", "text/html")
                    .build()
            ).execute().use { resp ->
                Log.i(TAG, "fetchEnvelope: HTTP ${resp.code} url=${resp.request.url}")
                if (!resp.isSuccessful) return@use null
                val html = resp.body?.string() ?: return@use null
                val m = Regex("data-page=\"([^\"]+)\"").find(html) ?: return@use null
                JSONObject(htmlUnescaped(m.groupValues[1]))
            }
        }.onFailure { Log.w(TAG, "fetchEnvelope($path) failed", it) }.getOrNull()
    }

    /** Esito del partial reload Inertia del cambio stagione. */
    data class SeasonPartial(
        val code: Int,
        /** props.loadedSeason della stagione richiesta (null su 409/versione scaduta). */
        val loadedSeason: JSONObject?,
        /** Nuova versione della busta, se la risposta l'ha rivelata. */
        val version: String?
    )

    /** GET `<path>/season-<N>` con gli header di partial reload Inertia:
     *  risposta ~10KB JSON {props:{loadedSeason}} invece della pagina intera.
     *  Su versione scaduta il server risponde 409 (corpo vuoto): il chiamante
     *  deve ri-fetchare la busta per aggiornare la version e riprovare. */
    fun fetchSeasonPartial(
        titlePath: String,
        season: Int,
        domain: String,
        inertiaVersion: String
    ): SeasonPartial {
        val url = domain.trimEnd('/') + titlePath.trimEnd('/') + "/season-$season"
        return runCatching {
            client.newCall(
                Request.Builder().url(url)
                    .header("User-Agent", DESKTOP_UA)
                    .header("Accept", "text/html")
                    .header("X-Inertia", "true")
                    .header("X-Inertia-Version", inertiaVersion)
                    .header("X-Inertia-Partial-Component", "Titles/Title")
                    .header("X-Inertia-Partial-Data", "loadedSeason")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .build()
            ).execute().use { resp ->
                Log.i(TAG, "fetchSeasonPartial(s=$season): HTTP ${resp.code}")
                val code = resp.code
                if (code != 200) return@use SeasonPartial(code, null, null)
                val body = resp.body?.string() ?: return@use SeasonPartial(code, null, null)
                // Per prudenza accettiamo anche l'HTML intero (alcuni proxy
                // rispondono 200 con la pagina completa): riparsiamo data-page.
                if (!body.trimStart().startsWith("{")) {
                    val m = Regex("data-page=\"([^\"]+)\"").find(body) ?: return@use SeasonPartial(code, null, null)
                    val env = JSONObject(htmlUnescaped(m.groupValues[1]))
                    return@use SeasonPartial(
                        code,
                        env.optJSONObject("props")?.optJSONObject("loadedSeason"),
                        env.optString("version").takeIf { it.isNotBlank() }
                    )
                }
                val env = JSONObject(body)
                SeasonPartial(
                    code,
                    env.optJSONObject("props")?.optJSONObject("loadedSeason"),
                    env.optString("version").takeIf { it.isNotBlank() }
                )
            }
        }.onFailure {
            Log.w(TAG, "fetchSeasonPartial(s=$season) failed", it)
        }.getOrDefault(SeasonPartial(0, null, null))
    }

    private fun imagesUrl(props: JSONObject, filename: String?) =
        if (filename.isNullOrBlank()) null
        else props.optString("cdn_url", "").trimEnd('/') + "/images/" + filename

    private fun pickImage(images: org.json.JSONArray, type: String): String? {
        for (i in 0 until images.length()) {
            val im = images.optJSONObject(i) ?: continue
            if (im.optString("type") == type) return im.optString("filename")
        }
        return null
    }

    /** JSONObject di un singolo titolo → TvTitle. Popola anche i metadati
     *  estesi quando presenti (pagina dettaglio = completo; slider = lean). */
    fun parseTitle(t: JSONObject, props: JSONObject, domain: String): TvTitle? {
        val id = t.optInt("id", 0)
        val slug = t.optString("slug")
        if (id == 0 || slug.isBlank()) return null
        val images = t.optJSONArray("images")
        val plot = t.optJSONArray("translations")?.let { trs ->
            for (i in 0 until trs.length()) {
                val tr = trs.optJSONObject(i) ?: continue
                if (tr.optString("key") == "plot" && tr.optString("locale") == "it") {
                    return@let tr.optString("value").takeIf { it.isNotBlank() }
                }
            }
            null
        } ?: t.optString("plot").takeIf { it.isNotBlank() && it != "null" }
        val lastAir = t.optString("last_air_date_it").ifBlank { t.optString("last_air_date") }
            .ifBlank { t.optString("release_date_it").ifBlank { t.optString("release_date") } }
        val year = lastAir.take(4).toIntOrNull()?.takeIf { it > 1900 }

        // -- Metadati estesi ---------------------------------------------------
        val hasFullMeta = t.has("genres") && t.has("main_actors")
        val genres = t.optJSONArray("genres")?.let { gs ->
            (0 until gs.length()).mapNotNull { gs.optJSONObject(it)?.optString("name") }
                .filter { it.isNotBlank() }
        } ?: emptyList()
        val cast = t.optJSONArray("main_actors")?.let { acts ->
            (0 until acts.length())
                .mapNotNull { acts.optJSONObject(it) }
                .sortedBy { it.optJSONObject("pivot")?.optInt("order", 0) ?: 0 }
                .mapNotNull { it.optString("name").takeIf { n -> n.isNotBlank() } }
                .take(8)
        } ?: emptyList()
        val creator = t.optJSONArray("main_directors")?.let { ds ->
            (0 until ds.length()).mapNotNull { ds.optJSONObject(it)?.optString("name") }
                .filter { it.isNotBlank() }
                .joinToString(", ")
                .takeIf { it.isNotBlank() }
        }
        val age = t.optInt("age", -1)
        val maturity = when {
            age > 0 -> "$age+"
            age == 0 -> "T"
            else -> null
        }
        val quality = t.optString("quality").takeIf { it.isNotBlank() && it != "null" }?.uppercase()
        val badges = buildList {
            quality?.let { add(it) }
            if (t.optInt("dub_ita", 0) == 1) add("DUB")
            if (t.optInt("sub_ita", 0) == 1) add("SUB")
        }
        val runtime = t.optInt("runtime", 0).takeIf { it > 0 }
        val seasonsCount = t.optInt("seasons_count", 0).takeIf { it > 0 }
        val type = t.optString("type").ifBlank {
            // L'hero della home non ha il campo type: lo si deduce.
            if ((seasonsCount ?: 0) > 0) "tv" else "movie"
        }
        val durationLabel = when {
            type == "tv" && (seasonsCount ?: 0) > 0 ->
                if (seasonsCount == 1) "1 stagione" else "$seasonsCount stagioni"
            runtime != null && runtime > 0 ->
                if (runtime >= 60) "${runtime / 60}h ${runtime % 60}min" else "${runtime}min"
            else -> null
        }
        val match = t.optString("score").toDoubleOrNull()
            ?.takeIf { it in 0.0..10.0 }?.let { Math.round(it * 10).toInt() }
        val top10Rank = t.optInt("top10_index", 0).takeIf { it > 0 }
        val comingSoon = t.optBoolean("coming_soon", false)
        val releaseDate = t.optString("release_date_it").ifBlank { t.optString("release_date") }
            .takeIf { it.isNotBlank() && it != "null" }?.let { formatReleaseDate(it) }
        val isKids = age in 0..6 || genres.any { it in KIDS_GENRES }

        return TvTitle(
            id = id,
            slug = slug,
            name = t.optString("name"),
            type = type,
            score = t.optString("score").takeIf { it.isNotBlank() && it != "null" },
            plot = plot,
            seasonsCount = seasonsCount,
            year = year,
            posterUrl = imagesUrl(props, images?.let { pickImage(it, "poster") }),
            backgroundUrl = imagesUrl(props, images?.let { pickImage(it, "background") }),
            url = "/it/titles/$id-$slug",
            genres = genres,
            cast = cast,
            creator = creator,
            maturity = maturity,
            badges = badges,
            durationLabel = durationLabel,
            match = match,
            top10Rank = top10Rank,
            comingSoon = comingSoon,
            releaseDate = releaseDate,
            isKids = isKids,
            runtimeMin = runtime,
            hasFullMeta = hasFullMeta
        )
    }

    /** Generi che attivano il profilo kids sull'interfaccia SCtv. */
    private val KIDS_GENRES = setOf(
        "Animation", "Animazione", "Family", "Famiglia", "Kids", "Bambini"
    )

    /** "2025-12-10" → "10 dic 2025" (null su formati inattesi). */
    private fun formatReleaseDate(iso: String): String? {
        val m = Regex("(\\d{4})-(\\d{2})-(\\d{2})").find(iso) ?: return iso
        val months = listOf("gen", "feb", "mar", "apr", "mag", "giu",
            "lug", "ago", "set", "ott", "nov", "dic")
        val monthIdx = m.groupValues[2].toIntOrNull()?.let { it - 1 } ?: return iso
        if (monthIdx !in months.indices) return iso
        return "${m.groupValues[3].toInt()} ${months[monthIdx]} ${m.groupValues[1]}"
    }

    private fun titlesArray(obj: JSONObject, key: String): List<org.json.JSONArray> {
        // titles può essere [ ... ] oppure { data: [ ... ] } (paginato)
        return when (val v = obj.opt(key)) {
            is org.json.JSONArray -> listOf(v)
            is JSONObject -> listOfNotNull(v.optJSONArray("data"))
            else -> emptyList()
        }
    }

    /** Slider di una pagina (/ = home, /it/movies, /it/tv-shows — il sito usa
     *  componenti Home* con la stessa forma ovunque). */
    fun slidersRows(path: String, domain: String): List<TvRow> {
        val rows = ArrayList<TvRow>()
        val props = fetchProps(path, domain) ?: run {
            Log.w(TAG, "slidersRows($path): props null")
            return rows
        }
        Log.i(TAG, "slidersRows($path): sliders=${props.optJSONArray("sliders")?.length() ?: 0}")
        val sliders = props.optJSONArray("sliders") ?: return rows
        for (i in 0 until sliders.length()) {
            val s = sliders.optJSONObject(i) ?: continue
            val items = s.optJSONArray("titles") ?: continue
            val list = (0 until items.length()).mapNotNull { idx ->
                parseTitle(items.optJSONObject(idx) ?: return@mapNotNull null, props, domain)
            }
            if (list.isNotEmpty()) rows.add(TvRow(s.optString("label"), list))
        }
        return rows
    }

    /** Pagina Browse (/it/movies, /it/tv-shows): lista paginata di titoli. */
    fun browse(path: String, domain: String): List<TvTitle> {
        val props = fetchProps(path, domain) ?: run {
            Log.w(TAG, "browse($path): props null")
            return emptyList()
        }
        val out = titlesArray(props, "titles").flatMap { arr ->
            (0 until arr.length()).mapNotNull { idx ->
                parseTitle(arr.optJSONObject(idx) ?: return@mapNotNull null, props, domain)
            }
        }
        Log.i(TAG, "browse($path): ${out.size} titoli (titles è array? ${props.opt("titles")?.javaClass?.simpleName})")
        return out
    }

    /** Ricerca. */
    fun search(query: String, domain: String): List<TvTitle> =
        browse("/it/search?q=" + java.net.URLEncoder.encode(query, "UTF-8"), domain)

    /** Righe "Continua a guardare" dal ProgressStore locale. */
    fun continueWatching(context: android.content.Context, domain: String): List<TvTitle> =
        ProgressStore.get(context).resumeable().map { p ->
            TvTitle(
                id = p.titleId, slug = p.slug, name = p.name, type = p.type,
                score = null, plot = p.episodeName?.let { "S${p.season ?: "?"}E${p.episode ?: "?"} · $it" },
                seasonsCount = null, year = null, posterUrl = p.poster, backgroundUrl = null,
                url = p.url
            )
        }
}
