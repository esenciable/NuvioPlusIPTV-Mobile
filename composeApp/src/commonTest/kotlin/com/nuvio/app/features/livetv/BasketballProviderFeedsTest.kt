package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.sports.BasketballFeeds
import com.nuvio.app.features.livetv.sports.GameState
import com.nuvio.app.features.livetv.sports.League
import com.nuvio.app.features.livetv.sports.Sport
import com.nuvio.app.features.livetv.sports.SportProvider
import com.nuvio.app.features.livetv.sports.SportsCatalog
import com.nuvio.app.features.livetv.sports.SportsFeeds
import com.nuvio.app.features.livetv.sports.SportsEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlin.test.assertTrue

/**
 * The two basketball leagues ESPN doesn't carry: their own answers, rewritten into a scoreboard and
 * read back by the parser the page already uses.
 *
 * The fixtures are real, trimmed captures (see [SportsFixtures]) — an EuroLeague season document
 * from the competition's v2 API, three ACB rounds from its own JSON API, and an NBA scoreboard
 * straight from ESPN so the leagues that were already there are pinned too.
 */
class BasketballProviderFeedsTest {

    private fun league(id: String): League = checkNotNull(SportsCatalog.league(id)) { "no league $id" }

    /**
     * ACB, answered by its own API. The key rides on the request (without it the API answers 401),
     * only [json]'s round answers, and the rounds either side of it fail: a round that fails has to
     * be skipped, never empty the list.
     */
    private fun acbFeeds(json: String): BasketballFeeds = BasketballFeeds(
        get = { url -> throw IllegalStateException("the ACB source needs its key, not a plain fetch: $url") },
        getWithHeaders = { url, headers ->
            assertTrue(headers["X-APIKEY"]?.isNotBlank() == true, "the ACB key must be sent")
            assertTrue(
                url.startsWith("$ACB_MATCHES?competitionId=1&isRoundSelected=true"),
                "unexpected $url"
            )
            // `round=` answers 200 and is ignored, and the season is not ours to send.
            assertTrue(!url.contains("&round=") && !url.contains("season="), "unexpected $url")
            if (url.contains("roundId=")) throw IllegalStateException("no round for $url")
            json
        }
    )

    private fun acbEvents(json: String): List<SportsEvent> = runBlocking {
        SportsFeeds.parseScoreboard(league("acb"), acbFeeds(json).scoreboard(league("acb")))
    }

    @Test
    fun `euroleague season document becomes a scoreboard`() {
        val raw = SportsFixtures.EUROLEAGUE_GAMES
        val feeds = BasketballFeeds(get = { url ->
            if (url.contains("/competitions/E/seasons/")) raw else throw IllegalStateException("unexpected $url")
        })
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
    fun `acb round from its api becomes a scoreboard with its channels`() {
        val events = acbEvents(SportsFixtures.ACB_ROUND)

        assertEquals(3, events.size, "the round's games the fixture keeps")
        val first = events.first { it.home?.team?.abbr == "K&U" }
        assertEquals("MOB", first.away?.team?.abbr, "teams resolve by id, not by an index into the page")
        assertEquals("Kids&Us Manresa", first.home?.team?.name, "the club's own name")
        assertEquals("100", first.home?.score)
        assertEquals("99", first.away?.score)
        assertEquals(GameState.POST, first.state, "FINALIZED is final")
        assertNotNull(first.home?.team?.color, "the club's own colour")
        assertNotNull(first.home?.team?.logo, "the club's crest")
        assertTrue(first.subtitle?.contains("Jornada 7") == true, "the round is named from the competition's list")
        // The API's broadcast list is flat with a `primary` flag, not the page's {primary, secondary}.
        // (The parser has always put a streaming app last, so "Movistar Plus +" sorts down.)
        assertEquals(
            listOf("DAZN", "Orange", "Vodafone TV", "Movistar Plus +"),
            first.broadcasts,
            "the primary channels come across instead of being lost"
        )
        val second = events.first { it.home?.team?.abbr == "BAR" }
        assertEquals(
            listOf("DAZN", "TV3/3Cat", "TVG", "Orange", "Vodafone TV"),
            second.broadcasts.filterNot { it.endsWith("+") },
            "the primary channels in ACB's own `order`, not in the order of the array"
        )
        // The one match that flags "Movistar Plus +" as secondary: only the primaries are listed.
        val third = events.first { it.home?.team?.abbr == "ILE" }
        assertEquals(
            listOf("DAZN", "Vamos", "Orange", "Vodafone TV"),
            third.broadcasts,
            "a game whose channel list carries a non-primary entry lists the primaries only"
        )
    }

    @Test
    fun `acb games that have not started are scheduled, never live`() {
        // NOT_STARTED, measured with a 0-0 score on the board: a guess from the score alone would
        // call it live.
        val scheduled = acbEvents(SportsFixtures.ACB_ROUND_SCHEDULED).single()
        assertEquals(GameState.PRE, scheduled.state, "NOT_STARTED is scheduled")
        assertEquals("BRE", scheduled.home?.team?.abbr, "its teams still resolve by id")
        assertNull(scheduled.home?.score, "a game that has not started carries no score")
        assertNull(scheduled.away?.score)

        // NOT_SCHEDULED: a date on the calendar, no game yet.
        val unscheduled = acbEvents(SportsFixtures.ACB_ROUND_UNSCHEDULED).single()
        assertEquals(GameState.PRE, unscheduled.state, "NOT_SCHEDULED is scheduled")
        assertEquals("MBA", unscheduled.home?.team?.abbr)
        assertNull(unscheduled.home?.score)
    }

    @Test
    fun `a rejected key or an empty answer fails instead of hiding the round`() {
        val acb = league("acb")
        // The key is public but revocable: a 401 has to surface, not read as "no games this round".
        val rejected = runBlocking {
            runCatching {
                BasketballFeeds(
                    get = { url -> throw IllegalStateException("unexpected $url") },
                    getWithHeaders = { _, _ -> throw IllegalStateException("HTTP 401") }
                ).scoreboard(acb)
            }
        }
        assertTrue(rejected.isFailure, "a rejected key is a failure")

        val empty = runBlocking {
            runCatching {
                BasketballFeeds(
                    get = { url -> throw IllegalStateException("unexpected $url") },
                    getWithHeaders = { _, _ -> "{\"teams\":[],\"matches\":[]}" }
                ).scoreboard(acb)
            }
        }
        assertTrue(empty.isFailure, "an empty answer is a failure, not an empty league")
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

    private companion object {
        const val ACB_MATCHES = "https://api2.acb.com/api/seasondata/Competition/matches"
    }
}
