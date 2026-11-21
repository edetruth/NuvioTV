package com.nuvio.tv.core.sportslive.scores

import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import okio.Buffer

/**
 * The scores last shown, kept on disk (live.23), so a reopen draws them at once while the first fetch is in flight.
 * One small JSON file: when they were shown, and each card's game (by card key) as it was shown, delayed. Team games
 * only, and only what a card draws (no scorers, situation or broadcasts), at most [MAX_GAMES]. A copy older than
 * [MAX_AGE_MS] is not read back; [LiveScoresRepository] drops a kept score once the card's own feed has answered.
 */
internal object KeptScores {
    /** A kept copy older than this is not shown: a live score moves on. */
    const val MAX_AGE_MS = 10 * 60_000L

    const val MAX_GAMES = 200

    /** [shown] (card key to game, as shown at [savedAt]) as the file's text. */
    fun encode(shown: Map<String, ScoreSnapshot>, savedAt: Long): String {
        val buffer = Buffer()
        JsonWriter.of(buffer).use { w ->
            w.beginObject()
            w.name("at").value(savedAt)
            w.name("games").beginArray()
            for ((key, s) in shown.entries.filter { it.value.individual == null }.take(MAX_GAMES)) {
                w.beginObject()
                w.name("k").value(key)
                w.name("e").value(s.eventId)
                w.name("sport").value(s.league.sport)
                w.name("league").value(s.league.league)
                w.name("slug").value(s.leagueSlug)
                w.opt("lname", s.leagueName)
                s.startMs?.let { w.name("start").value(it) }
                w.name("timed").value(s.timeValid)
                w.name("state").value(s.state.name)
                w.opt("clock", s.clockText)
                w.name("final").value(s.isFinal)
                w.name("home"); team(w, s.home)
                w.name("away"); team(w, s.away)
                w.endObject()
            }
            w.endArray()
            w.endObject()
        }
        return buffer.readUtf8()
    }

    /** A copy read back: when it was shown, and its games by card key. */
    class Kept(val savedAt: Long, val games: Map<String, ScoreSnapshot>)

    /** The copy in [text]; null when it is older than [MAX_AGE_MS] at [nowMs], empty or unreadable. */
    fun decode(text: String, nowMs: Long): Kept? = try {
        val r = JsonReader.of(Buffer().writeUtf8(text))
        var at: Long? = null
        val games = LinkedHashMap<String, ScoreSnapshot>()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "at" -> at = r.nextLong()
                "games" -> {
                    r.beginArray()
                    while (r.hasNext()) {
                        val (key, snapshot) = game(r) ?: continue
                        if (games.size < MAX_GAMES) games[key] = snapshot
                    }
                    r.endArray()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        val savedAt = at
        if (savedAt == null || savedAt > nowMs || nowMs - savedAt > MAX_AGE_MS || games.isEmpty()) null else Kept(savedAt, games)
    } catch (e: Exception) {
        null
    }

    private fun JsonWriter.opt(name: String, value: String?) {
        if (value != null) name(name).value(value)
    }

    private fun team(w: JsonWriter, t: ScoreTeam) {
        w.beginObject()
        w.opt("id", t.id)
        w.opt("ab", t.abbreviation)
        w.opt("dn", t.displayName)
        w.opt("sdn", t.shortDisplayName)
        w.opt("n", t.name)
        w.opt("loc", t.location)
        w.opt("logo", t.logo)
        t.score?.let { w.name("sc").value(it.toLong()) }
        w.name("ls").beginArray()
        t.linescores.forEach { w.value(it.toLong()) }
        w.endArray()
        w.opt("rec", t.record)
        if (t.alternateNames.isNotEmpty()) {
            w.name("alt").beginArray()
            t.alternateNames.forEach { w.value(it) }
            w.endArray()
        }
        t.rank?.let { w.name("rank").value(it.toLong()) }
        w.endObject()
    }

    private fun game(r: JsonReader): Pair<String, ScoreSnapshot>? {
        var key: String? = null
        var eventId: String? = null
        var sport: String? = null
        var league: String? = null
        var slug: String? = null
        var leagueName: String? = null
        var start: Long? = null
        var timed = true
        var state: ScoreState? = null
        var clock: String? = null
        var final = false
        var home: ScoreTeam? = null
        var away: ScoreTeam? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "k" -> key = r.nextString()
                "e" -> eventId = r.nextString()
                "sport" -> sport = r.nextString()
                "league" -> league = r.nextString()
                "slug" -> slug = r.nextString()
                "lname" -> leagueName = r.nextString()
                "start" -> start = r.nextLong()
                "timed" -> timed = r.nextBoolean()
                "state" -> state = r.nextString().let { name -> ScoreState.entries.firstOrNull { it.name == name } }
                "clock" -> clock = r.nextString()
                "final" -> final = r.nextBoolean()
                "home" -> home = team(r)
                "away" -> away = team(r)
                else -> r.skipValue()
            }
        }
        r.endObject()
        if (key == null || eventId == null || sport == null || league == null || state == null || home == null || away == null) return null
        val feed = EspnLeague(sport, league)
        return key to ScoreSnapshot(
            eventId = eventId, league = feed, leagueSlug = slug ?: feed.league, leagueName = leagueName, startMs = start,
            timeValid = timed, state = state, clockText = clock, isFinal = final, home = home, away = away
        )
    }

    private fun team(r: JsonReader): ScoreTeam {
        val strings = HashMap<String, String>()
        var score: Int? = null
        var rank: Int? = null
        val lines = ArrayList<Int>()
        val alternates = ArrayList<String>()
        r.beginObject()
        while (r.hasNext()) {
            when (val name = r.nextName()) {
                "sc" -> score = r.nextInt()
                "rank" -> rank = r.nextInt()
                "ls" -> {
                    r.beginArray()
                    while (r.hasNext()) lines += r.nextInt()
                    r.endArray()
                }
                "alt" -> {
                    r.beginArray()
                    while (r.hasNext()) alternates += r.nextString()
                    r.endArray()
                }
                "id", "ab", "dn", "sdn", "n", "loc", "logo", "rec" -> strings[name] = r.nextString()
                else -> r.skipValue()
            }
        }
        r.endObject()
        return ScoreTeam(
            id = strings["id"], abbreviation = strings["ab"], displayName = strings["dn"], shortDisplayName = strings["sdn"],
            name = strings["n"], location = strings["loc"], logo = strings["logo"], score = score, linescores = lines,
            record = strings["rec"], alternateNames = alternates, rank = rank
        )
    }
}
