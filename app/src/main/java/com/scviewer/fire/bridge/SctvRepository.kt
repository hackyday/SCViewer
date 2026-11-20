package com.scviewer.fire.bridge

import android.content.Context
import android.util.Log
import com.scviewer.fire.data.DEFAULT_PROFILE_ID
import com.scviewer.fire.data.FavoritesStore
import com.scviewer.fire.data.PosterCache
import com.scviewer.fire.data.ProgressStore
import com.scviewer.fire.data.SiteCatalog
import com.scviewer.fire.data.SiteUrl
import com.scviewer.fire.data.TvTitle
import com.scviewer.fire.data.sanitizeProfileId
import com.scviewer.fire.data.WatchProgress
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Sorgenti dati REALI dell'interfaccia SCtv (M2) — piano §3.
 *
 * Tutte le funzioni sono BLOCCANTI (da chiamare su Dispatchers.IO): incapsulano
 * le fetch del sito ([SiteCatalog]) e i store locali, e producono direttamente
 * i JSON dell'evento push verso il JS (contratto piano §2).
 *
 * Struttura (quella prevista dal piano §3, mantenuta in un file unico per
 * coerenza con §6 "Creati: bridge/SctvRepository.kt"):
 *  - [getPage]        = SiteClient: cache pagina Inertia (TTL) + throttling;
 *  - [bootPayload] /
 *    [tabPayload]     = CatalogSource: home/serie/film + righe dagli slider;
 *  - [detailPayload] /
 *    [seasonEpisodes] = TitleDetailSource: LRU (max 20) + partial reload;
 *  - [continueWatchingJson] / [myListIds] / [toggleMyList] = LocalSources.
 *
 * SCOPO PROFILI: i metodi dati che leggono/scrivono store locali (My List,
 * Continua a guardare) e il boot che li incorpora prendono tutti un
 * `profileId` (default "default" — modalità sito e dati legacy). Il
 * snapshot offline è per profilo.
 *
 * SLUG (verificato con curl 2026-11-20): `/it/titles/<id>` SENZA slug → 404,
 * ma `/it/titles/<id>-<qualsiasi-slug>` → 200: la route del sito risolve per
 * ID e ignora lo slug. Quindi la risoluzione è best-effort, in ordine:
 *  1. LRU dettaglio / titoli noti (slug canonico dalla risposta precedente);
 *  2. FavoritesStore / ProgressStore (regex `/it/titles/(\d+)-(.+)`);
 *  3. fallback "t" — il sito serve comunque il titolo giusto e la risposta
 *     riporta lo slug canonico, che qui viene messo in cache per le volte
 *     successive. Scelta documentata: la fetch diretta senza slug è SEMPRE
 *     evitata, il placeholder è corretto perché il bind è per id.
 */
class SctvRepository(private val context: Context) {

    companion object {
        private const val TAG = "SctvRepository"
        private const val PAGE_TTL_MS = 5 * 60_000L
        private const val DETAIL_LRU_MAX = 20
        private const val KNOWN_TITLES_MAX = 600

        /** Rate sul sito: 1-2 req/s (piano §3 rischi) — spacing minimo. */
        private const val MIN_REQUEST_SPACING_MS = 400L

        private const val SNAPSHOT_FILE = "sctv_boot_snapshot.json"

        /** Estrae lo slug da un path `/it/titles/<id>-<slug>`. */
        private val SLUG_REGEX = Regex("/it/titles/(\\d+)-([a-z0-9\\-]+)")
    }

    // -- Cache pagine Inertia (SiteClient) --------------------------------------

    private class CachedPage(val props: JSONObject, val version: String, val fetchedAt: Long)

    private val pageCache = LinkedHashMap<String, CachedPage>()

    // -- LRU dettaglio (TitleDetailSource) ---------------------------------------

