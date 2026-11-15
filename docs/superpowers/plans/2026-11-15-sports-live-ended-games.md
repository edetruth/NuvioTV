# Hide Ended Live Games Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Drop "live" games whose addon no longer returns any playable stream.

**Spec:** `docs/superpowers/specs/2026-11-15-sports-live-ended-games-design.md`

## Global Constraints

- Probe only LIVE_NOW items; concurrency 3; per-probe timeout 8 000 ms; cache TTL 600 000 ms keyed `addonId|id`; focus re-probe when entry older than 120 000 ms.
- Ended = probe completed and no stream passes `StreamAutoPlaySelector.isPlayable` (make it `internal`/reachable if private). Timeout/error ≠ ended.
- Excluded from LIVE_NOW, Home Live now row, zap and dual lists; other tabs untouched.
- `source /d/Nuvio/tools/env.sh`; `./gradlew --stop` before each run; one at a time. Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01K2go64gSK4YGNPmEjTxDuk`.

### Task E1: Probe, cache, exclusion, focus re-probe
Files: create `data/repository/SportsLiveStreamProbe.kt` (`@Singleton`; `suspend fun probe(items: List<SportsLiveItem>)`, `val ended: StateFlow<Set<String>>`, `fun reprobeIfStale(item)`), wire into `SportsLiveRepositoryImpl` (launch probe after each successful load without blocking it; `liveNow()` excludes ended keys) and `SportsLiveViewModel` (exclude ended keys from LIVE_NOW; `selectItem` triggers `reprobeIfStale`). Tests with a fake `StreamRepository` per the spec's cases plus concurrency cap and TTL under virtual time. Emulator check with a highfly item lacking streams (report UNCLEAR if none at test time). Commit `feat(sportslive): hide live games whose streams are gone`.
