package com.nuvio.app.features.livetv.sports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The addon labels EVERY stream with the same `name` ("Esencial Sport") and puts the provider it
 * resolved in `resolution`. Reading `name` collapsed three links into one row, which is the bug the
 * operator reported ("solo aparece un enlace cuando son más"). This test pins the real payload.
 */
class SportsEsencialAddonTest {

    private val payload = """
        {"streams":[
          {"name":"Esencial Sport","title":"Boca Juniors vs Defensa Y Justicia · Fan LAT","url":"https://h/rblive/playlist?u=a","resolution":"Fan LAT","streamId":927292,"verified":true},
          {"name":"Esencial Sport","title":"Boca Juniors vs Defensa Y Justicia · TyR","url":"https://h/rblive/playlist?u=b","resolution":"TyR","streamId":934642,"verified":true},
          {"name":"Esencial Sport","title":"Boca Juniors vs Defensa Y Justicia · Sport PT","url":"https://h/rblive/playlist?u=c","resolution":"Sport PT","streamId":933286,"verified":true}
        ]}
    """.trimIndent()

    @Test
    fun `three links are three rows, each named after the provider it came from`() {
        val streams = SportsEsencialAddon.parseStreams(payload)
        assertEquals(3, streams.size)

        val rows = SportsEsencialAddon.rowsFor("rb_4398553_1", streams)
        assertEquals(3, rows.size)
        assertEquals(
            listOf("Esencial Sport · Fan LAT", "Esencial Sport · TyR", "Esencial Sport · Sport PT"),
            rows.map { it.channel.name },
        )
        // Distinct keys, or the grid would treat them as the same channel.
        assertEquals(3, rows.map { it.channel.key }.toSet().size)
        assertEquals(3, rows.map { it.channel.url }.toSet().size)
    }

    @Test
    fun `a stream without a provider label still shows, named after the addon`() {
        val one = """{"streams":[{"name":"Esencial Sport","url":"https://h/x"}]}"""
        val rows = SportsEsencialAddon.rowsFor("rb_1", SportsEsencialAddon.parseStreams(one))
        assertEquals(1, rows.size)
        assertEquals("Esencial Sport", rows[0].channel.name)
    }

    @Test
    fun `an empty or malformed answer is no rows, never a crash`() {
        assertTrue(SportsEsencialAddon.parseStreams("""{"streams":[]}""").isEmpty())
        assertTrue(SportsEsencialAddon.parseStreams("not json").isEmpty())
        assertTrue(SportsEsencialAddon.parseStreams("""{"nothing":1}""").isEmpty())
    }
}
