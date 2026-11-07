package com.nuvio.app.features.livetv.native.magis

/**
 * Diagnostics for the native Magis source.
 *
 * `println` does NOT reach logcat on modern Android, so a silent `runCatching` around the portal
 * walk is invisible: this expect/actual exists so a failed bootstrap or a dead portal can be seen
 * in `adb logcat -s Magis` instead of guessed at.
 */
internal expect fun magisLog(message: String)
