package com.nuvio.app.features.livetv.native.magis

/**
 * Where the Magis session and the remote config are persisted on the mobile client.
 *
 * The app's own persistence is a pair of lambdas handed in from `androidMain`
 * (`installPersistence(load, save)`, the same idiom `LiveTvPreferences` uses), so these adapters
 * are just the two `MagisSessionStore`/`MagisConfigStore` shapes over a JSON string. Until the
 * lambdas are installed — and in tests — everything stays in memory, which is a working (if
 * forgetful) session: the portal mints a device again on the next launch.
 */
internal class MagisStores {

    private var loadSession: () -> String? = { null }
    private var saveSession: (String) -> Unit = {}
    private var loadConfig: () -> String? = { null }
    private var saveConfig: (String) -> Unit = {}

    fun installPersistence(
        loadSession: () -> String?,
        saveSession: (String) -> Unit,
        loadConfig: () -> String?,
        saveConfig: (String) -> Unit,
    ) {
        this.loadSession = loadSession
        this.saveSession = saveSession
        this.loadConfig = loadConfig
        this.saveConfig = saveConfig
    }

    fun sessionStore(): MagisSessionStore = object : MagisSessionStore {
        override fun save(s: StoredSession) {
            saveSession(
                MagisJsonObject()
                    .put("userId", s.userId)
                    .put("userToken", s.userToken)
                    .put("sn", s.sn)
                    .toString(),
            )
        }

        override fun read(): StoredSession? {
            val text = loadSession() ?: return null
            val doc = runCatching { MagisJsonObject(text) }.getOrNull() ?: return null
            val sn = doc.optString("sn")
            if (sn.isBlank()) return null
            return StoredSession(
                userId = doc.optString("userId"),
                userToken = doc.optString("userToken"),
                sn = sn,
            )
        }
    }

    fun configStore(): MagisConfigStore = object : MagisConfigStore {
        override fun save(config: MagisRuntimeConfig) {
            saveConfig(config.toJson())
        }

        override fun read(): MagisRuntimeConfig? = loadConfig()?.let { parseMagisConfig(it) }
    }
}

/**
 * No TMDB title lookup on the mobile client yet.
 *
 * The TV client resolves `title`/`year` from TMDB so the VOD flow can search by name when the
 * portal doesn't match. The mobile app has no equivalent service wired (its TMDB usage is
 * collection-oriented), so the VOD flow runs with the id-only path: it still resolves, but the
 * search fallback has nothing to search for. `MagisTitleLookup` is a port precisely so this can
 * be filled in without touching the protocol.
 */
internal object NoMagisTitleLookup : MagisTitleLookup {
    override suspend fun lookup(id: String, isSeries: Boolean): MagisTitleInfo? = null
}
