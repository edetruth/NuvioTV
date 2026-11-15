# Sports & Live Zap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Up/Down/Channel keys zap between games of the list the user came from; Left opens a live-games panel over the video; optional rewind within the live window.

**Architecture:** A process-wide zap session (snapshot of the section's visible list) activates player key hooks for the matching Player route. Each zap resolves a stream for the target item and replaces the Player route. A small overlay composable renders the banner and panel.

**Tech Stack:** Kotlin, Compose (TV), Hilt, coroutines, JUnit4/MockK.

**Spec:** `docs/superpowers/specs/2026-11-15-sports-live-zap-design.md`

## Global Constraints

- Session only for Player routes launched from Sports & Live (screen or Home Live now row) whose `contentId` equals the session's current item id; otherwise upstream behavior unchanged.
- Keys hooked only when session active AND controls hidden: DOWN/CHANNEL_DOWN/MEDIA_NEXT = next; UP/CHANNEL_UP/MEDIA_PREVIOUS = previous; wrap-around; LEFT short press = panel.
- Resolver: `getStreamsFromAllAddons(apiType, id)` → `orderAddonStreams` → `selectAutoPlayStream` with user settings; MANUAL or no match → first `isPlayable` stream; overall timeout 12 000 ms → toast `sports_live_zap_no_stream` ("No stream for %1$s"), stay.
- Banner 3 000 ms; text: name, "league · addon", "%1$d of %2$d" (`sports_live_zap_position`); resolving text `sports_live_zap_switching` ("Switching to %1$s…").
- Panel lists Live Now items (session's filter applied when session tab is LIVE_NOW), current highlighted; Back/Left/Right closes; opening triggers non-forced `SportsLiveRepository.load()`.
- Zap navigation: `streamRouteFor`-equivalent Player navigation with `popUpTo(Screen.Player.route) { inclusive = true }`, `returnToHomeOnBack = false` for section sessions, `true` for Home-row sessions.
- Do not modify player internals other than the listed key branches (Z3) and seek gating (Z5). Strings only in `values/strings.xml`.
- `source /d/Nuvio/tools/env.sh`; `./gradlew --stop` before each run; one Gradle run at a time. Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01K2go64gSK4YGNPmEjTxDuk`.

## Review Focus

1. Zap pressed rapidly several times: only the last target plays; earlier resolutions are cancelled (no stacked navigations).
2. Session list becomes stale (game ended): zapping still works on the snapshot; an item with no stream shows the toast and stays.
3. Player opened for a movie after a sports session: no hooks (session matched by contentId).
4. Back after several zaps returns to the section once, not through every zapped player.
5. Process death / restore: no crash; session absent → upstream behavior.

---

### Task Z1: Zap session model and holder

**Files:** Create `ui/screens/sportslive/SportsLiveZapSession.kt`; Test `app/src/test/java/com/nuvio/tv/ui/screens/sportslive/SportsLiveZapSessionTest.kt`.

**Interfaces — Produces:**
```kotlin
data class SportsLiveZapSession(val items: List<SportsLiveItem>, val index: Int, val tab: SportsLiveTab, val filter: String?, val returnToHomeOnBack: Boolean) {
    val current: SportsLiveItem
    fun next(): SportsLiveZapSession; fun previous(): SportsLiveZapSession   // wrap-around
    fun moveTo(itemKey: String): SportsLiveZapSession?                        // key = "${addonId}|${id}", null if absent
    val positionLabelArgs: Pair<Int, Int>                                     // (index + 1, items.size)
}
object SportsLiveZapSessions { fun start(session: SportsLiveZapSession); fun current(): SportsLiveZapSession?; fun activeFor(contentId: String?): SportsLiveZapSession?; fun update(session: SportsLiveZapSession); fun clear() }
```
- [ ] Tests: next/previous wrap; single-item list stays; moveTo present/absent; `activeFor` matches only the current item id; `clear` empties. RED → implement (`@Volatile` field, like `SourceFallbackHandoff`) → GREEN. Commit `feat(sportslive): zap session`.

### Task Z2: Zap stream resolver

**Files:** Create `ui/screens/sportslive/SportsLiveZapResolver.kt` (`@Singleton`, `@Inject constructor(streamRepository: StreamRepository, playerSettingsDataStore: PlayerSettingsDataStore, addonRepository: AddonRepository)`); Test `.../SportsLiveZapResolverTest.kt`.

**Interfaces — Produces:** `suspend fun resolve(item: SportsLiveItem): Stream?` (null on no playable stream or 12 s timeout); internal `var timeoutMs = 12_000L` for tests.
- [ ] Tests with a fake `StreamRepository` flow: picks autoplay selection; MANUAL → first playable; all externals → null; timeout → null under virtual time; Loading-only flow → null. RED → implement (mirror the player's next-episode selection inputs: settings from `playerSettings.first()`, installed names from enabled addons' `displayName`) → GREEN. Commit `feat(sportslive): zap stream resolver`.

### Task Z3: Start sessions and hook player keys + navigation

**Files:** Modify `ui/screens/sportslive/SportsLiveScreen.kt` / `SportsLiveViewModel.kt` (start a session with `visibleItems`, selected index, tab, filter when playing), Home Live now launch in `NuvioNavHost.kt` (session over current Live Now items from `SportsLiveRepository.latest` bucketed, `returnToHomeOnBack = true`), `ui/screens/player/PlayerScreen.kt` (key branches gated on `SportsLiveZapSessions.activeFor(contentId) != null && !controlsVisible`; new param `onZap: (SportsLiveItem, Stream) -> Unit = { _, _ -> }`), `NuvioNavHost.kt` Player registration (implement `onZap`: build Player route from the stream like the Stream→Player mapping at ≈645–720, navigate with `popUpTo(Screen.Player.route) { inclusive = true }`); create a small `PlayerZapController` (in `ui/screens/sportslive/`) holding the in-flight resolve `Job` so a newer zap cancels the older.
- [ ] Tests: `PlayerZapController` cancels previous resolve; emits navigation only for the last target; no-stream path emits toast event. Compile both flavors. Commit `feat(sportslive): zap between games with Up/Down and channel keys`.

### Task Z4: Banner and Left panel overlay

**Files:** Create `ui/screens/player/SportsLiveZapOverlay.kt` (banner + panel composables); modify `PlayerScreen.kt` to host it only when a session is active; strings per Global Constraints plus `sports_live_zap_panel_title` ("Live now").
- [ ] Implement; compile both flavors; `assembleFullDebug`; emulator: banner after zap shows name and "n of N"; Left opens panel with Live Now items, current highlighted; OK switches; Back closes. Screenshots `/d/Nuvio/scratch/screens/zap-*.png`. Commit `feat(sportslive): zap banner and live games panel`.

### Task Z5: Rewind within the live window (droppable)

**Files:** `ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt` seek gating (≈1508–1594) and the corresponding `PlayerScreen` LEFT/RIGHT/FF/REW branches.
- [ ] When a zap session is active, the engine is ExoPlayer and the current item is dynamic with a seekable window > 30 s, allow seek-back/forward within the window (clamped to live edge); otherwise keep upstream blocking. Unit-test the gating decision as a pure function. Emulator: rewind 30 s on a highfly stream if its window allows; report the observed window. If the change requires restructuring upstream live handling, STOP and report BLOCKED with findings instead. Commit `feat(sportslive): rewind within the live window during zap sessions`.

### Task Z6: Full verification

- [ ] `./gradlew :app:testFullDebugUnitTest` (background) — no new failures beyond the 3 known (2 Trakt credential tests, 1 SearchSuggestions); `assembleFullDebug assemblePlaystoreDebug`.
- [ ] Emulator: Review Focus 1–4; Sources panel after a zap lists the new game's streams; movie playback from Home unchanged (Up shows controls). Report table with screenshots.
