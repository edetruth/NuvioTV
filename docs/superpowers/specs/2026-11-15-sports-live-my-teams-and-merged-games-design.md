# Sports & Live: My Teams and one card per game — design

Date: 2026-11-15
Status: approved (user: "Continue until completion"; open calls are the controller's rulings)
Depends on: Sports & Live section, filter row + channel groups follow-on (filter row is reused), zap (resolver reused)

## Intent

After zap, the user asked for **My Teams** (follow teams; their games come first) and **one card per game** (when several addons carry the same game, show one card with every source). Both need to know *which teams* an event is about, so they share one team parser.

## Shared: team parsing

A pure `SportsLiveTeams.parse(name: String): TeamPair?` splits event names on ` vs `, ` vs. `, ` v `, ` at `, ` @ `, ` - ` (case-insensitive, first separator that yields two non-empty sides). Each side is normalised for matching: lowercase, strip leading ranking numbers ("2 Utah Utes" → "utah utes"), strip "fc"/"sc"/"cf" suffix tokens, collapse whitespace. Display names keep the original text minus rankings. Channels and names without a separator return null. Tests use real fixture names ("Tenerife vs Ceuta", "2 Utah Utes at 23 Arizona Wildcats", "Detroit Lions vs New England Patriots", "NFL RedZone" → null).

A team *matches* another when the normalised strings are equal or one is a whole-word suffix of the other ("patriots" ↔ "new england patriots").

## My Teams — rulings

1. **Following:** long-press on an event card opens a small menu: "Follow <team A>", "Follow <team B>" (or "Unfollow"), "Details". This replaces long-press → Details (Details stays reachable via the pane's button and the menu). Channels' long-press menu has only "Details" (favorites for channels is a later feature).
2. **Storage:** a per-profile set of display team names in `SportsLivePreferencesDataStore` (`sports_live_followed_teams`, Gson list), with add/remove.
3. **Effect in the section:** items involving a followed team sort first within Live Now, Upcoming and Replays and carry a small star in the card. The filter row gains a leading "★ My teams" chip whenever at least one followed team appears in the tab; selecting it shows only those games.
4. **Effect on Home:** the Live now row puts followed teams' live games first. No new Home row (keeps Home quiet; ruling).
5. **Settings → Sports & Live** gains a "My teams" card listing followed teams; OK unfollows.

## One card per game — rulings

1. **Matching** two items from *different* addons as the same game: both are events (not channels, not replays), both parse to team pairs that match order-insensitively, sports are equal or one is unknown, and either both are live or their starts are within 30 minutes. Within one addon nothing changes (existing `(addonId, id)` dedupe).
2. **Merged item:** `SportsLiveItem` gains `alternates: List<SportsLiveItem>` (other addons' copies). The primary is the copy from the addon that comes first in the user's addon order; the card shows "N sources" under the addon name.
3. **Playing a merged card:** OK resolves streams from the primary and every alternate in parallel (reusing the zap resolver's fetch), drops junk, and:
   - if the user's autoplay mode is not MANUAL, plays the best stream across all sources;
   - if MANUAL, shows a Sports & Live stream picker dialog listing streams grouped by addon, then plays the chosen one.
   Upstream's Stream screen is not modified.
5. **Failover across sources (user request 2026-11-15).** While a merged game plays, its alternates are registered in a process-wide `SportsLiveExtraSources` holder keyed by the playing `contentId`. Two small upstream touch points read it: the player's source-fallback candidate builder (`nextSourceFallbackCandidate`, `PlayerRuntimeControllerSourceFallback.kt`) appends the alternates' streams after the current addon's, and the in-player Sources panel loader (`loadSourceStreams`, `PlayerRuntimeControllerStreams.kt`) appends them grouped by addon. Zap resolution tries the primary first, then alternates. This is a deliberate, minimal exception to "don't modify the player".
4. Merging happens in the bucketer after the per-addon dedupe, so Home's Live now row, the section and zap all see merged cards.

## Non-goals

Fuzzy matching beyond the rules above. Team logos. Notifications. Channel favorites (next feature after this).

## Verification

Unit: parser on fixture names; follow/unfollow persistence per profile; ordering and "★ My teams" filter; merge rules (positive, different sport, far start times, channel, replay); merged-card resolution across sources with MANUAL and autoplay. Emulator: follow a team via long-press, its games jump to the top with a star; settings lists it; with two sports addons carrying the same game, one card shows "2 sources" and plays.
