# Sports & Live Hover Preview Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Muted live preview in the details pane after resting 2 s on a Live Now or Channels card; OK hands off to full screen with the same stream.

**Spec:** `docs/superpowers/specs/2026-11-15-sports-live-hover-preview-design.md`

## Global Constraints

- Settle delay 2 000 ms; tabs LIVE_NOW and CHANNELS only; muted; one preview player max; released on move/leave/pause.
- Stream from `SportsLiveZapResolver.resolve` (no forceRefresh); null → keep art silently.
- Setting key `sports_live_hover_preview` (default true) in `SportsLivePreferencesDataStore`; strings `sports_live_setting_hover_preview` ("Preview games on hover"), `sports_live_preview_label` ("Preview").
- Player built with the dual-screen lightweight Exo construction (honours proxy headers).
- `source /d/Nuvio/tools/env.sh`; `./gradlew --stop` before each run; one at a time. Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01K2go64gSK4YGNPmEjTxDuk`.

## Review Focus

1. Rapid scrolling never starts a preview and never leaks players.
2. Returning from full screen does not resume a stale preview with sound.
3. Ended-games probe and preview do not double-fetch.

### Task H1: Preview state machine + setting
Pure state machine in `ui/screens/sportslive/preview/SportsLivePreviewState.kt` (inputs: focused item, tab, setting, visibility, resolver result; outputs: Idle / Resolving(item) / Playing(item, stream)) with virtual-time tests; setting in the prefs store and Settings toggle. Commit `feat(sportslive): hover preview state and setting`.

### Task H2: Pane player + hand-off
Pane composable hosting the lightweight player (muted, "Preview" label), wired to the state machine in `SportsLiveViewModel`; OK on a playing preview navigates to Player with the resolved stream and a zap session. Compile both flavors; emulator check with screenshots `/d/Nuvio/scratch/screens/hp-*.png`. Commit `feat(sportslive): muted live preview in the details pane`.
