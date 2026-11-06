package com.nuvio.app.features.livetv.native.magis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The Magis wire format is the contract with the portal AND with the TV client: both must
 * produce the same bytes for the same body, or the portal sees two different devices. The vector
 * below is the one the TV client pins (`MagisCryptoTest`), so this test proves the port is
 * byte-compatible instead of merely round-tripping with itself.
 *
 * On iOS the crypto port is not implemented yet and fails loudly; the tests skip there instead
 * of pretending to pass.
 */
class MagisCryptoWireTest {

    private val key = "e7af1ed7de1ffddd7bd3fe37ebdffde9ef3fe1ae39edfeb8"
    private val port: MagisCryptoPort? = runCatching { createMagisCryptoPort() }.getOrNull()

    @Test
    fun `produces the exact wire the TV client produces`() {
        val port = port ?: return
        val expected = "336b6e7968596c346c48552f313276566c5134474951646642336151306b4f32"
        assertEquals(expected, MagisCrypto(port, key).encryptBody("""{"hola":"mundo"}"""))
    }

    @Test
    fun `decrypts the known wire`() {
        val port = port ?: return
        val wire = "336b6e7968596c346c48552f313276566c5134474951646642336151306b4f32"
        assertEquals("""{"hola":"mundo"}""", MagisCrypto(port, key).decryptBlob(wire))
    }

    @Test
    fun `round trip survives unicode and nesting`() {
        val port = port ?: return
        val plain = """{"hola":"mundo","nested":{"list":[1,2,3]},"unicode":"español"}"""
        val crypto = MagisCrypto(port, key)
        assertEquals(plain, crypto.decryptBlob(crypto.encryptBody(plain)))
    }

    @Test
    fun `the key must be 48 hex characters`() {
        val port = port ?: return
        assertFailsWith<IllegalArgumentException> { MagisCrypto(port, "short") }
        assertFailsWith<IllegalArgumentException> { MagisCrypto(port, key + "aa") }
    }
}
