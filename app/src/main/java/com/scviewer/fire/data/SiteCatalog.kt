package com.scviewer.fire.data

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Un titolo per l'interfaccia TV nativa: i campi serviti dalle righe
 *  di poster e dall'hero. I dati vengono dal JSON Inertia (data-page)
 *  delle pagine del sito — stesso reverse engineering del PageInfo JS. */
data class TvTitle(
    val id: Int,
    val slug: String,
    val name: String,
    val type: String,          // "tv" | "movie"
    val score: String?,
    val plot: String?,
    val seasonsCount: Int?,
    val posterUrl: String?,
    val backgroundUrl: String?,
    val url: String            // percorso relativo della pagina titolo
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
                if (!resp.isSuccessful) return@use null
                val html = resp.body?.string() ?: return@use null
                val m = Regex("data-page=\"([^\"]+)\"").find(html) ?: return@use null
                val unescaped = m.groupValues[1]
                    .replace("&quot;", "\"")
                    .replace("&#0?39;".toRegex(), "'")
                    .replace("&lt;", "<").replace("&gt;", ">")
                    .replace("&amp;", "&")
                // Il data-page è il wrapper Inertia {component, props, url,
                // version}: a noi serve l'oggetto "props" interno.
                JSONObject(unescaped).optJSONObject("props")
            }
        }.onFailure { Log.w(TAG, "fetchProps($path) failed", it) }.getOrNull()
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

    /** JSONObject di un singolo titolo → TvTitle. */
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
        }
        return TvTitle(
            id = id,
            slug = slug,
            name = t.optString("name"),
            type = t.optString("type"),
            score = t.optString("score").takeIf { it.isNotBlank() && it != "null" },
            plot = plot,
            seasonsCount = t.optInt("seasons_count", 0).takeIf { it > 0 },
            posterUrl = imagesUrl(props, images?.let { pickImage(it, "poster") }),
            backgroundUrl = imagesUrl(props, images?.let { pickImage(it, "background") }),
            url = "/it/titles/$id-$slug"
        )
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
                seasonsCount = null, posterUrl = p.poster, backgroundUrl = null,
                url = p.url
            )
        }
}
