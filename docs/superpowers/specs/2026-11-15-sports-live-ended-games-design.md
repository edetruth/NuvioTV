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

## Amendment after first implementation (2026-11-15)

Bulk probing of every Live Now item per load triggered highfly's rate limit ("wait 9 min") on the emulator, blocking playback. Replaced with on-demand probing: only the Live Now item the user settles on for 700 ms in the section is probed (again when its result is older than 2 minutes; re-probes bypass the stream session cache so an end is seen within minutes). Stream fetches from zap/merged cards will mark items ended once the zap resolver calls `recordResult` (not yet wired). Rate-limit/network errors and timeouts never mark ended; a response containing any stream with an info hash (including debrid-pending torrents) counts as alive.
Two more guards: an item whose known kickoff (with time of day) was under 30 minutes ago is never marked ended (links often appear after kickoff), and an ended verdict expires after 3 minutes so the game reappears and is re-checked on the next focus (alive entries have no TTL: they are simply re-probed on focus after 2 minutes).

## Non-goals

Probing Upcoming/Channels. Showing an "Ended" badge (items simply disappear).

## Verification

Unit: ended detection (zero playable → hidden; junk-only → hidden; timeout → visible; error → visible), concurrency cap, cache TTL, focus re-probe. Emulator: a highfly item that shows no streams disappears from Live Now within one load.
