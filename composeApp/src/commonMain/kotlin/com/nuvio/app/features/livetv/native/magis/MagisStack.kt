package com.nuvio.app.features.livetv.native.magis

import com.nuvio.app.features.livetv.model.LiveChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The Magis portal stack for this app: config -> crypto -> portal -> session -> live client, built
 * ONCE per process from the values [MagisConfig] receives at build time.
 *
 * A build without `MAGIS_*` values behaves as if the source does not exist ([stack] returns null):
 * no channels, no calls, no errors — the same "source absent, never a crash" rule the TV client
 * follows.
 *
 * The session is minted lazily on the first call ([ensureReady]): the portal hands out ONE device
 * per fingerprint and two identities over the same device kick each other out, so the mint result
 * is kept for the process lifetime. Persisting it across launches is the next step — until then a
 * new launch mints a new device.
 */
internal class MagisStack(
    private val session: MagisSession,
    private val live: MagisLiveClient,
    private val vod: MagisVodClient,
) {

    private var ready = false

    /** A page of channels per category, walked until a short page arrives. */
    suspend fun loadChannels(): List<LiveChannel> {
        if (!ensureReady()) { magisLog("loadChannels: session not ready"); return emptyList() }
        val categories = runCatching { live.categories() }
            .onFailure { magisLog("loadChannels: categories threw ${it::class.simpleName}: ${it.message}") }
            .getOrNull().orEmpty()
        magisLog("loadChannels: ${categories.size} categories")
        if (categories.isEmpty()) return emptyList()

        // Categories are independent reads, so they run concurrently — but BOUNDED, and the portal's
        // own 150 ms pace is still the real limiter: unbounded fan-out would multiply the traffic
        // that got the TV client rate-limited.
        val concurrency = Semaphore(4)
        val batches: List<List<MagisLiveChannel>> = coroutineScope {
            categories.map { category ->
                async {
                    concurrency.withPermit {
                        try {
                            loadCategory(category)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            magisLog("category ${category.id} failed ${e::class.simpleName}: ${e.message}")
                            emptyList()
                        }
                    }
                }
            }.awaitAll()
        }

        val out = LinkedHashMap<String, LiveChannel>()
        categories.forEachIndexed { index, category ->
            for (channel in batches[index]) {
                if (channel.code.isBlank()) continue
                out.putIfAbsent(channel.code, MagisSource.channelFrom(channel, category.id, category.name))
            }
        }
        // Keys are `magis:<code>`; the map is keyed by code until here so a channel appearing in
        // two categories keeps the first group, like the TV client.
        magisLog("loadChannels: ${out.size} channels")
        return out.values.toList()
    }

    /**
     * Marker -> playable channel: the playlist URL plus the signed `Content-Auth`/`Content-License`
     * headers. Null when the portal could not resolve it — the caller must surface that as a
     * failure, never as a guess at a URL.
     */
    suspend fun resolve(channel: LiveChannel): LiveChannel? {
        if (!ensureReady()) return null
        val code = MagisSource.codeOf(channel.key) ?: return null
        val result = runCatching { live.resolveDetailed(code) }.getOrNull() as? MagisResult.Ok ?: return null
        val cdn = result.data.cdns.firstOrNull() ?: return null
        val aimed = result.data.copy(host = cdn.host, authBase = cdn.authBase)
        return channel.copy(url = live.playlistUrl(aimed), headers = live.signedHeaders(aimed))
    }

    /** One category, every page: full pages keep coming until one arrives short. */
    private suspend fun loadCategory(category: MagisLiveCategory): List<MagisLiveChannel> {
        val loaded = mutableListOf<MagisLiveChannel>()
        for (page in 1..MAX_PAGES_PER_CATEGORY) {
            val fetched = live.channels(category.id, page)
            loaded += fetched
            if (fetched.size < MagisLiveClient.PAGE_SIZE) break
        }
        return loaded
    }

    /**
     * Magis VOD for one title: the direct CDN URL plus the headers it demands. Null when the
     * portal has nothing for this id or the session is dead — the stream screen then shows the
     * other sources instead of an error.
     */
    suspend fun resolveVod(type: String, videoId: String, season: Int?, episode: Int?): MagisVodStream? {
        if (!ensureReady()) return null
        val result = runCatching {
            vod.resolveDetailed(
                id = videoId,
                mediaType = type,
                season = season ?: 0,
                episode = episode ?: 0,
            )
        }.getOrNull()
        return when (result) {
            is MagisResult.Ok -> result.data
            is MagisResult.PortalError -> {
                magisLog("vod: portal ${result.code}${result.msg?.let { ": $it" } ?: ""} [id=$videoId]")
                null
            }
            is MagisResult.RedError -> {
                magisLog("vod: red ${result.cause.message} [id=$videoId]")
                null
            }
            null -> null
        }
    }

    private suspend fun ensureReady(): Boolean {
        if (ready) return true
        val result = runCatching { session.ensureAnonymous() }
            .onFailure { magisLog("ensureAnonymous threw ${it::class.simpleName}: ${it.message}") }
            .getOrNull()
        ready = result is MagisResult.Ok
        magisLog("ensureAnonymous -> ${result?.let { it::class.simpleName } ?: "null"}" +
            ((result as? MagisResult.PortalError)?.let { " code=${it.code}" } ?: ""))
        return ready
    }

    private companion object {
        /** Hard cap so a category that always answers a full page eventually stops. */
        const val MAX_PAGES_PER_CATEGORY = 5
    }
}

