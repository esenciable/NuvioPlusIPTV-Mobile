package com.nuvio.app.features.servers

import com.nuvio.app.features.livetv.native.magis.MagisBootstrap
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamProxyHeaders

/**
 * The Magis VOD client as one more entry of the app's own native source family.
 *
 * `ServerStreams` already publishes one `AddonStreamGroup` per entry of its list, so Magis rides
 * the exact presentation path the server connections use — order, filters, badges — without
 * entering the addon registry or the plugin scraper registry.
 *
 * A build without `MAGIS_*` values answers [isConfigured] = false and contributes no group at all:
 * the same "source absent, never a crash" rule the live side keeps.
 */
internal object MagisServerSource {

    const val GROUP_ID = "magis:vod"
    const val GROUP_NAME = "Magis VOD"

    val isConfigured: Boolean get() = MagisBootstrap.stack() != null

    /**
     * Only catalogue ids can resolve in Magis: numeric TMDB ids, `tmdb:` prefixed ones and IMDb
     * `tt…` ids (series arrive as `tt…:season:episode`). Everything else — the sports feed's
     * `rb_…` events, native live keys — is not a Magis title, and asking the portal for it only
     * costs a call and a log line.
     */
    private fun isVodId(videoId: String): Boolean {
        val bare = videoId.substringBefore(':').substringBefore('/').removePrefix("tmdb:")
        return bare.all(Char::isDigit) || bare.startsWith("tt", ignoreCase = true)
    }

    suspend fun streams(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
    ): List<StreamItem> {
        if (!isVodId(videoId)) return emptyList()
        val stack = MagisBootstrap.stack() ?: return emptyList()
        val resolved = stack.resolveVod(type, videoId, season, episode) ?: return emptyList()
        // Shape only: the URL carries a signed token and the headers carry credentials, so neither
        // value is logged — but knowing the extension and the header NAMES is what tells a stalled
        // player apart from a rejected request.
        com.nuvio.app.features.livetv.native.magis.magisLog(
            "vod: resolved host=${resolved.url.substringAfter("://").substringBefore('/')} " +
                "path=${resolved.url.substringBefore('?').takeLast(24)} " +
                "headers=${resolved.headers.keys.sorted()}",
        )
        return listOf(
            StreamItem(
                name = GROUP_NAME,
                title = GROUP_NAME,
                description = "Magis",
                url = resolved.url,
                addonName = GROUP_NAME,
                addonId = GROUP_ID,
                // The CDN signs per request: the headers travel with the stream so the player
                // sends them on the playlist AND on every segment.
                behaviorHints = StreamBehaviorHints(
                    proxyHeaders = StreamProxyHeaders(request = resolved.headers),
                ),
            ),
        )
    }
}
