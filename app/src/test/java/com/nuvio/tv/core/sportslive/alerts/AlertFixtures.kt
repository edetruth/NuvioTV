package com.nuvio.tv.core.sportslive.alerts

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind

internal object AlertFixtures {
    const val START = 1_800_000_000_000L
    const val MINUTE = 60_000L

    /** Score alerts at Every score (live.28): the tests of the score cards' plumbing, whatever the sport's moments. */
    val EVERY = SportsLiveAlertSettings(scoreAlertLevel = ScoreAlertLevel.EVERY_SCORE)

    // IND @ PHI: home Philadelphia 76ers, away Indiana Pacers (NBA, final in the fixture).
    val pacersFinal: ScoreSnapshot =
        EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first { it.eventId == "401909985" }

    fun item(
        name: String = "Pacers @ 76ers",
        id: String = name,
        addonId: String = "a",
        startMs: Long? = START,
        isLive: Boolean = false,
        followed: Boolean = false,
        sport: String? = "Basketball",
        isChannel: Boolean = false,
        alternates: List<SportsLiveItem> = emptyList()
    ) = SportsLiveItem(
        meta = MetaPreview(
            id = id, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = listOfNotNull(sport)
        ),
        addonId = addonId, addonName = addonId, addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = if (isChannel) SportsLiveKind.CHANNELS else SportsLiveKind.EVENTS, isLive = isLive, isChannel = isChannel,
        startMs = startMs, sport = sport, startHasTimeOfDay = startMs != null,
        teams = if (isChannel) null else SportsLiveTeams.parse(name), followed = followed, alternates = alternates
    )

    fun pre(): ScoreSnapshot = pacersFinal.copy(
        state = ScoreState.PRE, isFinal = false, clockText = null, startMs = START,
        home = pacersFinal.home.copy(score = null), away = pacersFinal.away.copy(score = null)
    )

    fun live(home: Int?, away: Int?, clock: String = "Q2 5:00"): ScoreSnapshot = pacersFinal.copy(
        state = ScoreState.IN, isFinal = false, clockText = clock, startMs = START,
        home = pacersFinal.home.copy(score = home), away = pacersFinal.away.copy(score = away)
    )

    fun final(home: Int, away: Int, note: String = "Final/OT"): ScoreSnapshot = pacersFinal.copy(
        state = ScoreState.POST, isFinal = true, clockText = note, startMs = START,
        home = pacersFinal.home.copy(score = home), away = pacersFinal.away.copy(score = away)
    )
}