    private class DetailEntry(
        val item: JSONObject,          // item CATALOG completo (episodi stagione default)
        val slug: String,
        val version: String,           // versione Inertia per i partial reload
        val cdnUrl: String,            // base immagini (riusata per gli episodi)
        val defaultSeason: Int
    ) {
        /** Episodi per numero stagione — popolata dai partial reload. */
        val seasons = HashMap<Int, JSONArray>()
    }

    private val detailCache = LinkedHashMap<Int, DetailEntry>(16, 0.75f, true)

    /** Tutti i titoli visti (slider, search, dettaglio): serve al toggleMyList
     *  e alla risoluzione slug senza una fetch extra. */
    private val knownTitles = LinkedHashMap<Int, TvTitle>()

    private var lastRequestAt = 0L

    /** Spaziatura minima tra due richieste verso il sito (rate 1-2 req/s). */
    private fun throttle() {
        synchronized(this) {
            val now = System.currentTimeMillis()
            val wait = lastRequestAt + MIN_REQUEST_SPACING_MS - now
            if (wait > 0) {
                try { Thread.sleep(wait) } catch (_: InterruptedException) {}
            }
            lastRequestAt = System.currentTimeMillis()
        }
    }

    // -- CatalogSource ------------------------------------------------------------

    /** Props di una pagina Inertia con cache TTL; null se la fetch fallisce. */
    private fun getPage(path: String, domain: String, allowStale: Boolean = false): CachedPage? {
        val cached = synchronized(pageCache) { pageCache[path] }
        val fresh = cached != null && System.currentTimeMillis() - cached.fetchedAt < PAGE_TTL_MS
        if (fresh) return cached
        throttle()
        val env = SiteCatalog.fetchEnvelope(path, domain) ?: run {
            // Offline/Cloudflare: con allowStale la copia scaduta è meglio di niente.
            return if (allowStale) cached else null
        }
        val page = CachedPage(
            props = env.optJSONObject("props") ?: JSONObject(),
            version = env.optString("version"),
            fetchedAt = System.currentTimeMillis()
        )
        synchronized(pageCache) { pageCache[path] = page }
        return page
    }

    /** Righe + catalogo da una pagina con slider (/ , /it/tv-shows, /it/movies).
     *  I titoli sono dedup-ati per id dentro [catalogAcc] e gli id item
     *  ("site-<id>") finiscono nella riga corrispondente. */
    private fun addSlidersRows(
        page: CachedPage?,
        catalogAcc: LinkedHashMap<String, JSONObject>,
        filterType: String?   // null = tutti; "tv"/"movie" = filtra per tipo
    ): JSONArray {
        val rows = JSONArray()
        if (page == null) return rows
        val sliders = page.props.optJSONArray("sliders") ?: return rows
        for (i in 0 until sliders.length()) {
            val s = sliders.optJSONObject(i) ?: continue
            val sliderName = s.optString("name")
            val titles = s.optJSONArray("titles") ?: continue
            val ids = JSONArray()
            for (k in 0 until titles.length()) {
                val raw = titles.optJSONObject(k) ?: continue
                val t = SiteCatalog.parseTitle(raw, page.props, "") ?: continue
                rememberTitle(t)
                if (filterType != null && t.type != filterType) continue
                val keyId = "site-" + t.id
                if (!catalogAcc.containsKey(keyId)) catalogAcc[keyId] = itemJson(t)
                ids.put(keyId)
            }
            if (ids.length() > 0) {
                val row = JSONObject()
                    .put("label", s.optString("label").ifBlank { sliderName })
                    .put("ids", ids)
                if (sliderName == "top10") row.put("isTop10", true)
                rows.put(row)
            }
        }
        return rows
    }

