package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.identify.SportsLiveTeamIdentity
import com.nuvio.tv.core.sportslive.identify.TeamIdentityBook
import com.nuvio.tv.core.sportslive.scores.TeamDirectory
import com.nuvio.tv.domain.model.SportsLiveItem

/**
 * A card's inferred copy (#132 P4: its league from its teams; #168: its teams' identity) and whether it still holds
 * (live.25, #221). It holds while every team list the inference read is as it was and the teams' identity is the same:
 * a learned logo, a score match, an ESPN start or another league's list landing never infers the card again (each did,
 * for all ~750 cards, 3-4 times per open on the TV), while a list it read or its identity changing always does, so a
 * card never keeps a sport the directory or the identities now say otherwise about.
 */
class SportsLiveInferredSport(
    val item: SportsLiveItem,
    private val stillHolds: () -> Boolean
) {
    /** True while the facts [item] was inferred from are unchanged: an equal card may take [item] again. */
    fun holds(): Boolean = stillHolds()

    companion object {
        /**
         * [item] inferred from [directory] and [book], with what that read. The identity is read before the card is
         * identified and a list's generation with the list, so a change racing the inference only makes it run again.
         */
        fun infer(
            item: SportsLiveItem,
            directory: TeamDirectory = TeamDirectory.shared,
            book: TeamIdentityBook = TeamIdentityBook.shared
        ): SportsLiveInferredSport {
            val identity = SportsLiveTeamIdentity.identityOf(item, book)
            val (inferred, read) = directory.reading { SportsLiveSportInference.infer(item, directory) }
            val identified = SportsLiveTeamIdentity.identified(inferred, book)
            return SportsLiveInferredSport(identified) {
                directory.unchangedSince(read) && SportsLiveTeamIdentity.identityOf(item, book) == identity
            }
        }
    }
}
