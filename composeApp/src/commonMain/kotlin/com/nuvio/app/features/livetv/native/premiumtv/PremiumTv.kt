package com.nuvio.app.features.livetv.native.premiumtv

import com.nuvio.app.features.livetv.model.LiveChannel
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlin.time.TimeSource

/**
 * PremiumTV: the provider's IPTV list as a source that needs NO setup.
 *
 * There is no protocol here — no portal, no session, no crypto, no rate limit. The app reads the
 * playlist we regenerate ourselves (`nuvio-iptv`), which is the same provider list with two things
 * added: real `tvg-id`s for the channels that have them and a `url-tvg` header pointing at our
 * guide. Both files are static on GitHub Pages.
 *
 * This mirrors, on purpose, the design the fork already validated once (nuvio-es, PremiumTV as the
 * second native live source): a stable channel id derived from the stream URL — not from the list
 * position, so favourites survive a re-fetch — and per-entry `#EXTVLCOPT` headers read from the
 * list itself instead of a blanket default.
 */
internal object PremiumTvSource {

    const val SOURCE_ID = "premiumtv"
    const val SOURCE_NAME = "PremiumTV"

    /** Our regenerated copy of the provider's list (tvg-ids + `url-tvg`), refreshed every 12 h. */
    const val FEED_URL = "https://esenciable.github.io/nuvio-iptv/esencial.m3u"

    /** The default when an entry declares no user agent of its own (the list's own common case). */
    private const val CHROME_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"

    fun keyOf(url: String): String = "$SOURCE_ID:${stableId(url)}"

    fun channelFrom(entry: PremiumTvEntry, index: Int): LiveChannel {
        val group = entry.group.ifBlank { "Otros" }
        return LiveChannel(
            key = keyOf(entry.url),
            sourceId = SOURCE_ID,
            sourceName = SOURCE_NAME,
            name = entry.name,
            tvgId = entry.tvgId?.takeIf { it.isNotBlank() },
            tvgName = null,
            logo = entry.logo?.takeIf { it.isNotBlank() },
            groupId = "$SOURCE_ID::$group",
            group = group,
            number = index + 1,
            url = entry.url,
            headers = entry.headers,
            catchup = null,
            drm = null,
        )
    }

    /**
     * The per-entry headers, with the precedence the fork measured:
     *  - `User-Agent`: the entry's own `#EXTVLCOPT:http-user-agent`, else Chrome.
     *  - `Referer`: the entry's own `#EXTVLCOPT:http-referrer`; if it declares none, ONLY the
     *    Samsung TV Plus hosts get `https://www.samsung.com/`. Sending that referrer to every
     *    other channel is the bug the plugin had: it breaks channels that validate their own.
     */
    fun headersFor(url: String, userAgent: String?, referrer: String?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out["User-Agent"] = userAgent?.takeIf { it.isNotBlank() } ?: CHROME_UA
        val declared = referrer?.takeIf { it.isNotBlank() }
        val host = url.substringAfter("://").substringBefore('/').lowercase()
        val samsung = host.contains("jmp2.uk") || host.contains("samsung")
        if (declared != null) {
            out["Referer"] = declared
        } else if (samsung) {
            out["Referer"] = "https://www.samsung.com/"
        }
        return out
    }

    /**
     * A short, stable id derived from the URL: the list is re-fetched and reordered constantly, so
     * an index would rename every channel. FNV-1a 64 is used instead of SHA-256 because this runs
     * in `commonMain` (no `java.security`), and for ~2.000 URLs the collision odds are negligible.
     */
    private fun stableId(url: String): String {
        var hash = -0x340d631b7bdddcdbL
        for (char in url) {
            hash = hash xor char.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash.toULong().toString(16).padStart(16, '0')
    }
}

/** One playlist entry: what the `#EXTINF` line declares plus the URL that follows it. */
internal data class PremiumTvEntry(
    val name: String,
    val group: String,
    val logo: String?,
    val tvgId: String?,
    val url: String,
    val headers: Map<String, String>,
)

/**
 * Reads the feed and keeps the last good answer. A FAILED fetch never arms the TTL, so a transient
 * failure retries on the next call instead of freezing an empty list for half an hour.
 */
internal class PremiumTvFeed(
    private val url: String = PremiumTvSource.FEED_URL,
    private val ttlMs: Long = 30 * 60 * 1000L,
) {
    private var lastGood: List<PremiumTvEntry>? = null
    private var lastGoodAt: TimeSource.Monotonic.ValueTimeMark? = null

    suspend fun load(): List<PremiumTvEntry> {
        val cached = lastGood
        val at = lastGoodAt
        if (cached != null && at != null && at.elapsedNow().inWholeMilliseconds < ttlMs) return cached

        val text = runCatching {
            com.nuvio.app.core.network.createApiHttpClient().get(url).bodyAsText()
        }.getOrNull()
        val parsed = text?.let { parsePremiumTvM3u(it) }.orEmpty()
        if (parsed.isEmpty()) return cached.orEmpty()

        lastGood = parsed
        lastGoodAt = TimeSource.Monotonic.markNow()
        return parsed
    }
}

/**
 * Parses the playlist. Every line the app does not need to understand is dropped, but the
 * `#EXTVLCOPT` headers that decide whether a channel answers are KEPT: they are the difference
 * between a stream that plays and a 403.
 */
internal fun parsePremiumTvM3u(text: String): List<PremiumTvEntry> {
    val out = ArrayList<PremiumTvEntry>()
    var pending: String? = null
    var userAgent: String? = null
    var referrer: String? = null

    for (raw in text.lineSequence()) {
        val line = raw.trim()
        when {
            line.isEmpty() -> Unit
            line.startsWith("#EXTINF") -> {
                pending = line
                userAgent = null
                referrer = null
            }
            line.startsWith("#EXTVLCOPT:http-user-agent=", ignoreCase = true) ->
                userAgent = line.substringAfter('=', "").trim()
            line.startsWith("#EXTVLCOPT:http-referrer=", ignoreCase = true) ->
                referrer = line.substringAfter('=', "").trim()
            line.startsWith("#") -> Unit
            else -> {
                val extinf = pending
                pending = null
                if (extinf == null) continue
                val name = extinf.nameOfEntry()
                val url = line
                out += PremiumTvEntry(
                    name = name.ifBlank { url.substringAfterLast('/') },
                    group = extinf.attribute("group-title").orEmpty(),
                    logo = extinf.attribute("tvg-logo"),
                    tvgId = extinf.attribute("tvg-id"),
                    url = url,
                    headers = PremiumTvSource.headersFor(url, userAgent, referrer),
                )
            }
        }
    }
    return out
}

/** `key="value"` inside an `#EXTINF` line, or null when absent. */
private fun String.attribute(key: String): String? =
    Regex("(?:^|\\s)${Regex.escape(key)}=\"([^\"]*)\"").find(this)?.groupValues?.get(1)

/** The display name: everything after the first comma that is not inside quotes. */
private fun String.nameOfEntry(): String {
    var inQuotes = false
    for (index in indices) {
        val char = this[index]
        when {
            char == '"' -> inQuotes = !inQuotes
            char == ',' && !inQuotes -> return substring(index + 1).trim()
        }
    }
    return ""
}

/** One feed per process: it holds the last-good list and its 30 minute window. */
internal val premiumTvFeed = PremiumTvFeed()
