package com.nuvio.app.features.livetv.native.magis

import com.nuvio.app.features.livetv.model.LiveChannel

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

        val out = LinkedHashMap<String, LiveChannel>()
        for (category in categories) {
            var page = 1
            while (page <= MAX_PAGES_PER_CATEGORY) {
                val fetched = runCatching { live.channels(category.id, page) }
                    .onFailure { magisLog("category ${category.id} page $page threw ${it.message}") }
                    .getOrNull().orEmpty()
                for (channel in fetched) {
                    if (channel.code.isBlank()) continue
                    out.putIfAbsent(channel.code, MagisSource.channelFrom(channel, category.id, category.name))
                }
                // A short page means the category is exhausted.
                if (fetched.size < MagisLiveClient.PAGE_SIZE) break
                page++
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
        return MagisStack(session, MagisLiveClient(portal, session, configProvider = { provider.current }))
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
