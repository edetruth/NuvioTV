# Scoreboard Implementation Plan (SB1, SB2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (one task at a time, implemented inline).

**Goal:** The Scoreboard (#23): a stadium-board layout of the same games as Live Now and Upcoming, grouped LIVE → STARTING SOON → FINAL, with the focused game on the right (muted preview, big scoreline, a situation line from ESPN's `situation`, Watch / Watch with another game / Game stats).

**Issues:** #23 (Scoreboard); follow-ups #57 (Watch with another game → dual screen), #24 (Timeline shares the layout switch).
**Design:** `docs/superpowers/designs/2026-11-16-sports-live-views/README.md` §2 Scoreboard, "Shared visual language", "Spoilers, delay and keys", "Built components", "Decisions"; `Scoreboard.dc.html`.
**Builds on:** scores S1–S6 (`core/sportslive/scores/`), Results R1, Game page G1, in-player games panel (`SportsLiveZapPanel.kt`), #60 ended-game detection (FINAL group).

## Decisions (user, 2026-11-17)
- Scoreboard and Timeline are a **layout switch** (Cards · Scoreboard · Timeline) inside Live Now and Upcoming: same games, different layout. The Channels guide (#25) stays its own tab.
- **Situation line: yes.** Parse ESPN scoreboard `competitions[].situation` (down & distance, count and baserunners, power play when given). Same feed, no extra request.
- **Finals keep their score by default** (Scores = Show).

## Global Constraints
- Precision: a score, and so a situation, shows only for a strict match with the card's sides on the event one way round (`ScoreChips.of`). An unmatched game never shows a situation.
- Spoilers: the situation follows the score exactly. Hidden whenever the chip is Hidden (Hide until focused, before the 1 s reveal) or absent (Scores = Off, unmatched, upcoming, postponed). It shows only while the chip is Live.
- Delay: the situation is part of what `ScoreDelay` holds back (with score, clock, state, linescores, scorers); a game first seen live shows none until the delay has passed. Every surface reads the delayed snapshot from `LiveScoresRepository`; no view fetches its own.
- Requests: none new. Situation comes from the scoreboard response already polled.
- Strings only in `values/strings.xml`; sports-scoped code only.
- Builds: `source /d/Nuvio/tools/env.sh`; ≥6 GB free, no other java; one Gradle task at a time with `./gradlew --stop` around each.
- Commit trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` + `Claude-Session:` line.

## Task SB1 — situation data (now)
1. **DTO:** `EspnCompetitionDto.situation` → `EspnSituationDto`, every field nullable: `possession` (team id), `down`, `distance`, `shortDownDistanceText`, `downDistanceText`, `possessionText`, `isRedZone`; `balls`, `strikes`, `outs`, `onFirst`/`onSecond`/`onThird` (read as `Any?`: a boolean or a runner object both mean occupied), `batter`/`pitcher` (`athlete.shortName`/`displayName`); `lastPlay.text`. A malformed situation must not cost the game its score.
2. **Model:** `GameSituation` on `ScoreSnapshot.situation` (null unless the game is IN and something was given): football possession side, down & distance, yard line, red zone; baseball balls/strikes/outs, runners on 1st/2nd/3rd, batter, pitcher; `lastPlay` cut to ~80 chars at a word. Hockey power play: only if ESPN's scoreboard carries it (not seen in NHL feeds or summaries so far → nothing). Soccer: ESPN sends no `situation` (checked on 22 live soccer/all games) → nothing. Basketball: usually `lastPlay` only.
3. **Formatter:** `GameSituations.situationLine(situation, snapshot, strings)` → "SEA ball · 2nd & 6 · LV 34" (red zone adds "Red zone"), "Bot 7 · 2 out · 1st & 3rd" (inning half from ESPN's clock text; "Runner on 2nd" for one runner, "Bases loaded" for three, nothing for empty bases), "" when nothing. Words come from a `GameSituationStrings` (tests pass English; SB2 adds the string resources).
4. **Spoiler gate:** `GameSituations.of(item, snapshot, mode, revealed)` returns the situation only when `ScoreChips.of(...)` is `ScoreChip.Live`.
5. **Delay:** `ScoreDelay` treats `situation` like the score (held back, withheld on first sight).
6. **Tests:** DTO parsing on a trimmed real live college basketball scoreboard (lastPlay) and labelled hand-trimmed NFL and MLB samples (ESPN's shape; no live NFL/MLB on 2026-11-17); formatter per sport; empty when absent; null when hidden, Off, unmatched, upcoming, final; delay holds the situation.

## Task SB2 — Scoreboard UI (after #60 lands)
1. **Layout switch:** a pill row Cards · Scoreboard · Timeline at the top of Live Now and Upcoming (Timeline greyed until #24), remembered per tab (DataStore, like other Sports & Live settings). The Channels tab has no switch.
2. **Left column (1000 px):** grouped rows **LIVE** (red header) → **STARTING SOON** → **FINAL**. Reuse the in-player panel's grouping (`SportsLiveZapPanel`: Live by elapsed, Starting soon within 3 h by kickoff, followed teams first inside a group, finished games keep their start-time place under Hide until focused); FINAL comes from #60's ended state.
   - Row: league tag, two team lines in the card's order (ESPN abbreviation/name via `GameArt`, followed team's gold ★), stacked scores, the clock (ESPN's text). Starting soon: kickoff in local time + "in 1 h 44 m". Final: the chip text ("FT 2–1", "Score hidden").
   - Unmatched games: names from the card, no score, no situation.
3. **Right column (focused game):** muted preview after 2 s rest (the hover preview, its setting and Data saver rule), LIVE tag, "Preview · muted · OK for full screen"; big scoreline (S5 `ScoreDetailsBuilder.scorelineText`), status line (clock · source count; soccer scorers line), the **situation line** (`GameSituations`), then buttons **Watch** (best stream / Stream screen as cards do today), **Watch with another game** (Live only; the existing dual screen, see #57), **Game stats** (Game page, strict match only).
4. **Spoilers, delay, keys:** the same reveal as cards (1 s focus under Hide until focused; Off shows no scores and no situation); everything from the delayed snapshot. ↑ ↓ move between games (groups skipped over), → into the buttons, OK watches, hold OK / Menu = the card menu, Back returns to the switch.
5. **Tests:** grouping and order (live/soon/final, followed first), hidden rows never reveal state, situation hidden with the score, layout choice remembered per tab, focus restore on Back.

## Follow-ups
- #57: "Watch with another game" opens the dual screen with the focused game preselected.
- Idea: a compact Scoreboard strip on Home (live rows only, chip + situation line) reusing the SB2 row.
- Idea: show the last play under the situation line on the focused game (already parsed; same spoiler/delay rule).
