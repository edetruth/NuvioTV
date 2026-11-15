# Sports & Live: hide ended "live" games — short design

Date: 2026-11-15
Status: approved (user asked "is that something we can fix on our end?"; approach is the controller's ruling)
Depends on: hide-unplayable-streams (junk must be removed before counting streams)

## Problem

highfly's Sports Streams keeps games marked "LIVE NOW" after they end; opening one shows no streams. Its catalog gives no end time and often no start time, so the bucketer cannot tell.

## Ruling

Use stream availability as the signal for **Live Now only**:
- After each section/Home load, probe streams for Live Now items with `StreamRepository.getStreamsFromAllAddons(apiType, id)` (junk already filtered), max 3 concurrent, 8 s timeout each, results cached per `addonId|id` for 10 minutes in the repository.
- An item whose probe finishes with **zero playable streams** (`StreamAutoPlaySelector.isPlayable` false for all) is marked ended and excluded from LIVE_NOW, the Home Live now row, and zap/dual lists. Timeouts and errors do **not** mark ended (stay visible).
- Focusing a card in the section re-probes that item if its cache entry is older than 2 minutes.
- Upcoming, Replays and Channels are never probed or hidden by this rule.
- Probes share the stream repository's session cache, so a later OK on a probed game opens instantly.

## Non-goals

Probing Upcoming/Channels. Showing an "Ended" badge (items simply disappear).

## Verification

Unit: ended detection (zero playable → hidden; junk-only → hidden; timeout → visible; error → visible), concurrency cap, cache TTL, focus re-probe. Emulator: a highfly item that shows no streams disappears from Live Now within one load.
