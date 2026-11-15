# Sports & Live Mini Player and PiP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Keep sports games playing in a corner inside Nuvio and in system picture-in-picture over other apps.

**Spec:** `docs/superpowers/specs/2026-11-15-sports-live-mini-player-design.md`

## Global Constraints

- Only for zap-session playback. Mini player: bottom-right, width 24% of screen, 16:9, not focusable; label string `sports_live_mini_hint` ("%1$s · Play/Pause to expand"); "Now playing" card strings `sports_live_now_playing`, `sports_live_expand`, `sports_live_stop`.
- Expand on MEDIA_PLAY_PAUSE / MEDIA_PLAY while mini active; stop on any other Player route, profile switch, Stop, 2 h idle.
- PiP only when `FEATURE_PICTURE_IN_PICTURE`; aspect 16:9; triggered by `onUserLeaveHint` while a sports full player or mini player is active; PiP UI renders only the active video.
- Reuse the dual-screen Exo construction for the mini player. Upstream player internals untouched; `MainActivity` and the manifest get small isolated additions.
- `source /d/Nuvio/tools/env.sh`; `./gradlew --stop` before each run; one at a time. Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01K2go64gSK4YGNPmEjTxDuk`.

## Review Focus

1. No double audio: full player, dual screen and mini player never play sound at the same time.
2. Mini player never steals D-pad focus from the screen underneath.
3. Home key with no sports playback → upstream behavior (no PiP).
4. Rotating through profile switch / process death leaves no orphan player.

---

### Task M1: Mini player state + host
Create `ui/screens/sportslive/mini/SportsLiveMiniPlayerState.kt` (pure state machine + tests) and `SportsLiveMiniPlayerHost.kt` (composable hosted in `MainActivity` above the NavHost; single Exo instance via the dual-screen construction). Start on Back from a zap-session Player route (NavHost Player `onBackPress` path), stop on other Player routes and profile switch. Commit `feat(sportslive): mini player inside Nuvio`.

### Task M2: Expand and Now playing card
Media-key handling in `MainActivity` (only when mini active) → full player for the session item; "Now playing" card at the top of the Sports & Live screen with Expand/Stop. Emulator check with screenshots `/d/Nuvio/scratch/screens/mini-*.png`. Commit `feat(sportslive): expand or stop the mini player`.

### Task M3: System picture-in-picture
Manifest `supportsPictureInPicture` on `MainActivity`; `onUserLeaveHint` → `enterPictureInPictureMode(PictureInPictureParams 16:9)` when eligible; `onPictureInPictureModeChanged` → render only the active video; closing PiP stops playback. Pure eligibility function with tests. Emulator: press Home during a sports game, report whether a PiP window appears. Commit `feat(sportslive): picture-in-picture over other apps`.

### Task M4: Full verification
Full unit suite (3 known failures only), both flavors, emulator walk of Review Focus 1–4.