    /** Payload dell'evento boot: home + serie + film, hero, righe, CW e My List
     *  (CW e My List SCOPATI al profilo [profileId]).
     *  Null se la home non è raggiungibile (l'Activity gestisce recovery). */
    fun bootPayload(domain: String, uiMode: String, profileId: String = DEFAULT_PROFILE_ID): JSONObject? {
        val home = getPage("/", domain)
            ?: return null
        val tv = getPage("/it/tv-shows", domain)
        val movie = getPage("/it/movies", domain)

        val catalog = LinkedHashMap<String, JSONObject>()
        val homeRows = JSONArray()
        val cw = continueWatchingJson(profileId)

        // Rail "Continua a guardare" sempre in cima alla home.
        if (cw.length() > 0) {
            val ids = JSONArray()
            for (i in 0 until cw.length()) {
                val cwItem = cw.optJSONObject(i) ?: continue
                val keyId = cwItem.optString("id")
                if (!catalog.containsKey(keyId)) {
                    catalog[keyId] = JSONObject()
                        .put("id", keyId)
                        .put("titleId", cwItem.optInt("titleId"))
                        .put("title", cwItem.optString("name"))
                        .put("type", cwItem.optString("type", "movie"))
                        .put("isOriginal", false)
                        .put("isKids", false)
                        .put("match", 0)
                        .put("year", 0)
                        .put("maturity", "NR")
                        .put("durationLabel", "")
                        .put("badges", JSONArray())
                        .put("genres", JSONArray())
                        .put("synopsis", cwItem.optString("tag"))
                        .put("cast", JSONArray())
                        .put("posterUrl", cwItem.optString("poster"))
                        .put("backdropUrl", cwItem.optString("poster"))
                        .put("url", cwItem.optString("url"))
                        .put("comingSoon", false)
                        .put("seasons", 0)
                        .put("episodes", JSONArray())
                }
                ids.put(keyId)
            }
            homeRows.put(
                JSONObject().put("label", "Continua a guardare").put("ids", ids)
            )
        }

        val homeSliders = addSlidersRows(home, catalog, null)
        for (i in 0 until homeSliders.length()) homeRows.put(homeSliders.optJSONObject(i))
        val tvRows = addSlidersRows(tv, catalog, "tv")
        val movieRows = addSlidersRows(movie, catalog, "movie")

        // Hero = props.title della home (titolo in evidenza con logo+preview).
        var heroTitleId = ""
        val heroRaw = home.props.optJSONObject("title")
        if (heroRaw != null) {
            val hero = SiteCatalog.parseTitle(heroRaw, home.props, "")?.let {
                // L'hero della home non ha score: niente badge "match".
                it.copy(score = null, match = null)
            }
            if (hero != null) {
                rememberTitle(hero)
                heroTitleId = "site-" + hero.id
                // L'hero è anche la prima card del catalogo: apribile dal modal.
                catalog.put("site-" + hero.id, itemJson(hero))
            }
        }
        if (heroTitleId.isEmpty() && catalog.isNotEmpty()) {
            heroTitleId = catalog.keys.first()
        }

        val catalogArr = JSONArray()
        for (item in catalog.values) catalogArr.put(item)

        val payload = JSONObject()
            .put("bridgeVersion", 1)
            .put("uiMode", uiMode)
            .put("domainHost", hostOf(domain))
            .put("myList", myListIds(profileId))
            .put("continueWatching", cw)
            .put("catalog", catalogArr)
            .put("heroTitleId", heroTitleId)
            .put(
                "rows",
                JSONObject()
                    .put("home", homeRows)
                    .put("tv", tvRows)
                    .put("movie", movieRows)
            )
        // La preferenza audio vive SOLO in localStorage lato JS (fix TV #15):
        // il nativo non ha uno store per essa e un valore fisso la resetterebbe
        // a ogni boot, quindi il campo settings non viene inviato.
        writeSnapshot(payload, profileId)
        return payload
    }

