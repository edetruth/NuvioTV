# Individual sports in Results (R2, #55 part 2)

**Goal:** Tennis (ATP/WTA), MMA (UFC), Motorsport (F1) and Golf (PGA/LPGA) in My sports and in Results, with the design's individual-sport rows (§4 Results).
**Builds on:** R1 (`core/sportslive/scores/` MySports, ResultsRepository, SportsResults; Results UI in `ui/screens/sportslive/`).
**Precision rule:** a wrong result or a wrong replay is worse than none.

## ESPN data (checked with real JSON, 2026-11-17)
- **Tennis** `tennis/atp`, `tennis/wta` scoreboard `?dates=D`: events are *tournaments* active on D, each with `groupings[]` (draw: `mens-singles`, `womens-doubles`…) holding *every* match of the tournament (all days). Keep matches whose start is ESPN day D (US Eastern). Competitor: `athlete` (singles) or `roster` (doubles), `order`, `winner`, `linescores[{value, tiebreak}]`. Status `STATUS_FINAL` / `STATUS_RETIRED` / `STATUS_WALKOVER`. A match tiebreak is a set `1-0` with tiebreak points (10-4). `order`/`homeAway` do not give away the winner. Grand Slams list both tours' draws in each feed: ATP keeps men's draws, WTA women's. **Singles only** (doubles: follow-up).
- **UFC** `mma/ufc` `?dates=D`: one event per card dated D; `competitions[]` are fights (prelims first, main event last). Method only from `details[].type.text`: "Unofficial Winner Kotko" → KO/TKO, "…Submission", "…Decision"; round = `status.period`, time = `status.displayClock`. No winner flagged → draw/NC ("No winner"). Main event listed first.
- **F1** `racing/f1` `?dates=D`: the GP weekend with FP1-3, Qual, Race competitions; competitors carry `order` and `athlete` only. **Time/gap is not on the scoreboard** (needs 3 core-API calls per race): podium only. The Race row shows on the race's ESPN day.
- **Golf** `golf/pga`, `golf/lpga` `?dates=D`: one competition, every player with `order` and `score` to par ("-20", "E"). `endDate` (Eastern) is the final round's day (a Monday finish included). Shown on that day, only when final; top 5 by `order`, ties ("T4") from equal scores; position 1 is the winner (a playoff loser is 2, not T1).
- **Cricket (evaluate only, not built):** there is no `cricket/scoreboard` (404); leagues and tours are numeric ids that change per series/season (IPL 8048, "England tour of Australia 2026/27" 24273), scores are strings ("378 & 326/5 (57 ov)") and multi-day tests span several ESPN days. Only `apis/v2/scoreboard/header?sport=cricket` lists everything, and it is not date-addressable for past days. Needs its own design: not built.

## Task R2
1. **Model.** `ScoreSnapshot.individual: IndividualResult?` (TennisMatch / Fight / RaceResult / GolfResult), empty sides; event id `sport:id` so no clash with team ids. Section key: tennis tournament+draw ("Wimbledon · Men's Singles"), UFC card, F1, PGA/LPGA.
2. **Parsing** (`EspnIndividualResults`): tolerant all-nullable DTO fields added to `EspnScoreboardDto.kt`; the provider routes tennis/mma/racing/golf feeds there with the requested day.
3. **My sports.** `MySport` TENNIS, MMA, MOTORSPORT, GOLF (`SportKind.INDIVIDUAL`) with leagues ATP, WTA, UFC, F1, PGA, LPGA (all default within their sport). Unset My sports = every sport incl. these; a set My sports has them only when picked. Results requests a feed only for sports/leagues in effect; past days disk-cached; no polling.
4. **Rows.** Tennis: two player lines (winner bold) + set columns (tiebreak points small on the set's loser; match tiebreak as its points; "Ret."/"W/O" note). UFC: fighter vs fighter, then "Winner · KO/TKO R2 3:14" (decisions without round). F1: "1. Norris · 2. Verstappen · 3. Hadjar". Golf: winner + to par, "+4 more" collapsed. Hide until focused: the 1 s reveal hides winner bold, sets, method, podium and leaderboard; Off: nothing (as today).
5. **OK.** The Game page cannot render these sports. OK on a golf row expands/collapses its top 5 in place; OK on tennis/UFC/F1 rows does nothing (no toast). No hold-OK Follow menu on them.
6. **Replay: none for individual sports.** `ScoreMatcher` is built for two team names with aliases; player-name replay titles ("Sinner v Alcaraz", "UFC 334 Main Card", "Brazil GP") have no strict both-sides-on-the-exact-date rule we can prove. `replaysByEvent` skips individual events.
7. **Not in R2:** following players (follow-up), doubles, F1 time/gap, cricket.

## Tests
DTO parsing per sport on trimmed real fixtures; set-score formatting (tiebreaks, match tiebreak, retirement, walkover); UFC method/round text; F1 podium; golf top 5 and ties; My sports gating (no requests for unchosen sports, default includes them); spoiler modes; no replay for individual events.