/** Process-wide bootstrap: one stack, built from the build-time config, or null when disabled. */
internal object MagisBootstrap {

    private var built: MagisStack? = null
    private var attempted = false

    /** Session/config persistence, installed by the platform layer (see [MagisStores]). */
    val stores = MagisStores()

    fun stack(): MagisStack? {
        if (attempted) return built
        attempted = true
        built = runCatching { build() }
            .onFailure { magisLog("bootstrap threw ${it::class.simpleName}: ${it.message}") }
            .getOrNull()
        if (built == null) {
            magisLog(
                "bootstrap disabled: enabled=${MagisConfig.enabled} hosts=${MagisConfig.HOSTS.length} " +
                    "appId=${MagisConfig.APP_ID.length} key=${MagisConfig.THREE_DES_KEY.length}",
            )
        }
        return built
    }

    private fun build(): MagisStack? {
        val config = runtimeConfig()
        magisLog("bootstrap: config=${config != null} hosts=${config?.hosts?.size ?: 0}")
        if (config == null) return null
        val port = createMagisCryptoPort()
        val provider = MagisConfigProvider(config)
        val holder = MagisCryptoHolder(port) { provider.current.threeDesKeyHex }
        val sessionStore = stores.sessionStore()
        val portal = MagisPortalHttp(
            configProvider = { provider.current },
            cryptoProvider = { holder.get() },
            snProvider = { sessionStore.read()?.sn.orEmpty() },
        )
        val session = MagisSession(portal, sessionStore, MagisDevice(port))
        return MagisStack(
            session = session,
            live = MagisLiveClient(portal, session, configProvider = { provider.current }),
            // The portal searches its catalogue BY NAME, so the app's own id is turned into the
            // Spanish title pair first (see MobileMagisTitleLookup).
            vod = MagisVodClient(
                portal = portal,
                session = session,
                configProvider = { provider.current },
                titleLookup = MobileMagisTitleLookup(),
            ),
        )
    }

    /** The build-time values as a runtime config, or null when the build carries none. */
    private fun runtimeConfig(): MagisRuntimeConfig? {
        if (!MagisConfig.enabled) return null
        val hosts = MagisConfig.HOSTS.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (hosts.isEmpty()) return null
        return MagisRuntimeConfig(
            hosts = hosts,
            appId = MagisConfig.APP_ID,
            apkVersion = MagisConfig.APK_VERSION,
            apkVerHeader = MagisConfig.APK_VER_HEADER,
            spkgVer = MagisConfig.SPKG_VER,
            threeDesKeyHex = MagisConfig.THREE_DES_KEY,
        )
    }
}
