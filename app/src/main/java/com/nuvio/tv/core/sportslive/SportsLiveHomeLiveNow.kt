package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.identify.SportsLiveTeamIdentity
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.TeamDirectory
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem

/**
 * Home's Live now row in Live Now's own rules and order (RC TV finding, live.23: Home listed "ATP" and two "ATP 250 ·
 * Stockholm Open" rows, and other sports' games, that Live Now keeps under More live). The steps and functions are the
 * section's, in its order:
 * - titles hidden from Live Now leave ([SportsLiveHiddenTitles]), and so do "A vs B" listings that are no game in progress
 *   ([SportsLiveOtherStreams], live.27: Live now's Other streams, never Home's row); each card takes the league its teams name
 *   ([SportsLiveSportInference], as the section's pinned tabs); followed teams' games are marked ([SportsLiveItemBucketer.pinFollowed]);
 * - under My sports, listings that are no game leave ([SportsLiveOtherEvents.split], H), as they leave Live now for
 *   More live (with nothing picked they stay, as on Live now, copies of one listing as one card);
 * - My sports scopes it as it scopes Live now ([SportsLiveMySportsScope]: other sports, J3's unplaced games and untagged
 *   games are More live's, not Home's);
 * - ranked by importance ([SportsLiveImportance], J2): followed teams first, then My sports by tier.
 * Copies of one game are already one card (the section's shared buckets).
 */
object SportsLiveHomeLiveNow {
    /** The section's sport inference for one card. */
    val infer: (SportsLiveItem) -> SportsLiveItem = { item ->
        SportsLiveTeamIdentity.identified(SportsLiveSportInference.infer(item, TeamDirectory.shared))
    }

    fun of(
        live: List<SportsLiveItem>,
        followed: List<FollowedTeam>,
        mySports: MySports,
        hidden: Set<String>,
        inferSport: (SportsLiveItem) -> SportsLiveItem = infer,
        nowMs: Long = System.currentTimeMillis()
    ): List<SportsLiveItem> {
        val listed = SportsLiveOtherStreams.split(SportsLiveHiddenTitles.filter(live, hidden), nowMs).first.map(inferSport)
        val pinned = SportsLiveItemBucketer.pinFollowed(listed, followed)
        // As Live now: with nothing picked in My sports there is no More live, and they stay listed (their copies as one,
        // as More live's Other folds them); under My sports they are More live's.
        val (gamesOnly, others) = SportsLiveOtherEvents.split(pinned)
        val games = if (mySports.isDefault) gamesOnly + others else gamesOnly
        val scoped = SportsLiveMySportsScope.scope(games, mySports)
        return SportsLiveImportance.sort(scoped, mySports)
    }
}
