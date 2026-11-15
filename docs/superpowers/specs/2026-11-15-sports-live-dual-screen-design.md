# Sports & Live: dual screen — design

Date: 2026-11-15
Status: approved (user: "lets try implementing that feature as well"; device is the latest onn 4K box; layout is a controller ruling)
Depends on: zap (resolver, session), hide-unplayable-streams

## Intent

Watch two live games at once on the TV.

## Rulings

1. **Layout: side by side.** Two equal 16:9 panes centered on a black background, each with a thin label strip (game name · league). The highlighted pane has an accent border and is the only one with sound. Left/Right switch the highlighted pane. Chosen over main+corner because the user asked for "two games going on at once" with no primary.
2. **Entry:** the Sports & Live long-press menu gains "Watch with another game" on live event cards; it opens a picker of the other Live Now items (same filter as the section) and starts dual screen with the two. The player's Left panel (zap) gains the same action for the current game.
3. **Controls:** OK on the highlighted pane → that game opens full screen in the normal player (with a zap session over the section list). Up/Down on the highlighted pane → zap that pane to the previous/next item of the session list (same resolver). Back → leaves dual screen to where it was opened from. MENU → swap panes.
4. **Engine:** a separate lightweight screen with two Media3 ExoPlayer instances created through the app's existing `PlayerMediaSourceFactory` (so proxy headers work), muted except the highlighted one. Not upstream's `PlayerScreen`/`PlayerRuntimeController` — it stays untouched. No subtitles, no Sources panel in panes (documented).
5. **Failure handling:** a pane whose stream errors or never renders a frame within 15 s shows "Couldn't play this stream" with a hint to try another; the other pane keeps playing. If the device reports too few hardware decoders (second player fails with a decoder init error), show "This device can't play two streams at once" and keep the first.
6. **Lifecycle:** both players released on leave/pause; the screen keeps the TV awake while playing.

## Non-goals

More than two panes. Main+corner layout. Recording. Synced audio mixing.

## Verification

Unit: dual-screen state reducer (highlight, swap, zap per pane, error states). Emulator: both panes render and play (emulator software decoding may limit to one; report what happens), highlight/audio switching, OK expands, Back returns. Real-device check is the user's onn box.
