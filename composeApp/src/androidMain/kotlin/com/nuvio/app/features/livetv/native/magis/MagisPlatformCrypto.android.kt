package com.nuvio.app.features.livetv.native.magis

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Android implementation of the Magis crypto port.
 *
 * Base64 is NOT here: it moved to the portable package as pure Kotlin (`MagisBase64`), because
 * `android.util.Base64` is a stub in unit tests and `java.util.Base64` is API 26+ (minSdk is 24).
 * DES-EDE is in the platform's provider on every supported version.
 */
private object AndroidMagisCryptoPort : MagisCryptoPort {

    private val random = SecureRandom()

    override fun tripleDesEcbPkcs5(key: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray {
        val cipher = Cipher.getInstance("DESede/ECB/PKCS5Padding")
        cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "DESede"))
        return cipher.doFinal(data)
    }

    override fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

    override fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }
}

internal actual fun createMagisCryptoPort(): MagisCryptoPort = AndroidMagisCryptoPort
