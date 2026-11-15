# Sports & Live: zap while watching — design

Date: 2026-11-15
Status: approved (user said "Continue until completion"; the open calls below are the controller's, recorded as rulings)
Depends on: Sports & Live section (complete), hide-unplayable-streams (in progress)

## Intent

The user flips between games. Today every switch means Back → list → OK → stream list → play. They asked for:

1. **Down / Up** while watching → the next / previous game **in the exact list they started from** (same tab, same filter: "If I'm watching a game from nfl filter, only nfl games").
2. **Left** → a panel over the video listing **games going on right now**; OK switches, without leaving the player.
3. **Switching streams** from inside the player when one fails (exists upstream: Sources panel + source fallback) must keep working and follow the current game.
4. **Rewind** within a live game, built as a separate last step so it can be dropped if it conflicts with upstream updates.

## Facts from recon (upstream player)

- `PlayerScreen.onKeyEvent` (≈724–1018): with controls hidden, UP and DOWN call `OnToggleControls`; LEFT/RIGHT preview-seek but every seek returns early when `isLive`; CHANNEL_UP/DOWN and MEDIA_NEXT/PREV are unhandled when there are no chapters (always, for live).
- Player identity (`title`, `contentId`, `contentType`, `videoId`, poster, …) is `val` from `PlayerNavigationArgs`; only stream-level fields are mutable. In-place identity swap would touch many upstream files.
- `NuvioNavHost` already re-navigates to Player with `popUpTo(Screen.Player.route) { inclusive = true }` for next-episode style flows.
- `StreamRepository.getStreamsFromAllAddons(type, videoId)` + `StreamAutoPlaySelector.selectAutoPlayStream(...)` are usable outside the Stream screen (the player's next-episode path already does this).
- Live detection: `LivePlaybackUiPolicy` (player-reported live, or type `channel`); seeking blocked when live.

## Approaches considered

| | Approach | Trade-off | Outcome |
|---|---|---|---|
| A | **Replace the Player route** for each zap: resolve a stream for the target item, then navigate to a new Player route with `popUpTo(Player, inclusive)` | Small, contained upstream edits; Sources panel and fallback follow the new game for free because a fresh player has the new identity; a brief re-buffer per zap (unavoidable anyway) | **Chosen** |
| B | In-place swap: make player identity mutable and swap media like `switchToEpisodeStream` | Smoother UI but edits the core of upstream's player in many places → merge conflicts on every Nuvio update | Rejected |
| C | Zap only via the Left panel, no Up/Down | Ignores an explicit request | Rejected |

## Decisions (rulings)

1. **Zap session.** When the user plays from the Sports & Live screen, the screen hands the player a *zap session*: the ordered list of items visible at that moment (tab + filter), the index of the item being played, the tab, and the active filter. Stored in a process-wide holder in `ui/screens/sportslive/` (same pattern as upstream's `SourceFallbackHandoff`), keyed by the item's id, so it only activates for a Player route whose `contentId` matches the session's current item. Launching from Home's Live now row starts a session over the Live Now list (no filter). Launching from anywhere else: no session, the player behaves exactly as upstream.
2. **Keys, only while a session is active and the controls are hidden:** DOWN / CHANNEL_DOWN / MEDIA_NEXT → next item; UP / CHANNEL_UP / MEDIA_PREVIOUS → previous item; wrap-around at the ends; LEFT (short press) → live games panel. With controls visible, or with no session, every key behaves as upstream. Showing the controls stays possible via OK→(pause) or the panel's close; MENU keeps upstream behavior.
3. **Picking the stream on zap.** Fetch streams for the target with `getStreamsFromAllAddons(item.apiType, item.id)` (junk already removed by the filter), order with `orderAddonStreams`, select with `selectAutoPlayStream` using the user's autoplay settings; if the mode is MANUAL or nothing matches, use the first playable stream. If nothing is playable within 12 s, show a toast "No stream for <name>" and stay on the current game. Zap navigation passes `returnToHomeOnBack = false` so Back still returns to the section.
4. **Zap banner.** After each zap, show a 3-second banner at the top: game name, "· league · addon", and "3 of 12". While resolving, the banner shows "Switching to <name>…".
5. **Left panel.** A semi-transparent panel on the left third of the screen over the video, listing **Live Now** items (respecting the session's sport filter when the session came from Live Now; otherwise all live items), current game highlighted, D-pad Up/Down to move, OK to switch (same mechanism as decision 3), Back/Left/Right closes. Live items come from `SportsLiveRepository.latest` filtered/bucketed exactly as the section does (reuse the bucketer). Opening it triggers a non-forced repository load.
6. **Sources and fallback** need no change beyond being reachable: a zapped-to game is a fresh player with that game's identity. Verification covers it.
7. **Rewind (separate, last task).** For a session-backed live playback on ExoPlayer, allow LEFT-hold / FF / REW seeking within the stream's own live window (`Player.isCurrentMediaItemDynamic` + seekable range) instead of blocking all seeks; keep upstream blocking when no window exists or on MPV. Gate the change behind the zap session so non-sports live (type `channel`) is untouched. If this cannot be done without restructuring upstream's live gating, the task is dropped and reported.

## Non-goals

Picture-in-picture or multiview. Zap for movies/series. Changing upstream player UI beyond the banner and panel. Persisting the session across process death.

## Files (expected)

New: `ui/screens/sportslive/SportsLiveZapSession.kt` (holder + pure next/prev logic), `ui/screens/sportslive/SportsLiveZapResolver.kt` (stream resolution), `ui/screens/player/SportsLiveZapOverlay.kt` (banner + Left panel). Touched upstream: `PlayerScreen.kt` (key branches gated on session; host the overlay), `NuvioNavHost.kt` (Player route `onZap` navigation), `SportsLiveScreen`/`NuvioNavHost` launch sites (start a session). Rewind: `PlayerRuntimeControllerPlaybackEvents.kt` seek gating only.

## Verification

Unit: session next/prev with wrap and filter, resolver selection incl. MANUAL fallback and no-stream timeout, session matching by contentId. Emulator: start a highfly game from Live Now filtered to one sport, Down/Up cycle only that sport with banner; Left opens panel, OK switches; Sources panel lists the new game's streams; Back returns to the section; launching a movie from Home shows upstream behavior (Up shows controls).
