package com.nuvio.app.features.livetv.native.magis

import com.nuvio.app.features.tmdb.TmdbConfig
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The Magis VOD flow needs a TITLE to search the portal by: the portal's search is by name, not by
 * id. This turns the id the app already has into the Spanish title pair the portal's catalogue
 * uses, with two small TMDB calls that live HERE instead of going through the app's TMDB service —
 * the VOD flow needs exactly two requests and every failure has to be visible in this client's log.
 *
 * LANGUAGE is `es-MX`, the plugin's own choice: asking TMDB in English made the lookup search
 * "Dune" while the portal lists "Duna", and the only candidate sharing the word was a mockbuster.
 * The Spanish answer still carries `original_title`, so the original-title fallback keeps working.
 *
 * The API key comes from the user's TMDB setting, falling back to the one baked at build time
 * (`TmdbConfig.API_KEY`), which is how the rest of the app resolves it.
 */
internal class MobileMagisTitleLookup : MagisTitleLookup {

    override suspend fun lookup(id: String, isSeries: Boolean): MagisTitleInfo? {
        val mediaType = if (isSeries) "tv" else "movie"
        // The stream pipeline hands over `tt…:season:episode` for series; only the bare id resolves.
        val bareId = id.substringBefore(':').substringBefore('/').trim().removePrefix("tmdb:")
        if (bareId.isEmpty()) return null

        // The BUILD-time key wins here, unlike the rest of the app: it is the deployment's own key
        // and it is known to work, while a stale value saved in the user's TMDB setting answers
        // "Invalid API key" and would silently leave the VOD tab empty.
        val apiKey = listOf(
            runCatching { TmdbConfig.API_KEY }.getOrNull().orEmpty(),
            runCatching { TmdbSettingsRepository.effectiveApiKey() }.getOrNull().orEmpty(),
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        if (apiKey.isBlank()) {
            magisLog("vod: no TMDB api key configured")
            return null
        }

        val numericId = bareId.toIntOrNull() ?: imdbToTmdb(bareId, mediaType, apiKey) ?: run {
            magisLog("vod: no TMDB id for $bareId")
            return null
        }

        val path = if (isSeries) "tv/$numericId" else "movie/$numericId"
        val raw = magisHttpGet("https://api.themoviedb.org/3/$path?api_key=$apiKey&language=$TMDB_LANGUAGE")
            ?: return null
        val doc = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: run {
            magisLog("vod: TMDB detail unparsable for $numericId")
            return null
        }

        val title = text(doc, "title").ifBlank { text(doc, "name") }
        val original = text(doc, "original_title").ifBlank { text(doc, "original_name") }
        if (title.isBlank() && original.isBlank()) {
            magisLog("vod: TMDB has no title for $numericId")
            return null
        }
        val year = text(doc, "release_date").ifBlank { text(doc, "first_air_date") }.take(4).toIntOrNull()
        magisLog("vod: $bareId -> \"${title.ifBlank { original }}\" ($year)")
        return MagisTitleInfo(
            title = title.ifBlank { original },
            originalTitle = original.ifBlank { title },
            year = year,
        )
    }

    /** `tt…` -> the numeric TMDB id, through TMDB's own external-id lookup. */
    private suspend fun imdbToTmdb(imdbId: String, mediaType: String, apiKey: String): Int? {
        if (!imdbId.startsWith("tt", ignoreCase = true)) return null
        val raw = magisHttpGet(
            "https://api.themoviedb.org/3/find/$imdbId?external_source=imdb_id&api_key=$apiKey",
        ) ?: return null
        val doc = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        magisLog(
            "vod: find($imdbId) keys=${doc.keys} status=${text(doc, "status_message")} " +
                "tvResults=${(doc["tv_results"] as? JsonArray)?.size} movieResults=${(doc["movie_results"] as? JsonArray)?.size}",
        )
        fun first(name: String): Int? = (doc[name] as? JsonArray)
            ?.firstOrNull()
            ?.let { it as? JsonObject }
            ?.get("id")
            ?.let { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }
        return when (mediaType) {
            "tv" -> first("tv_results") ?: first("movie_results")
            else -> first("movie_results") ?: first("tv_results")
        }
    }

    private fun text(doc: JsonObject, name: String): String =
        (doc[name] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private companion object {
        /** The plugin's TMDB language (es-MX): the Magis portal is a Spanish catalogue. */
        const val TMDB_LANGUAGE = "es-MX"
    }
}
