# My Teams and One Card per Game Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Follow teams (pinned, starred, "★ My teams" filter) and merge the same game from different addons into one card with all sources.

**Architecture:** A pure team parser shared by both features; followed teams stored per profile; ordering and filtering in the bucketer/ViewModel; merging in the bucketer; a small stream picker for merged cards in MANUAL autoplay mode.

**Spec:** `docs/superpowers/specs/2026-11-15-sports-live-my-teams-and-merged-games-design.md`

## Global Constraints

- Parser separators (case-insensitive, first that yields two sides): ` vs `, ` vs. `, ` v `, ` at `, ` @ `, ` - `. Normalise: lowercase, strip leading digits+space, drop trailing tokens `fc|sc|cf`, collapse whitespace. Match = equal or whole-word suffix.
- Preference key `sports_live_followed_teams` (Gson list of display names) in `SportsLivePreferencesDataStore`.
- Merge: different addons; both EVENTS non-replay non-channel; team pairs match order-insensitively; sports equal or one null; both live or |startΔ| ≤ 30 min. Primary = earliest in addon order.
- Upstream Stream screen is not modified. Player internals are touched only in T5b (fallback candidate builder and Sources panel loader), a few lines each.
- `source /d/Nuvio/tools/env.sh`; `./gradlew --stop` before each run; one at a time. Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01K2go64gSK4YGNPmEjTxDuk`.

## Review Focus

1. "NFL RedZone", channel names and "Today's Games" never parse as team pairs.
2. A followed team of "Patriots" matches "New England Patriots" but not "Patriots Academy FC" in a different sport only if sports differ — check matching scope.
3. Two different games between the same teams on consecutive days do not merge (time window).
4. Profile switch: followed teams follow the profile.
5. Merged card with all sources junk/empty → toast, no crash.

---

### Task T1: Team parser
Files: create `core/sportslive/SportsLiveTeams.kt` (`data class TeamPair(val home: String, val away: String)` display; `fun parse(name: String): TeamPair?`; `fun normalise(team: String): String`; `fun matches(a: String, b: String): Boolean`); test with fixture names from `app/src/test/resources/sportslive/*.json`. TDD; commit `feat(sportslive): parse team names from events`.

### Task T2: Followed teams store and ordering
Files: modify `SportsLivePreferencesDataStore.kt` (`followedTeams: Flow<Set<String>>`, `follow(team)`, `unfollow(team)`); `SportsLiveItem` gains `teams: TeamPair?` (set in `describe`) and `followed: Boolean` (computed in the ViewModel/repository from the followed set); bucketer/ViewModel ordering: followed first, then existing order; filter row's leading "★ My teams" pseudo-filter (string `sports_live_filter_my_teams`) on Live Now/Upcoming/Replays when any followed item exists; Home Live now row orders followed first. Tests for store round-trip and per-profile isolation, ordering, filter. Commit `feat(sportslive): follow teams and pin their games`.

### Task T3: Long-press menu, star, settings card
Files: `SportsLiveScreen.kt`/`SportsLiveCards.kt` (long-press menu with Follow/Unfollow per team + Details; star on followed cards), `SportsLiveSettingsContent.kt` + VM ("My teams" card; OK unfollows), strings. Compile both flavors; emulator check with screenshots `/d/Nuvio/scratch/screens/teams-*.png`. Commit `feat(sportslive): follow from the card menu and manage teams in settings`.

### Task T4: Merge same game across addons
Files: `SportsLiveItem` gains `alternates: List<SportsLiveItem> = emptyList()`; bucketer merge step after per-addon dedupe (needs addon order: pass `addonOrder: List<String>` into `bucket`, defaulting to first-seen order); card subtitle "N sources" (`sports_live_sources_count`). Tests for the merge rules (positive and each negative in Review Focus 3, channel, replay, different sport). Commit `feat(sportslive): one card per game across addons`.

### Task T5: Play merged cards
Files: `SportsLiveZapResolver` gains `resolveAll(item): List<Stream>` (primary + alternates in parallel, ordered by addon order) and `resolve` tries primary then alternates; section OK on a merged card: non-MANUAL → play best across sources; MANUAL → new `SportsLiveStreamPickerDialog` (streams grouped by addon) → play. Tests for resolveAll ordering and the MANUAL/auto decision. Emulator check if two addons carry the same game at test time (else report UNCLEAR with the unit evidence). Commit `feat(sportslive): play merged games from any source`.

### Task T5b: Failover and Sources across merged sources
Files: create `ui/screens/sportslive/SportsLiveExtraSources.kt` (`object` holder: `register(contentId, items: List<SportsLiveItem>)`, `extraStreamsFor(contentId): suspend () -> List<Stream>` resolved via `SportsLiveZapResolver.resolveAll` minus the primary, cached per contentId, `clear(contentId)`); modify `PlayerRuntimeControllerSourceFallback.kt` (`nextSourceFallbackCandidate`: append extra streams after the current candidates, skipping already-tried keys) and `PlayerRuntimeControllerStreams.kt` (`loadSourceStreams`: append extra streams grouped under their addon names); register on play of a merged card and on zap to one; clear on player exit. Keep each upstream edit to a few lines calling into the holder. Tests: holder register/clear; fallback candidate order with extras (pure function extracted if needed). Emulator: force a failure on a merged game and observe fallback to another addon's stream (UNCLEAR if no merged game is available at test time). Commit `feat(sportslive): fail over to other addons' streams for merged games`.

### Task T6: Full verification
Full unit suite (only the 3 known failures), both flavor builds, emulator walk of Review Focus 1–5 where data allows; report table.
