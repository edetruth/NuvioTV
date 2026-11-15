# Hide unplayable streams — short design

Date: 2026-11-15
Status: approved by user 2026-11-15
Part of: the next round (junk streams → zap → My Teams → one card per game)

## Intent

Addons such as highfly add "streams" that cannot play: "CDN (Premium) — Upgrade to watch" with `url = https://www.google.com`, and "Tip: Use an external player" with `url = https://sports.example.invalid/health`. They clutter the stream list and, worse, the automatic picker treats them as playable today (`Stream.isExternal()` is false because the link is in `url`), so autoplay, source fallback and the coming zap feature can land on them. The user also wants to switch streams from inside the player when one fails; that already exists upstream (Sources panel, source fallback) and must keep working without junk entries.

## Rule

A stream is unplayable junk when its `url` is an `http`/`https` link whose path is empty, `/`, or `/health` (case-insensitive, query ignored). Real media links always have a file or playlist path. Amended 2026-11-15 (Nebula Sports test addon): a path ending in `.html` or `.htm`, or equal to `/health/`, is also junk (web pages never play); streams with `clientResolve.infoHash` are never junk. Torrents (`magnet:`/`infoHash`), YouTube (`ytId`), debrid and server streams, and `externalUrl`-only entries (explicit browser links such as PenguPlay's "support the project") are never classed as junk.

## Where

One pure predicate `isUnplayableJunk(stream)` applied where addon stream DTOs become domain streams in `StreamRepositoryImpl` (both the per-addon stream fetch and the inline-meta streams), so the Stream screen, its autoplay, the in-player Sources panel, next-episode autoplay and source fallback all see filtered lists. The Stream screen's separate `getEmbeddedStreamsFromMeta` path applies the same predicate. App-wide, no setting.

## Approaches considered

| | Approach | Outcome |
|---|---|---|
| A | URL-shape predicate at the repository boundary | **Chosen**: one place, covers every consumer, language-independent |
| B | Match words like "Upgrade"/"Tip" in names | Rejected: brittle, language-dependent |
| C | Filter only in the selector / Stream screen | Rejected: misses Sources panel and fallback |

## Non-goals

No settings toggle. No changes to how `externalUrl` entries are shown. No changes to the Sources panel UI itself.

## Verification

Unit tests on the predicate and on the repository mapping using the real highfly and Sports Replay stream bodies (junk removed; Leaf m3u8, TorBox and magnet kept); selector test that FIRST_STREAM can no longer pick junk. Emulator: a highfly game's stream list and in-player Sources panel show no junk; switching source in the player works; a forced failure falls back to a real stream.