    /** Payload dell'evento catalog per un tab: home/tv/movie → righe della
     *  pagina corrispondente; "new" → top10 di serie+film (coming soon v1 è
     *  vuoto: l'archivio è client-only, nessun SSR — piano §3). */
    fun tabPayload(tab: String, domain: String): JSONObject? {
        return when (tab) {
            "home" -> {
                val page = getPage("/", domain, allowStale = true) ?: return null
                val catalog = LinkedHashMap<String, JSONObject>()
                val rows = addSlidersRows(page, catalog, null)
                JSONObject()
                    .put("tab", "home")
                    .put("catalog", JSONArray(catalog.values))
                    .put("rows", rows)
            }
            "tv" -> {
                val page = getPage("/it/tv-shows", domain, allowStale = true) ?: return null
                val catalog = LinkedHashMap<String, JSONObject>()
                val rows = addSlidersRows(page, catalog, "tv")
                JSONObject()
                    .put("tab", "tv")
                    .put("catalog", JSONArray(catalog.values))
                    .put("rows", rows)
            }
            "movie" -> {
                val page = getPage("/it/movies", domain, allowStale = true) ?: return null
                val catalog = LinkedHashMap<String, JSONObject>()
                val rows = addSlidersRows(page, catalog, "movie")
                JSONObject()
                    .put("tab", "movie")
                    .put("catalog", JSONArray(catalog.values))
                    .put("rows", rows)
            }
            "new" -> {
                val tv = getPage("/it/tv-shows", domain, allowStale = true)
                val movie = getPage("/it/movies", domain, allowStale = true)
                if (tv == null && movie == null) return null
                val catalog = LinkedHashMap<String, JSONObject>()
                addSlidersRows(tv, catalog, "tv")
                addSlidersRows(movie, catalog, "movie")
                // Solo i top10 ordinati per rank (fix TV #9 lato JS li ordina).
                val top10 = catalog.values
                    .filter { it.optInt("top10Rank", 0) > 0 }
                    .sortedBy { it.optInt("top10Rank", 99) }
                JSONObject()
                    .put("tab", "new")
                    .put("top10", JSONArray(top10))
            }
            else -> null
        }
    }

    // -- TitleDetailSource ---------------------------------------------------------

    /** Dettaglio completo (item CATALOG con episodi della stagione di default).
     *  Cache LRU max 20; miss → fetch diretto `/it/titles/<id>-<slug>` con lo
     *  slug risolto dai cache locali (vedi KDoc della classe). Il [profileId]
     *  serve SOLO alla risoluzione slug dai store locali (cache di rete
     *  condivisa tra i profili: il catalogo non è un dato per-profilo). */
    fun detailPayload(
        titleId: Int,
        domain: String,
        profileId: String = DEFAULT_PROFILE_ID
    ): JSONObject? {
        synchronized(detailCache) { detailCache[titleId] }?.let { return it.item }

        val slug = resolveSlug(titleId, profileId) ?: "t"
        val path = "/it/titles/$titleId-$slug"
        throttle()
        val env = SiteCatalog.fetchEnvelope(path, domain) ?: return null
        val props = env.optJSONObject("props") ?: return null
        val rawTitle = props.optJSONObject("title") ?: return null
        val cdn = props.optString("cdn_url")
        val t = SiteCatalog.parseTitle(rawTitle, props, domain) ?: return null
        rememberTitle(t)

        val item = itemJson(t)
        val loadedSeason = props.optJSONObject("loadedSeason")
        val defaultSeason = if (t.type == "tv" && loadedSeason != null) {
            val (seasonNo, eps) = episodesJson(loadedSeason, cdn)
            item.put("episodes", eps)
            item.put("currentSeason", seasonNo)
            seasonNo
        } else 0

        val entry = DetailEntry(
            item = item,
            slug = t.slug,
            version = env.optString("version"),
            cdnUrl = cdn,
            defaultSeason = defaultSeason
        )
        if (defaultSeason > 0) entry.seasons[defaultSeason] = item.optJSONArray("episodes") ?: JSONArray()
        synchronized(detailCache) {
            detailCache[titleId] = entry
            while (detailCache.size > DETAIL_LRU_MAX) {
                detailCache.remove(detailCache.keys.first())
            }
        }
        // Il poster servono a Cronologia/Riprendi/My List anche fuori da SCtv.
        t.posterUrl?.let { PosterCache.put(context, titleId, it) }
        return item
    }

