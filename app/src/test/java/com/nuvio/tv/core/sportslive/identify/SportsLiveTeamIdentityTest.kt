package com.nuvio.tv.core.sportslive.identify

import com.nuvio.tv.core.sportslive.SportsLiveMySportsScope
import com.nuvio.tv.core.sportslive.SportsLiveSportKind
import com.nuvio.tv.core.sportslive.identify.TeamLookupFixtures.game
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** #168: an identified game takes its sport word and league; a game whose sport is known is left alone. */
class SportsLiveTeamIdentityTest {

    private val euroLeague = GameIdentity("basketball", "EuroLeague Basketball", "a.png" to "b.png")
    private val book = TeamIdentityBook().apply { put("Maccabi Tel Aviv", "BC Dubai", euroLeague) }

    @Test
    fun `an unknown game takes its identity's sport word and league`() {
        val item = game("Maccabi Tel Aviv vs BC Dubai", genres = listOf("Live"))
        val identified = SportsLiveTeamIdentity.identified(item, book)
        assertEquals("Basketball", identified.sport)
        assertEquals(listOf("Live", "EuroLeague Basketball"), identified.meta.genres)
        assertEquals(SportsLiveSportKind.BASKETBALL, SportsLiveMySportsScope.kindOf(identified))
        // Written the other way round, the same game.
        assertEquals("Basketball", SportsLiveTeamIdentity.identified(game("BC Dubai vs Maccabi Tel Aviv"), book).sport)
        // Badges follow the asked order.
        assertEquals("b.png" to "a.png", book.identityOf("BC Dubai", "Maccabi Tel Aviv")!!.badges)
    }

    @Test
    fun `a game whose sport is known, an unanswered game and a channel are left alone`() {
        val soccer = game("Maccabi Tel Aviv vs BC Dubai", sport = "Soccer")
        assertSame(soccer, SportsLiveTeamIdentity.identified(soccer, book))
        val other = game("Hapoel Holon vs BC Dubai")
        assertSame(other, SportsLiveTeamIdentity.identified(other, book))
        val channel = game("Maccabi Tel Aviv vs BC Dubai", isChannel = true)
        assertSame(channel, SportsLiveTeamIdentity.identified(channel, book))
        // A game whose teams did not agree.
        val unknownBook = TeamIdentityBook().apply { put("Maccabi Tel Aviv", "BC Dubai", null) }
        val unknown = game("Maccabi Tel Aviv vs BC Dubai")
        assertSame(unknown, SportsLiveTeamIdentity.identified(unknown, unknownBook))
        // An empty book answers nothing.
        assertSame(unknown, SportsLiveTeamIdentity.identified(unknown, TeamIdentityBook()))
    }

    @Test
    fun `the book's revision moves only when an identity is learned`() {
        val fresh = TeamIdentityBook()
        fresh.put("A Team", "B Team", null)
        assertEquals(0, fresh.revision.value)
        fresh.put("Maccabi Tel Aviv", "BC Dubai", euroLeague)
        assertEquals(1, fresh.revision.value)
    }
}
