package com.nuvio.app.features.livetv

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.features.livetv.data.EpgStore
import com.nuvio.app.features.livetv.data.LiveTvFiles
import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.data.PrefValue
import com.nuvio.app.features.livetv.ondemand.OnDemandStore
import com.nuvio.app.features.livetv.record.RecordingPlatform
import com.nuvio.app.features.livetv.sync.LiveTvDriveHttp

/**
 * Persists Live TV's preferences in SharedPreferences. Each stored value keeps its own type
 * (boolean, int, long, string, string set), the same five the TV app's DataStore holds, so the
 * map reads back exactly as it was written.
 */
internal object LiveTvPreferencesAndroid {
    private const val preferencesName = "livetv_prefs"
    private const val devicePreferencesName = "livetv_device_prefs"

    /** Magis keeps its own file: the minted device must survive a launch, or every start mints a
     *  new one and the portal sees device churn. */
    private const val magisPreferencesName = "magis_prefs"

    private var initialized = false

    /** Sets Live TV up once (the activity, a recording alarm or the recording service may each be first). */
    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        LiveTvFiles.initialize(context)
        EpgStore.initialize(context)
        OnDemandStore.initialize(context)
        RecordingPlatform.initialize(context)
        LiveTvDriveHttp.initialize(context)
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        val devicePreferences = context.getSharedPreferences(devicePreferencesName, Context.MODE_PRIVATE)
        LiveTvPreferences.installPersistence(
            load = { read(preferences) },
            save = { data -> write(preferences, data) },
            loadDevice = { read(devicePreferences) },
            saveDevice = { data -> write(devicePreferences, data) },
        )
        val magisPreferences = context.getSharedPreferences(magisPreferencesName, Context.MODE_PRIVATE)
        com.nuvio.app.features.livetv.native.magis.MagisBootstrap.stores.installPersistence(
            loadSession = { magisPreferences.getString("session", null) },
            saveSession = { json -> magisPreferences.edit().putString("session", json).apply() },
            loadConfig = { magisPreferences.getString("config", null) },
            saveConfig = { json -> magisPreferences.edit().putString("config", json).apply() },
        )
    }

    private fun read(preferences: SharedPreferences): Map<String, PrefValue> {
        val out = HashMap<String, PrefValue>()
        preferences.all.forEach { (key, value) ->
            val v = when (value) {
                is Boolean -> PrefValue.Bool(value)
                is Int -> PrefValue.Int(value)
                is Long -> PrefValue.Long(value)
                is String -> PrefValue.Str(value)
                is Set<*> -> PrefValue.StringSet(value.mapNotNull { it as? String }.toSet())
                else -> null
            }
            if (v != null) out[key] = v
        }
        return out
    }

    private fun write(preferences: SharedPreferences, data: Map<String, PrefValue>) {
        val editor = preferences.edit().clear()
        data.forEach { (key, value) ->
            when (value) {
                is PrefValue.Bool -> editor.putBoolean(key, value.value)
                is PrefValue.Int -> editor.putInt(key, value.value)
                is PrefValue.Long -> editor.putLong(key, value.value)
                is PrefValue.Str -> editor.putString(key, value.value)
                is PrefValue.StringSet -> editor.putStringSet(key, value.value)
            }
        }
        editor.apply()
    }
}
