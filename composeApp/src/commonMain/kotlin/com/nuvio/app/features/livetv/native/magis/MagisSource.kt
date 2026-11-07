package com.nuvio.app.features.livetv.native.magis

import com.nuvio.app.features.livetv.model.LiveChannel

/**
 * The bridge between the portal's channels and this app's Live TV model.
 *
 * A Magis channel never carries a URL: a live signal is only servable for a short window, so the
 * list keeps a MARKER (`magis-live://<code>`) and the real URL plus its signed headers are minted
 * seconds before playback (see [MagisStack.resolve]). The marker travels in `url` and the portal
 * code travels in the channel `key`, which keeps favourites and last-channel memory stable across
 * reloads.
 */
internal object MagisSource {

    const val SOURCE_ID = "magis"
    const val MARKER_SCHEME = "magis-live://"
    const val SOURCE_NAME = "Magis"

    fun keyOf(code: String): String = "$SOURCE_ID:$code"

    fun marker(code: String): String = MARKER_SCHEME + code

    /** Channel key -> the portal's channel code, or null when the key is not a Magis one. */
    fun codeOf(key: String): String? {
        val prefix = "$SOURCE_ID:"
        if (!key.startsWith(prefix)) return null
        return key.removePrefix(prefix).takeIf { it.isNotBlank() }
    }

    fun isMagis(channel: LiveChannel): Boolean = channel.sourceId == SOURCE_ID

    fun channelFrom(channel: MagisLiveChannel, categoryId: Int, categoryName: String): LiveChannel =
        LiveChannel(
            key = keyOf(channel.code),
            sourceId = SOURCE_ID,
            sourceName = SOURCE_NAME,
            name = channel.name,
            tvgId = null,
            tvgName = null,
            logo = channel.logo.takeIf { it.isNotBlank() },
            groupId = "$SOURCE_ID::$categoryId",
            group = categoryName,
            number = channel.number,
            url = marker(channel.code),
            headers = emptyMap(),
            catchup = null,
            drm = null,
        )
}