    /** Episodi di una stagione via partial reload Inertia (~10KB).
     *  Risultato: (stagione, episodi) o null se la fetch fallisce del tutto.
     *  Su 409 (versione scaduta) si ri-fetcha la busta per aggiornare la
     *  version e si riprova UNA volta; se arriva HTML intero viene riparsato
     *  il data-page (gestito dentro [SiteCatalog.fetchSeasonPartial]). */
    fun seasonEpisodes(
        titleId: Int,
        season: Int,
        domain: String,
        profileId: String = DEFAULT_PROFILE_ID
    ): Pair<Int, JSONArray>? {
        val entry = synchronized(detailCache) { detailCache[titleId] }
        entry?.seasons?.get(season)?.let { return season to it }

        // Senza versione Inertia il partial risponde 409 a colpo sicuro:
        // prima si riporta il dettaglio in cache (aggiorna anche slug/version).
        val freshEntry = entry ?: detailPayload(titleId, domain, profileId)?.let {
            synchronized(detailCache) { detailCache[titleId] }
        } ?: return null

        val path = "/it/titles/$titleId-${freshEntry.slug}"
        var partial = SiteCatalog.fetchSeasonPartial(path, season, domain, freshEntry.version)
        if (partial.code == 409 || partial.loadedSeason == null) {
            // Versione scaduta: nuova busta → nuova version → retry una volta.
            val env = run {
                throttle()
                SiteCatalog.fetchEnvelope(path, domain)
            } ?: return null
            val newVersion = env.optString("version")
            val props = env.optJSONObject("props") ?: return null
            val rawTitle = props.optJSONObject("title") ?: return null
            val t = SiteCatalog.parseTitle(rawTitle, props, domain) ?: return null
            rememberTitle(t)
            val refreshed = DetailEntry(
                item = freshEntry.item,
                slug = t.slug,
                version = newVersion,
                cdnUrl = props.optString("cdn_url").ifBlank { freshEntry.cdnUrl },
                defaultSeason = freshEntry.defaultSeason
            )
            refreshed.seasons.putAll(freshEntry.seasons)
            synchronized(detailCache) { detailCache[titleId] = refreshed }
            partial = SiteCatalog.fetchSeasonPartial(
                "/it/titles/$titleId-${t.slug}", season, domain, newVersion
            )
        }
        val loaded = partial.loadedSeason ?: return null
        val (seasonNo, eps) = episodesJson(loaded, freshEntry.cdnUrl)
        freshEntry.seasons[seasonNo] = eps
        return seasonNo to eps
    }

    // -- LocalSources -----------------------------------------------------------------

    /** "Continua a guardare" dal ProgressStore (scritto da ENTRAMBE le
     *  modalità), SCOPATO al profilo [profileId]: cw-item del contratto §2,
     *  poster da ProgressStore o [PosterCache]. */
    fun continueWatchingJson(profileId: String = DEFAULT_PROFILE_ID): JSONArray {
        val cw = JSONArray()
        for (p in ProgressStore.get(context).resumeable(profileId)) cw.put(cwJson(p))
        return cw
    }

    private fun cwJson(p: WatchProgress): JSONObject {
        val poster = p.poster?.takeIf { it.isNotBlank() }
            ?: PosterCache.get(context, p.titleId)
        val mins = ((p.durationSec - p.positionSec + 59) / 60).coerceAtLeast(1)
        val tag = p.seasonEpisodeTag()
        val label = when {
            tag.isNotBlank() -> tag + (p.episodeName
                ?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: " · $mins min rimasti")
            else -> "$mins min rimasti"
        }
        val progress = if (p.durationSec > 0) {
            (p.positionSec.toDouble() / p.durationSec).coerceIn(0.0, 1.0)
        } else 0.0
        return JSONObject()
            .put("id", "site-" + p.titleId)
            .put("titleId", p.titleId)
            .put("name", p.name)
            .put("tag", tag)
            .put("type", p.type.ifBlank { "movie" })
            .put("progress", progress)
            .put("label", label)
            .put("poster", poster ?: "")
            .put("season", p.season ?: 1)
            .put("episode", p.episode ?: 1)
            .put("positionSec", p.positionSec)
            .put("durationSec", p.durationSec)
            .put("url", p.url)
    }

