# Live Scores v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Live and final scores for Sports & Live games, starting with the user's followed teams, with spoiler protection and a score delay.

**Spec:** `docs/superpowers/specs/2026-11-16-sports-live-scores-v1-design.md` (issue #17 and its comments). Also folds in #41 (score delay). Research: `D:\Nuvio\tasks\research-live-scores.md`. Prior art: homelessbrian/NuvioPlusIPTV (GPL-3.0) `livetv/sports/` (ESPN feeds, SportsScoreDelay).

## Rollout order (user-approved)
1. **Results first** (yesterday and earlier, finals only): easiest to match, no clock, no polling; proves the matcher on real data.
2. **My Teams live scores:** poll only leagues of followed teams' games.
3. **Everything else:** all matched games on Live Now / Upcoming.

## Global Constraints
- Source: ESPN public site API (`site.api.espn.com/apis/site/v2/sports/{sport}/{league}/scoreboard?dates=YYYYMMDD`), keyless, behind a `ScoreProvider` interface. Tolerant DTOs (all fields optional). Any failure → no score, never an error UI.
- Generic User-Agent; requests carry only league + date. No user data.
- Matching reuses `SportsLiveTeams.parse` + `SportsLiveTeamAliases` (#34); both sides must match; ±3 h window; precision over recall. ESPN `abbreviation` / `shortDisplayName` feed the alias set.
- Polling: only while Sports & Live or Home Live now is visible; 30 s while a matched game is live in that league; 5 min when only upcoming; stop when all final. One request per league feed per interval (shared, coalesced, jittered). Backoff 30 s → 5 min; hide that league's chips after 3 failures.
- Disk cache: finals and past days for 7 days.
- **Score delay (#41):** a setting (Off / 15 s / 30 s / 60 s / 90 s) that holds back score/clock changes by the chosen delay everywhere scores appear.
- **Spoilers:** setting Show (default) / Hide until focused / Off. Results always respect it.
- Our own drawn game cards: team names, local kickoff time, league, score chip, team logos from ESPN when available (loaded by URL, cached). Channels keep addon art.
- Strings only in values/strings.xml. Fixtures: trimmed real ESPN responses only (no addon data). Fixture hosts other than ESPN → example.invalid.
- Memory-sequenced builds; `./gradlew --stop` around each task. Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01K2go64gSK4YGNPmEjTxDuk`.

## Review Focus
1. No wrong score on a card (mis-match). A wrong score is worse than none.
2. No spoilers with "Hide until focused"; score delay applies everywhere.
3. Request volume bounded; nothing polls when the screen isn't visible.
4. Failure-silent: ESPN down/changed → app unchanged, no crash.

### Task S1: Score provider, league map, matcher
`core/sportslive/scores/`: `ScoreProvider`, `EspnScoreProvider` (Retrofit/OkHttp, tolerant Moshi DTOs), `EspnLeagueMap` (sport/keyword → league slugs; soccer fallback `soccer/all`), `ScoreMatcher` (card → event, per-day match cache). Unit tests with trimmed real ESPN fixtures: NBA, NFL, soccer incl. AFCON qualifier abbreviations, a miss, a women's/U21 non-match.

### Task S2: Repository, polling, disk cache, score delay
`LiveScoresRepository` (singleton, `StateFlow<Map<CardKey, ScoreSnapshot>>`), lifecycle-gated poller per league feed, backoff, disk cache for finals, `ScoreDelay` (from #41). Settings: Scores (Show / Hide until focused / Off), Score delay. Virtual-time tests for cadence, backoff, delay.

### Task S3: Results view (first user-visible step)
Results on Sports & Live: Yesterday … 7 days ago; leagues of followed teams and watched leagues; final score rows; spoiler setting respected; opens Replays for the game when an addon has one.

### Task S4: Score chips + drawn game cards (My Teams first)
Score chip on cards (live: red dot + clock + score; final: FT). Our own drawn card for games (team names, local time, league, logos); channels keep addon art. Enabled for followed teams' games first, then all matched games behind the same code path.

### Task S5: Details pane + in-player panel
Details pane scoreline + linescore row + soccer scorers (from the scoreboard, no extra call). In-player games panel rows show score/clock; panel grouped Live now (by elapsed) → Starting soon → rest.

### Task S6: Full verification
Full unit suite (known-flaky exclusions only), both flavors, review of Review Focus 1–4; the user verifies on the TV.
