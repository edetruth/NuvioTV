# Sports & Live: sport chip row and Channels grouping — short design

Date: 2026-11-15
Status: approved by user 2026-11-15
Builds on: `2026-11-15-sports-live-section-design.md` (implemented on `feature/sports-live-section`)

## Intent

After trying the section on the emulator the user asked for two things: an obvious way to filter Live Now, Upcoming and Replays by sport, and a better-organised Channels tab, which will hold hundreds of channels once an IPTV-style addon is installed. Approach chosen by the user on 2026-11-15: "go with your recommendation".

## What exists today

- A sport filter already exists: "All" plus one chip per sport in the current tab, derived from each item's first genre or its catalog name. It shares one row with the four tab chips and the Refresh chip, so it reads as more tabs and is easy to miss.
- Channels is a flat list sorted live-first then by name. highfly names channels `CA : TSN 4`, `US : ESPN HD`, `(HD) : TNT SPORT 1`, and gives a second genre like `UK-TNT-SPORT-13434`, `CA-TSN-44343`. So a country code is reliably available from the second genre's leading token, and usually from the name prefix.

## Goals

1. The sport filter is visually distinct from the tabs: its own row directly under the tab row, with a different chip style (outlined vs. the tabs' filled), and the selected sport is clearly highlighted.
2. Channels gets the same chip row, but populated with **groups** instead of sports: `All`, then one chip per group found in the tab, sorted alphabetically. Picking a group filters the list.
3. A group for a channel is derived, in order: the leading token of the second `genres` entry when it is 2–3 uppercase letters (`UK`, `CA`, `US`); else the name prefix before ` : ` when it is 2–3 uppercase letters; else the addon's name. Items from non-channel kinds are unaffected.
4. Within a group, the existing order stays: live first, then name.

## Non-goals (this change)

- Favorites / pinning channels. Agreed as the next feature after this one.
- Section headers inside the list.
- Changing what counts as a channel, or the dedupe rule.

## Approaches considered

| | Approach | Trade-off | Outcome |
|---|---|---|---|
| A | Group chips reusing the existing sport-chip mechanism, moved to its own row | Smallest change; same interaction the user already knows; needs a reliable group parser | **Chosen** |
| B | Sticky section headers in the Channels list | Good for scanning, but a long list still needs scrolling and headers steal focus/space on TV | Rejected for now |
| C | Favorites first, grouping later | Most useful long-term, but doesn't solve "hundreds of channels" on day one | Deferred to the next feature |

## Decisions

- `SportsLiveItem` gains `group: String?` filled by the bucketer via a pure `SportsLiveChannelGrouper.groupOf(meta, addonName)`; for non-channel items it is null.
- `SportsLiveUiState.sports` is replaced by `filters: List<String>` whose meaning depends on the tab: sports for Live Now/Upcoming/Replays, groups for Channels; `selectedSport` becomes `selectedFilter`. Same reset-on-tab-change rule.
- The screen renders two rows: tabs + Refresh on the first, filter chips on the second. The second row is hidden when the tab has fewer than two distinct filter values.
- Focus: Up from the first card → filter row (when shown) → tab row. Down from the tab row → filter row → list.
- Chip style: filter chips use the outlined variant of the existing chip composable; the selected one uses the accent fill. If no outlined variant exists, the builder adds a `selected`/`outlined` parameter to the existing chip rather than a new composable.
- Tests: grouper unit tests on the three highfly name/genre shapes above plus an addon-name fallback; ViewModel test that Channels exposes groups and other tabs expose sports.

## Left to the builder

Exact spacing and chip sizes; whether the filter row scrolls horizontally like the tab row (it should when it overflows).