    /** Ids My List ("site-<id>") dai preferiti salvati (url → titleId),
     *  SCOPATI al profilo [profileId]. */
    fun myListIds(profileId: String = DEFAULT_PROFILE_ID): JSONArray {
        val ids = JSONArray()
        for (f in FavoritesStore.get(context).all(profileId)) {
            val id = SiteUrl.titleIdOf(f.url) ?: continue
            ids.put("site-$id")
        }
        return ids
    }

    /** Toggle My List ↔ FavoritesStore (bucket del profilo [profileId]).
     *  Ritorna {ids:[...]} aggiornato, o null se il titolo è ignoto e
     *  nemmeno la fetch di dettaglio risponde. */
    fun toggleMyList(
        titleId: Int,
        domain: String,
        profileId: String = DEFAULT_PROFILE_ID
    ): JSONObject? {
        val favorites = FavoritesStore.get(context)
        val existing = favorites.all(profileId).firstOrNull {
            SiteUrl.titleIdOf(it.url) == titleId
        }
        if (existing != null) {
            favorites.remove(existing.url, profileId)
        } else {
            val t = knownTitles[titleId] ?: detailPayload(titleId, domain, profileId)?.let {
                knownTitles[titleId]
            } ?: return null
            favorites.add(t.url, t.name, t.posterUrl, profileId)
        }
        return JSONObject().put("ids", myListIds(profileId))
    }

    /** Ricerca server: /it/search?q= → Titles/Browse titles[] (60, pertinenza). */
    fun searchPayload(query: String, domain: String): JSONObject {
        throttle()
        val titles = SiteCatalog.search(query, domain)
        val catalog = JSONArray()
        for (t in titles) {
            rememberTitle(t)
            catalog.put(itemJson(t))
        }
        return JSONObject()
            .put("query", query)
            .put("catalog", catalog)
    }

    // -- CategoriesSource (tab Categorie — dati reali dall'archivio) ----------------

