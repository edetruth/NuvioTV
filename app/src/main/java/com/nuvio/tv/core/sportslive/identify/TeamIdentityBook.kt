package com.nuvio.tv.core.sportslive.identify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The games [TeamIdentifier] has answered (#168), by their two team names in either order: an identity,
 * or none when the teams did not agree. In memory only; the names behind it are kept on the device.
 */
class TeamIdentityBook(private val maxGames: Int = MAX_GAMES) {

    private class Answer(val firstKey: String, val identity: GameIdentity?)

    private val answers = object : LinkedHashMap<String, Answer>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Answer>?): Boolean = size > maxGames
    }

    @Volatile
    private var empty = true

    private val _revision = MutableStateFlow(0)

    /** Bumped whenever a game's identity is learned: lists rebuild with it. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** The identity of the game between [first] and [second] (badges in this order); null when none is known. */
    fun identityOf(first: String, second: String): GameIdentity? {
        if (empty) return null
        val a = TeamIdentityRules.nameKey(first)
        val b = TeamIdentityRules.nameKey(second)
        val answer = synchronized(answers) { answers[pairKey(a, b)] } ?: return null
        val identity = answer.identity ?: return null
        return if (answer.firstKey == a) identity else identity.copy(badges = identity.badges.second to identity.badges.first)
    }

    /** True when the game between [first] and [second] was answered, identity or not. */
    internal fun has(first: String, second: String): Boolean {
        if (empty) return false
        val key = pairKey(TeamIdentityRules.nameKey(first), TeamIdentityRules.nameKey(second))
        return synchronized(answers) { answers.containsKey(key) }
    }

    /** Forgets the answer for [first] against [second] (a cricket label whose game is over); lists rebuild without it. */
    internal fun remove(first: String, second: String) {
        val key = pairKey(TeamIdentityRules.nameKey(first), TeamIdentityRules.nameKey(second))
        val removed = synchronized(answers) { answers.remove(key) }
        if (removed?.identity != null) _revision.update { it + 1 }
    }

    /** Stores the answer for [first] against [second]; [identity]'s badges are in that order. */
    internal fun put(first: String, second: String, identity: GameIdentity?) {
        val a = TeamIdentityRules.nameKey(first)
        val b = TeamIdentityRules.nameKey(second)
        synchronized(answers) { answers[pairKey(a, b)] = Answer(a, identity) }
        empty = false
        if (identity != null) _revision.update { it + 1 }
    }

    internal val isEmpty: Boolean get() = empty

    companion object {
        private const val MAX_GAMES = 2_048

        internal fun pairKey(a: String, b: String): String = if (a <= b) "$a\u0000$b" else "$b\u0000$a"

        /** The app's book, filled by [TeamIdentifier] and read by [SportsLiveTeamIdentity]. */
        val shared = TeamIdentityBook()
    }
}
