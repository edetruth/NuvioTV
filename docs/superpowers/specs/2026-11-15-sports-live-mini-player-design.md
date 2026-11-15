# Sports & Live: mini player and picture-in-picture — design

Date: 2026-11-15
Status: approved (user: "Over other apps as well. If we have it inside nuvio lets extend it"; details are controller rulings)
Depends on: zap (session), dual screen (lightweight player `DualPlayers`/Exo construction is reused)

## Intent

Keep a live game playing while doing other things: in a corner inside Nuvio, and over other apps when leaving Nuvio.

## Rulings

1. **Scope:** only playback that came from Sports & Live (an active zap session). Movies/series keep upstream behavior.
2. **In-app mini player:** pressing Back from a zap-session player closes the full player as today, but a mini player (bottom-right, 24% of screen width, 16:9, rounded, subtle shadow) starts the same stream (URL + headers) in a lightweight Exo instance hosted above the NavHost in `MainActivity`. It is not focusable (TV focus to a corner overlay is unreliable). A label under it reads "<game> · Play/Pause to expand".
3. **Expand / stop:** MEDIA_PLAY_PAUSE or MEDIA_PLAY while the mini player is active → opens the full player for the session's current item (resolving a fresh stream with the zap resolver; falls back to the mini player's stream). The Sports & Live screen shows a "Now playing" card above the list with Expand and Stop buttons. Starting any other playback (any Player route) stops the mini player. Profile switch stops it.
4. **Audio:** the mini player plays with sound; the dual screen and full player stop it on entry.
5. **System picture-in-picture:** if `PackageManager.FEATURE_PICTURE_IN_PICTURE` is present, `MainActivity` declares `android:supportsPictureInPicture="true"` and `android:resizeableActivity` as needed, and on `onUserLeaveHint` (Home key) while a sports full player or mini player is active, calls `enterPictureInPictureMode` with 16:9 params. While in PiP, the activity renders only the video surface of whichever player is active (full player's surface, or the mini player's at full size). Leaving PiP restores the previous UI. Closing the PiP window stops playback. Without the feature, Home behaves as upstream.
6. **Lifecycle:** mini player released on stop/close, on app finish, and after 2 hours without interaction (battery/bandwidth guard).

## Non-goals

PiP for non-sports playback. Remote-control of the PiP window beyond the system's own controls. Background audio without video.

## Risks

PiP on Google TV devices is OEM-dependent; the onn box is the real test. Upstream `MainActivity` and manifest are touched (PiP declaration, `onUserLeaveHint`, mini player host) — kept to small, isolated additions.

## Verification

Unit: mini-player state machine (start on Back from session player; stop on other playback/profile switch; expand), PiP eligibility decision. Emulator: Back from a sports game shows the mini player while browsing; Play/Pause expands; Now playing card Stop works; starting a movie stops it; Home → PiP window over the launcher (emulator supports PiP on API 36 TV if the feature is present; report).
