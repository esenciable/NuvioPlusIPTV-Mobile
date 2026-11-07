package com.nuvio.app.features.livetv.sports

import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Our own sports addon, read the way the TV app reads it: `/events.json` for the matches and
 * `/stream/tv/<id>.json` for their links.
 *
 * It used to be discovered through the addon's published catalogs, which is why the phone lost the
 * links the moment the addon stopped publishing them (the catalog only existed to put rows on Home
 * and results in search). A catalog is an advertising surface; the feed and the stream endpoint are
 * the contract.
 *
 * The feed is cached for a minute: the Sports page asks per game, and 40 games must not mean 40
 * fetches of a 650-event document. A FAILED fetch keeps the last good list and does not arm the
 * window, so the next attempt retries immediately.
 */
internal object SportsEsencialAddon {

    const val BASE_URL = "https://esencialsport.attendanceos.app"
    const val SOURCE_ID = "esencial-sport"
    const val SOURCE_NAME = "Esencial Sport"

    /** The same weight the addon-catalog path gives its own streams. */
    private const val SCORE = 100
    private const val TTL_MS = 60_000L

    /** How far apart the app's kickoff and the feed's may be before it is another game. */
    private const val KICKOFF_TOLERANCE_MS = 6 * 60 * 60 * 1000L

    data class Event(
        val id: String,
        val title: String,
        val teams: List<String>,
        val kickoffMs: Long,
        val live: Boolean,
    )

    data class Stream(val label: String, val url: String)

    private var cached: List<Event>? = null
    private var cachedAtMs = 0L
    private val lock = Mutex()

    /** The feed, cached for a minute. Empty only when the addon has never answered. */
    suspend fun events(): List<Event> {
        val fresh = cached?.takeIf { LiveTvClock.nowMs() - cachedAtMs < TTL_MS }
        if (fresh != null) return fresh
        return lock.withLock {
            val again = cached?.takeIf { LiveTvClock.nowMs() - cachedAtMs < TTL_MS }
            if (again != null) return@withLock again
            val parsed = runCatching { parseEvents(httpGetText("$BASE_URL/events.json")) }.getOrNull()
            if (parsed.isNullOrEmpty()) return@withLock cached.orEmpty()
            cached = parsed
            cachedAtMs = LiveTvClock.nowMs()
            parsed
        }
    }

    /** The links of one game: a label and the addon's proxied URL, which needs no headers. */
    suspend fun streams(eventId: String): List<Stream> {
        if (eventId.isBlank()) return emptyList()
        val raw = runCatching { httpGetText("$BASE_URL/stream/tv/$eventId.json") }.getOrNull() ?: return emptyList()
        return parseStreams(raw)
    }

    /** The response, parsed. Kept separate from the fetch so the shape can be tested. */
    internal fun parseStreams(raw: String): List<Stream> {
        val doc = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyList()
        val list = doc["streams"] as? JsonArray ?: return emptyList()
        return list.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val url = obj.str("url") ?: return@mapNotNull null
            if (url.isBlank()) return@mapNotNull null
            // The addon labels EVERY entry with the same `name` ("Esencial Sport") and puts the
            // provider it resolved in `resolution` (Fan LAT, TyR, Sport PT…), with the same text as
            // the tail of `title`. Using `name` collapsed three different links into one row.
            val provider = obj.str("resolution")
                ?: obj.str("title")?.substringAfter(" · ", "")?.takeIf { it.isNotBlank() }
            val label = if (provider.isNullOrBlank()) SOURCE_NAME else "$SOURCE_NAME · $provider"
            Stream(label = label, url = url)
        }
    }

    /**
     * The feed entry for one of the app's games: by id when the ids agree (`rb_…`), otherwise by
     * BOTH team names plus a kickoff inside the tolerance, so a duplicated fixture in two sources
     * cannot pick the wrong match.
     */
    fun match(game: SportsEvent, events: List<Event>): Event? {
        events.firstOrNull { it.id == game.id }?.let { return it }
        val ours = listOfNotNull(game.home?.team, game.away?.team)
            .map { normalize(it.name) + "|" + normalize(it.fullName) + "|" + normalize(it.abbr) }
        if (ours.size < 2) return null
        return events.firstOrNull { candidate ->
            val haystack = normalize(candidate.title + " " + candidate.teams.joinToString(" "))
            val namesHit = ours.count { variants -> variants.split("|").any { it.isNotBlank() && haystack.contains(it) } } >= 2
            val timeHit = candidate.kickoffMs <= 0L ||
                kotlin.math.abs(candidate.kickoffMs - game.startMs) <= KICKOFF_TOLERANCE_MS
            namesHit && timeHit
        }
    }

    /** The links of [game] as channels the Sports page can list. */
    suspend fun matches(game: SportsEvent): List<ChannelMatch> {
        val event = match(game, events()) ?: return emptyList()
        return rowsFor(event.id, streams(event.id))
    }

    /** One row per link, with the provider it resolved on, so three links are three rows. */
    internal fun rowsFor(eventId: String, streams: List<Stream>): List<ChannelMatch> =
        streams.mapIndexed { index, stream ->
            ChannelMatch(
                channel = LiveChannel(
                    key = "$SOURCE_ID:$eventId:$index",
                    sourceId = SOURCE_ID,
                    sourceName = SOURCE_NAME,
                    name = stream.label,
                    tvgId = null,
                    tvgName = null,
                    logo = null,
                    groupId = SOURCE_ID,
                    group = "Deportes",
                    number = index + 1,
                    url = stream.url,
                    headers = emptyMap(),
                    catchup = null,
                    drm = null,
                ),
                score = SCORE,
                how = SOURCE_NAME,
                program = null,
                quality = SportsMatcher.nameQuality(stream.label),
                catchup = false,
            )
        }

    private fun parseEvents(raw: String): List<Event> {
        val doc = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyList()
        val array = (doc["events"] ?: doc["matches"]) as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = obj.str("id") ?: return@mapNotNull null
            Event(
                id = id,
                title = obj.str("title").orEmpty(),
                teams = (obj["teams"] as? JsonArray)?.mapNotNull { team ->
                    (team as? JsonObject)?.str("name") ?: (team as? JsonPrimitive)?.contentOrNull
                }.orEmpty(),
                kickoffMs = obj.str("kickoffMs")?.toLongOrNull() ?: 0L,
                live = obj.str("live")?.toBooleanStrictOrNull() ?: false,
            )
        }
    }

    private fun JsonObject.str(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    /** Case and accent insensitive, punctuation dropped: "ÉB Pau-Orthez" == "eb pau orthez". */
    private fun normalize(value: String): String = buildString(value.length) {
        for (char in value.lowercase()) {
            when {
                char.isLetterOrDigit() -> append(char)
                char.isWhitespace() || char in "-_.'" -> append(' ')
            }
        }
    }.replace(Regex("\\s+"), " ").trim()
}
