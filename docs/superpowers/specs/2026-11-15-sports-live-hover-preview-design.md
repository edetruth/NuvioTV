# Sports & Live: hover preview — short design

Date: 2026-11-15
Status: approved (user: "Lets go with your recommendation and you've got the exact idea")
Depends on: dual screen (lightweight Exo player construction), zap resolver (best-stream selection), ended-games probe (stream fetch on settle), junk filter

## Intent

Instead of opening each game to see what's on, resting on a card for ~2 seconds plays that game's best stream as a live video in the details pane.

## Rulings

1. **Where:** in the details pane, replacing the 16:9 art. Never full screen on hover.
2. **When:** after the selection has rested on the same card for 2 000 ms. Moving away stops and releases the preview immediately.
3. **Tabs:** Live Now and Channels only. Upcoming (nothing to play) and Replays (spoilers) never preview.
4. **Sound:** always muted. No unmute control; OK gives full screen with sound.
5. **Stream:** the same selection zap uses (`SportsLiveZapResolver.resolve`), which reuses the stream fetch the ended-games probe already made on settle (no extra addon requests in the common case). If no playable stream resolves within the resolver timeout, the pane keeps the art and nothing is shown as an error.
6. **OK hand-off:** OK on a previewing card opens the full player with the already-resolved stream (no stream list), with a zap session as usual; the preview player is released after the full player starts.
7. **Setting:** Settings → Sports & Live gains "Preview games on hover" (default on).
8. **Resources:** at most one preview player at a time; it pauses when the screen is not visible and is released on leave; the pane shows a small "Preview" label while playing.

## Non-goals

Preview on Home's Live now row. Previews in Upcoming or Replays. Sound in preview.

## Verification

Unit: preview state machine (settle 2 s → start; move → stop; tab gating; setting off; resolver null → no preview), hand-off uses the resolved stream. Emulator: resting on a highfly Live Now card shows video in the pane within a few seconds (emulator decoding permitting), moving away stops it, OK opens full screen immediately.
