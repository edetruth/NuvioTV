# Sports & Live Dual Screen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Two live games side by side with audio following the highlighted pane.

**Spec:** `docs/superpowers/specs/2026-11-15-sports-live-dual-screen-design.md`

## Global Constraints

- New route `Screen.SportsLiveDual("sports_live_dual")`; items passed via a process-wide holder (like the zap session), not route args.
- Two ExoPlayer instances built with the app's `PlayerMediaSourceFactory`/existing Exo setup used by the trailer pool or player (builder finds the simplest correct construction that honours `proxyHeaders`); `volume = 0f` on the non-highlighted player.
- Keys: Left/Right highlight; Up/Down zap highlighted pane through `SportsLiveZapResolver`; OK → full player with zap session; MENU → swap; Back → leave.
- Pane timeout 15 000 ms to first frame → error state; decoder-init failure on the second player → device-limit message.
- Upstream player files untouched. Strings only in `values/strings.xml`: `sports_live_dual_watch_with` ("Watch with another game"), `sports_live_dual_pick` ("Pick a second game"), `sports_live_dual_stream_error` ("Couldn't play this stream"), `sports_live_dual_device_limit` ("This device can't play two streams at once").
- `source /d/Nuvio/tools/env.sh`; `./gradlew --stop` before each run; one at a time. Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01K2go64gSK4YGNPmEjTxDuk`.

## Review Focus

1. Leaving the screen (Back, Home key, app to background) releases both players — no audio keeps playing.
2. Zapping one pane cancels its previous resolve and doesn't touch the other pane.
3. Second stream failing leaves the first playing with sound.
4. Same game picked twice is prevented in the picker.

---

### Task D1: State model and reducer
Create `ui/screens/sportslive/dual/SportsLiveDualState.kt` (`data class DualPane(item, stream?, status: Loading|Playing|Error|DeviceLimit)`, `data class DualState(left, right, highlighted: Side)`, pure reducer for highlight/swap/zapResult/error) + tests. Commit `feat(sportslive): dual screen state`.

### Task D2: ViewModel and players
Create `.../dual/SportsLiveDualViewModel.kt` (resolves both items with `SportsLiveZapResolver`, per-pane resolve `Job`s, zap per pane) and `.../dual/DualPlayers.kt` (creates/releases two ExoPlayers, applies volume by highlight, first-frame timeout, maps decoder-init errors). Unit-test the ViewModel with a fake resolver. Commit `feat(sportslive): dual screen view model and players`.

### Task D3: Screen, route, entry points
Create `.../dual/SportsLiveDualScreen.kt` (layout per spec, key handling, lifecycle release), route + NavHost registration, long-press menu entry and picker in the section, Left-panel action in the zap overlay, strings. Compile both flavors; `assembleFullDebug`; emulator check (report whether the emulator can decode two streams); screenshots `/d/Nuvio/scratch/screens/dual-*.png`. Commit `feat(sportslive): watch two games side by side`.

### Task D4: Full verification
Full unit suite (3 known failures only), both flavors, emulator walk of Review Focus 1–4.
