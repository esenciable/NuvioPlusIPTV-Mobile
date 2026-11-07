package com.nuvio.app.features.livetv.native.magis

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

/** The ~150 ms courtesy gap between portal calls, measured against the live portal (see the TV
 *  client's odd/tasks/magis-vod-native.md V4a): the plugin sends unspaced and is accepted. */
internal const val MAGIS_RATE_LIMIT_MS = 150L

/**
 * Talks to the Magis portal over the app's own HTTP stack (Ktor), mirroring the TV client's
 * `MagisPortalClient` line by line: the same encrypted body, the same ~15 device fields glued to
 * every request, the same three headers, the same host failover, the same 150 ms pace.
 *
 * This is the only place the mobile client differs from the TV client for transport, which is
 * the whole point of the port: the protocol itself lives in the portable package.
 *
 * [send] is injectable so the tests drive it without a socket.
 */
internal class MagisPortalHttp(
    private val configProvider: () -> MagisRuntimeConfig,
    private val cryptoProvider: () -> MagisCrypto?,
    /** The minted device's `sn`, read per call: the session mints it and changes it live. */
    private val snProvider: () -> String = { "" },
    /** `https` in production; `http` exists so tests can point this at a local server. */
    private val scheme: String = "https",
    private val send: suspend (url: String, headers: Map<String, String>, body: String) -> String =
        { url, headers, body -> ktorSend(url, headers, body) },
) : MagisPortalLike {

    private val rateLimit = Mutex()
    private var lastCallAt = TimeSource.Monotonic.markNow()

    /** Host that worked last time, tried first so a dead host isn't paid for on every call. */
    private var preferredHost: String? = null

    override suspend fun call(
        path: String,
        bean: Map<String, Any?>,
        baseFields: Boolean,
        userId: String,
        userToken: String,
        sn: String?,
    ): MagisResult<MagisJsonObject> {
        val crypto = cryptoProvider()
            ?: return MagisResult.RedError(IllegalStateException("config de Magis sin clave 3DES usable"))
        val config = configProvider()
        val body = buildMap<String, Any?> {
            if (baseFields) {
                put("portalCode", PORTAL_CODE)
                put("userId", userId)
                put("userToken", userToken)
            }
            putAll(bean)
            putAll(deviceDict(config, sn ?: snProvider()))
        }
        val wire = crypto.encryptBody(MagisJsonObject(body).toString())

        magisLog("portal: POST $path hosts=${config.hosts.size} crypto=${crypto != null}")
        waitTurn()
        var lastError: Throwable? = null
        for (host in hostOrder(config)) {
            val headers = mapOf(
                "apk" to config.appId,
                "apkVer" to config.apkVerHeader,
                "spkgVer" to config.spkgVer,
                "User-Agent" to "okhttp/3.12.12",
            )
            try {
                val raw = send("$scheme://$host/api/portalCore/$path", headers, wire)
                val j = MagisJsonObject(raw)
                preferredHost = host
                val rc = j.optString("returnCode").takeIf { it.isNotEmpty() }
                if (rc != null && rc != "0") {
                    val message = j.optString("errorMessage").ifBlank { null }
                    magisLog("portal: $path rejected code=$rc msg=$message")
                    return MagisResult.PortalError(rc, message)
                }
                val data = j.optString("data")
                return if (data.isNotEmpty()) {
                    MagisResult.Ok(MagisJsonObject(crypto.decryptBlob(data)))
                } else {
                    MagisResult.Ok(j)
                }
            } catch (e: Throwable) {
                // Network down, TLS, or a body that isn't JSON: this host is no good, next one.
                magisLog("portal: host $host failed ${e::class.simpleName}: ${e.message}")
                lastError = e
            }
        }
        return MagisResult.RedError(lastError ?: IllegalStateException("sin hosts configurados"))
    }

    private fun hostOrder(config: MagisRuntimeConfig): List<String> {
        val preferred = preferredHost ?: return config.hosts
        return listOf(preferred) + config.hosts.filter { it != preferred }
    }

    private suspend fun waitTurn() = rateLimit.withLock {
        val elapsedMs = lastCallAt.elapsedNow().inWholeMilliseconds
        if (elapsedMs < MAGIS_RATE_LIMIT_MS) {
            kotlinx.coroutines.delay(MAGIS_RATE_LIMIT_MS - elapsedMs)
        }
        lastCallAt = TimeSource.Monotonic.markNow()
    }

    /**
     * The ~15 fields the original app glues to every body. Fixed values are the emulator's, the
     * one the protocol was captured with; `reserve1`/`deviceToken`/`drmId` go empty on purpose.
     */
    private fun deviceDict(config: MagisRuntimeConfig, sn: String): Map<String, Any?> = mapOf(
        "loginType" to "2",
        "appLanguage" to "en",
        "apkVersion" to config.apkVersion,
        "sysVersion" to config.spkgVer,
        "appId" to config.appId,
        "hardwareInfo" to "ranchu",
        "model" to "sdk_gphone64_arm64",
        "product" to "sdk_gphone64_arm64",
        "cpu" to "arm64-v8a",
        "B29" to "",
        "reserve1" to "",
        "deviceToken" to "",
        "sn" to sn,
        "drmId" to "",
        "sdkVer" to 36,
    )

    private companion object {
        const val PORTAL_CODE = "masnew"
    }
}

/** One client for the whole process: Magis calls are frequent and a per-call engine is waste. */
private val magisHttpClient: HttpClient by lazy { com.nuvio.app.core.network.createApiHttpClient() }

/** The Ktor transport: one POST, JSON in, text out. Throws on any failure so the caller fails over. */
private suspend fun ktorSend(url: String, headers: Map<String, String>, body: String): String {
    return magisHttpClient.post(url) {
        headers.forEach { (name, value) -> header(name, value) }
        contentType(ContentType.Application.Json)
        setBody(body)
    }.bodyAsText()
}
