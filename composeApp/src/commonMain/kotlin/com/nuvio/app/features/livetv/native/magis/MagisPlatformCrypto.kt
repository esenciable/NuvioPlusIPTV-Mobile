package com.nuvio.app.features.livetv.native.magis

/**
 * The platform primitives the Magis protocol needs (3DES, MD5, randomness, base64).
 *
 * The portable package declares the interface; each target answers it. Android uses
 * `javax.crypto` + `android.util.Base64`; iOS still has no implementation and fails loudly
 * rather than silently producing bodies the portal would reject.
 */
internal expect fun createMagisCryptoPort(): MagisCryptoPort