    /** Titoli di una pagina Browse (props.titles): può essere [ ... ] oppure
     *  { data: [ ... ] } (paginato, Laravel ?page=N). */
    private fun browseTitles(props: JSONObject): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        when (val v = props.opt("titles")) {
            is org.json.JSONArray -> {
                for (i in 0 until v.length()) v.optJSONObject(i)?.let { out.add(it) }
            }
            is JSONObject -> v.optJSONArray("data")?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
            }
        }
        return out
    }

    /** Titoli Browse → item CATALOG (dedup per id, memorizzati in knownTitles
     *  per toggleMyList/risoluzione slug). Il merge lato JS fa l'upsert nel
     *  CATALOG come per search. */
    private fun browseTitlesToCatalog(props: JSONObject, domain: String): JSONArray {
        val catalog = JSONArray()
        val seen = HashSet<Int>()
        for (raw in browseTitles(props)) {
            val t = SiteCatalog.parseTitle(raw, props, domain) ?: continue
            if (!seen.add(t.id)) continue
            rememberTitle(t)
            catalog.put(itemJson(t))
        }
        return catalog
    }

    /** Tab Categorie (dati REALI — i generi non esistono nelle card della
     *  home, solo nel dettaglio): GET /it/archive → props.genres[] (26, campi
     *  id/name) + props.titles (le prime 60) per il genere "All".
     *
     *  L'archivio è una pagina Inertia (component Titles/Browse): la busta
     *  arriva nel data-page dell'HTML shell (verificato con curl 2026-11-21)
     *  e [SiteCatalog.fetchProps] la parsa come sulle altre pagine. La
     *  variante Accept: application/json della stessa route risponde invece
     *  col paginatore Laravel puro (senza generi) — non usata qui.
     *
     *  Risposta evento `categories`: {req, genres:[{id,name}], catalog:[item]}.
     *  Null = sito irraggiungibile (l'Activity pusha error). */
    fun categoriesPayload(domain: String): JSONObject? {
        throttle()
        val props = SiteCatalog.fetchProps("/it/archive?lang=it", domain) ?: return null
        val genres = JSONArray()
        val rawGenres = props.optJSONArray("genres")
        if (rawGenres != null) {
            for (i in 0 until rawGenres.length()) {
                val g = rawGenres.optJSONObject(i) ?: continue
                val id = g.optInt("id", 0)
                val name = g.optString("name").takeIf { it.isNotBlank() } ?: continue
                if (id <= 0 || g.optInt("hidden", 0) != 0) continue
                genres.put(JSONObject().put("id", id).put("name", name))
            }
        }
        return JSONObject()
            .put("genres", genres)
            .put("catalog", browseTitlesToCatalog(props, domain))
    }

    /** Filtro genere dell'archivio (pagina 1 basta per v1): GET
     *  /it/archive?lang=it&genre[]=<id>&page=1 → Titles/Browse → props.titles
     *  (60 titoli, paginazione Laravel ?page=N).
     *
     *  Risposta evento `genreData`: {req, genreId, catalog:[item]}. */
    fun genrePayload(genreId: Int, domain: String): JSONObject? {
        // genre[] pre-encodato (%5B%5D): lato Laravel è un parametro array.
        throttle()
        val props = SiteCatalog.fetchProps(
            "/it/archive?lang=it&genre%5B%5D=$genreId&page=1", domain
        ) ?: return null
        return JSONObject()
            .put("genreId", genreId)
            .put("catalog", browseTitlesToCatalog(props, domain))
    }

    // -- Helper interni -----------------------------------------------------------------

    private fun rememberTitle(t: TvTitle) {
        synchronized(knownTitles) {
            knownTitles.remove(t.id)
            knownTitles[t.id] = t
            while (knownTitles.size > KNOWN_TITLES_MAX) {
                knownTitles.remove(knownTitles.keys.first())
            }
        }
    }

    /** Risoluzione slug: LRU dettaglio → titoli noti → preferiti → progress
     *  (preferiti/progress del profilo [profileId]; la cache di rete è
     *  condivisa — il catalogo non è un dato per-profilo).
     *  Null = usa il placeholder (il sito risolve per id, vedi KDoc classe). */
    private fun resolveSlug(titleId: Int, profileId: String = DEFAULT_PROFILE_ID): String? {
        synchronized(detailCache) { detailCache[titleId] }?.slug?.takeIf { it.isNotBlank() }
            ?.let { return it }
        synchronized(knownTitles) { knownTitles[titleId] }?.slug?.takeIf { it.isNotBlank() }
            ?.let { return it }
        for (f in FavoritesStore.get(context).all(profileId)) {
            val m = SLUG_REGEX.find(f.url) ?: continue
            if (m.groupValues[1].toIntOrNull() == titleId) return m.groupValues[2]
        }
        ProgressStore.get(context).get(titleId, profileId)?.slug?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return null
    }

    /** Item CATALOG (shape esatta contratto piano §2) da un [TvTitle]. */
    private fun itemJson(t: TvTitle): JSONObject {
        val item = JSONObject()
            .put("id", "site-" + t.id)
            .put("titleId", t.id)
            .put("title", t.name)
            .put("type", if (t.type == "tv") "tv" else "movie")
            .put("isOriginal", false)
            .put("isKids", t.isKids)
            .put("match", t.match ?: 0)
            .put("year", t.year ?: 0)
            .put("maturity", t.maturity ?: "NR")
            .put("durationLabel", t.durationLabel ?: "")
            .put("badges", JSONArray(t.badges))
            .put("genres", JSONArray(t.genres))
            .put("synopsis", t.plot ?: "")
            .put("cast", JSONArray(t.cast))
            .put("posterUrl", t.posterUrl ?: "")
            .put("backdropUrl", t.backgroundUrl ?: t.posterUrl ?: "")
            .put("url", t.url)
            .put("comingSoon", t.comingSoon)
            .put("seasons", t.seasonsCount ?: 0)
            .put("episodes", JSONArray())
        t.top10Rank?.let { item.put("top10Rank", it) }
        t.creator?.let { item.put("creator", it) }
        t.releaseDate?.let { item.put("releaseDate", it) }
        return item
    }

    /** Episodi di un props.loadedSeason → (numero stagione, JSONArray episodes
     *  con shape `{ep, id, season, title, duration, desc, streamUrl, thumb}`).
     *  streamUrl resta vuoto: la risoluzione stream è del player nativo (M3). */
    private fun episodesJson(loadedSeason: JSONObject, cdnUrl: String): Pair<Int, JSONArray> {
        val seasonNo = loadedSeason.optInt("number", 1).coerceAtLeast(1)
        val eps = JSONArray()
        val arr = loadedSeason.optJSONArray("episodes") ?: return seasonNo to eps
        val base = cdnUrl.trimEnd('/')
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val dur = e.optInt("duration", 0)
            val thumb = e.optJSONArray("images")?.let { images ->
                var filename: String? = null
                for (j in 0 until images.length()) {
                    val im = images.optJSONObject(j) ?: continue
                    if (im.optString("type") == "cover") {
                        filename = im.optString("filename")
                        break
                    }
                }
                filename?.takeIf { it.isNotBlank() }?.let { "$base/images/$it" }
            }
            eps.put(
                JSONObject()
                    .put("ep", e.optInt("number", i + 1))
                    .put("id", e.optInt("id", 0))
                    .put("season", seasonNo)
                    .put("title", e.optString("name"))
                    .put("duration", if (dur > 0) "${dur}min" else "")
                    .put("desc", e.optString("plot").takeIf { it.isNotBlank() && it != "null" } ?: "")
                    .put("streamUrl", "")
                    .put("thumb", thumb ?: "")
            )
        }
        return seasonNo to eps
    }

    private fun hostOf(domain: String): String =
        runCatching { java.net.URI(domain).host ?: "" }.getOrDefault("")

    // -- Snapshot offline -----------------------------------------------------------------

    /** Snapshot PER PROFILO: il profilo "default" conserva il file storico
     *  (gli utenti esistenti ritrovano il proprio fallback offline), gli
     *  altri profili hanno un file dedicato. */
    private fun snapshotFile(profileId: String): File {
        val safe = sanitizeProfileId(profileId)
        val name = if (safe == DEFAULT_PROFILE_ID) SNAPSHOT_FILE
        else SNAPSHOT_FILE.removeSuffix(".json") + "_$safe.json"
        return File(context.filesDir, name)
    }

    /** Ultimo boot riuscito, su disco: alla prima apertura offline l'UI resta
     *  utilizzabile (evento boot + toast errore) invece della sola error-card. */
    private fun writeSnapshot(payload: JSONObject, profileId: String = DEFAULT_PROFILE_ID) {
        runCatching {
            snapshotFile(profileId).writeText(payload.toString())
        }.onFailure { Log.w(TAG, "snapshot write failed", it) }
    }

    /** Snapshot dell'ultimo boot riuscito del PROFILO [profileId]
     *  (aggiornando dominio/modalità al valore corrente), o null se mai
     *  scritto. */
    fun readSnapshot(domain: String, uiMode: String, profileId: String = DEFAULT_PROFILE_ID): JSONObject? {
        return runCatching {
            val file = snapshotFile(profileId)
            if (!file.exists()) return@runCatching null
            val snap = JSONObject(file.readText())
            snap.put("domainHost", hostOf(domain))
            snap.put("uiMode", uiMode)
            snap
        }.onFailure { Log.w(TAG, "snapshot read failed", it) }.getOrNull()
    }
}
