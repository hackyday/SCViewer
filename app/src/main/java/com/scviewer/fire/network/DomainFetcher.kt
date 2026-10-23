package com.scviewer.fire.network

import com.scviewer.fire.data.DomainsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Pulls domains_s.json from the AstraeLabs/Domains repo (VibraVid behaviour).
 *
 * The fetch is wrapped in a tight timeout (8 s) so the WebView never has
 * to wait too long for the initial load — if GitHub is unreachable the
 * caller falls back to the cached domains.json.
 *
 * Mirrors VibraVid/utils/config.py: only the `streamingcommunity` key is
 * actually consumed here, but the full payload is preserved so we can show
 * the updatedAt timestamp in the UI.
 */
object DomainFetcher {

    private const val GITHUB_RAW_URL =
        "https://raw.githubusercontent.com/AstraeLabs/Domains/main/domains_s.json"

    /** Alternative mirror in case main branch is renamed. */
    private const val GITHUB_RAW_URL_MASTER =
        "https://raw.githubusercontent.com/AstraeLabs/Domains/master/domains_s.json"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * Returns the freshly-fetched config, or null on any failure
     * (network / parse / both URLs 404'd). The caller is responsible for
     * falling back to the cached copy.
     */
    suspend fun fetchOnline(): DomainsConfig? = withContext(Dispatchers.IO) {
        // Try main first, then master, then give up.
        for (url in listOf(GITHUB_RAW_URL, GITHUB_RAW_URL_MASTER)) {
            val result = tryFetch(url)
            if (result != null) return@withContext result.copy(updatedAt = System.currentTimeMillis())
        }
        null
    }

    private fun tryFetch(url: String): DomainsConfig? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "SCViewer/1.0 (FireTV; domain-sync)")
            .header("Cache-Control", "no-cache")
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) return@runCatching null
            extractStreamingcommunity(body)
        }
    }.getOrNull()

    /**
     * The upstream list has shipped two shapes over time:
     *   "streamingcommunity": "https://…"                        (legacy string)
     *   "streamingcommunity": { "full_url": "https://…" }        (current object)
     * Decode the JSON tree manually so both keep working — decoding straight
     * into [DomainsConfig] with the object shape throws and silently kills
     * every refresh.
     */
    private fun extractStreamingcommunity(body: String): DomainsConfig? {
        val sc = json.parseToJsonElement(body).jsonObject["streamingcommunity"] ?: return null
        val url = when (sc) {
            is JsonPrimitive -> sc.content
            is JsonObject -> (sc["full_url"] as? JsonPrimitive)?.content
            else -> null
        }?.takeIf { it.startsWith("http") } ?: return null
        return DomainsConfig(streamingcommunity = url)
    }
}
