package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.sports.BasketballFeeds
import com.nuvio.app.features.livetv.sports.GameState
import com.nuvio.app.features.livetv.sports.League
import com.nuvio.app.features.livetv.sports.Sport
import com.nuvio.app.features.livetv.sports.SportProvider
import com.nuvio.app.features.livetv.sports.SportsCatalog
import com.nuvio.app.features.livetv.sports.SportsFeeds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import kotlin.test.assertTrue

/**
 * The two basketball leagues ESPN doesn't carry: their own answers, rewritten into a scoreboard and
 * read back by the parser the page already uses.
 *
 * The fixtures are real, trimmed captures (see [SportsFixtures]) — an EuroLeague season document
 * from the competition's v2 API, the current ACB round out of the Next.js payload, and an NBA
 * scoreboard straight from ESPN so the leagues that were already there are pinned too.
 */
class BasketballProviderFeedsTest {

    private fun league(id: String): League = checkNotNull(SportsCatalog.league(id)) { "no league $id" }

    @Test
    fun `euroleague season document becomes a scoreboard`() {
        val raw = SportsFixtures.EUROLEAGUE_GAMES
        val feeds = BasketballFeeds { url ->
            if (url.contains("/competitions/E/seasons/")) raw else throw IllegalStateException("unexpected $url")
        }
        val league = league("euroleague")

        val events = runBlocking { SportsFeeds.parseScoreboard(league, feeds.scoreboard(league)) }

        assertEquals(2, events.size, "one played game and one still to play")
        val played = events.first { it.state == GameState.POST }
        assertEquals("Partizan", played.home?.team?.name)
        assertEquals("Maccabi", played.away?.team?.name)
        assertEquals("88", played.home?.score)
        assertEquals("67", played.away?.score)
        assertTrue(played.home?.winner == true, "the winner is flagged")
        assertEquals(4, played.home?.periods?.size, "quarters are kept")
        assertNotNull(played.home?.team?.logo, "a crest")
        assertTrue(played.startMs > 0L, "a real date")
        assertTrue(events.any { it.state == GameState.PRE }, "never started games stay ahead")
    }

    @Test
    fun `acb round payload becomes a scoreboard with its channels`() {
        val html = SportsFixtures.ACB_ROUND_CURRENT
        val feeds = BasketballFeeds { url ->
            // Only the round the page opens with: the rounds either side are fetched too, and a
            // failure there is meant to be harmless.
            if (url == "https://acb.com/es/liga/partidos") html else throw IllegalStateException("no round for $url")
        }
        val league = league("acb")

        val events = runBlocking { SportsFeeds.parseScoreboard(league, feeds.scoreboard(league)) }

        // The fixture is the round trimmed to 4 of its 9 games (and the 8 clubs they name).
        assertEquals(4, events.size)
        val first = events.first { it.home?.team?.abbr == "K&U" }
        assertNotNull(first.home, "the home side resolves through its teams:N reference")
        assertEquals("MOB", first.away?.team?.abbr)
        assertEquals("100", first.home?.score)
        assertEquals("99", first.away?.score)
        assertEquals(GameState.POST, first.state)
        assertNotNull(first.home?.team?.color, "the home club's colour")
        assertNotNull(first.home?.team?.logo, "the home club's crest")
        assertTrue(first.subtitle?.contains("Jornada 7") == true, "the round is named")
        assertTrue(first.broadcasts.contains("DAZN"), "the TV channels come across")
        assertTrue(
            first.broadcasts.containsAll(listOf("Movistar Plus +", "Orange", "Vodafone TV")),
            "all of them"
        )
    }

    @Test
    fun `espn leagues keep the feed they had`() {
        val nba = league("basketball/nba")
        assertEquals(SportProvider.ESPN, nba.provider)
        assertEquals("basketball/nba", nba.espnPath)
        assertTrue(nba.hasFeed)

        val events = SportsFeeds.parseScoreboard(nba, SportsFixtures.ESPN_NBA_SCOREBOARD)
        assertEquals(1, events.size)
        val game = events.first()
        assertNotNull(game.home)
        assertNotNull(game.away)
        assertTrue(game.home?.team?.name?.isNotBlank() == true)
        assertTrue(game.startMs > 0L)
    }

    @Test
    fun `nothing else in the catalogue was rerouted`() {
        val own = SportsCatalog.leagues.filter { it.provider != SportProvider.ESPN }.map { it.id }.toSet()
        assertEquals(setOf("acb", "euroleague"), own)
        // Visible wherever the device is, so neither is hidden by the region it is read from.
        assertEquals(League.ALL_REGIONS, league("acb").regions)
        assertEquals(League.ALL_REGIONS, league("euroleague").regions)
        assertEquals(Sport.BASKETBALL, league("acb").sport)
        assertEquals(Sport.BASKETBALL, league("euroleague").sport)
    }
}
