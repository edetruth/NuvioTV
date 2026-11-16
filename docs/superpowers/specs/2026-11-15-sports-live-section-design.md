# Sports & Live section — design

Date: 2026-11-15
Status: implemented 2026-11-15 (see docs/superpowers/plans/2026-11-15-sports-live-section.md)
Branch: `feature/sports-live-section` (local clone of NuvioMedia/NuvioTV, based on `dev` at 1.2.0-beta.1)

## 1. Intent

The user installs Stremio addons that serve live sports, sports TV channels and game replays (today: "Sports Streams" by highfly, "Sports Replay", "PenguPlay"). NuvioTV shows every addon catalog as poster rows on Home, so these items land between movie shelves and are hard to browse. On a typical Saturday the user wants to flip through what is live right now, glance at what is coming up, and press OK to play. Nothing about that is served by poster rows or a detail page.

Add a dedicated **Sports & Live** root section to upstream NuvioTV that treats these catalogs as a schedule rather than a library, and keep them off the regular Home shelves.

## 2. Goals

- A sidebar entry **Sports & Live** beside Home, Search, Library, Settings.
- Tabs **Live Now / Upcoming / Channels / Replays**, each a focusable list with a details pane; OK plays.
- All installed addons merge into one list. The addon name is visible on each card and in stream selection.
- Catalogs claimed by the section stop appearing on Home. Home gets one **Live now** row that links into the section.
- Works with zero configuration for the three known addons, with a settings page to include or exclude any catalog by hand.
- Both flavors (`full`, `playstore`); both Essential and Advanced experience modes; per-profile preferences like the rest of the app.

## 3. Non-goals (v1)

- No EPG timeline grid. No addon gives program schedules per channel.
- No live video preview in the details pane (static art only).
- No cross-addon de-duplication of the same game. Each addon's item is its own card.
- No reminders or notifications for upcoming games.
- No changes to the stream selection screen or the player. Existing autoplay and player settings apply as they are.
- No Supabase sync of the new preferences.
- No M3U / Xtream / IPTV sources. Addons only.

## 4. Anti-goals

- Must not change what Home shows for a user with no sports addons. With no claimed catalogs the section is hidden and Home is untouched.
- Must not fork or copy the NuvioTVLauncher `livetv/` code. That code is tied to M3U concepts and a 150-flag settings model.
- Must not add new network dependencies or backends. Only the installed addons are called.
- Must not break the existing catalog order, disabled-catalog keys, or home-catalog sync format.

## 5. Constraints and facts the design relies on

Verified against upstream code on 2026-11-15:

- Root destinations are `Screen` objects registered with `rootComposable` in `ui/navigation/NuvioNavHost.kt`; sidebar items and `rootRoutes` are built in `MainActivity.kt` (around L1101–1165). There is no feature-flag layer.
- Home selects catalogs with `CatalogDescriptor.shouldShowOnHome()` (`HomeViewModelCatalogUtils.kt` L361) and mirrors of that rule in `CatalogOrderViewModel.kt` and `LayoutSettingsViewModel.kt`. A further mirror in `HomeCatalogSyncSupport.kt` feeds the Supabase home-catalog sync and is deliberately left alone (7.1). There is no type filter today.
- `CatalogRepository.getCatalog(...)` accepts arbitrary `extraArgs` and emits `CatalogRow` of `MetaPreview`. Unknown types such as `sport` survive as `rawType`/`apiType`; `posterShape` "landscape" is honored by `ContentCard`.
- Upstream treats type `tv` as a series synonym (`ContentType.isSeriesLikeType`, `LivePlaybackUiPolicy`). The section bypasses the detail page so this does not matter for play, but the builder must confirm the Stream route works for `tv`/`sport` items with no season/episode.
- `Screen.Stream.createRoute(videoId, contentType, title, poster, backdrop, logo, …, contentId, contentName, …)` can be navigated to from anywhere; Continue Watching already does so. Stream fetching filters addons by `supportsStreamResource(type, id)` using idPrefixes, so an item from addon X gets streams from addon X (and any other addon that claims its prefix).
- Continue Watching and Upcoming are separate state fields rendered by each of the three Home layouts (Modern, Classic, Grid), not `HomeRow` subtypes.
- Preferences follow the `ProfileDataStoreFactory` pattern (see `AnimeSkipSettingsDataStore.kt`); settings rail categories are declared in `SettingsCatalog.kt` and rendered in `SettingsScreen.kt`; `SettingsCatalogTest` asserts the category list.
- Addon data shapes observed (samples in `D:\Nuvio\scratch\addons`, private, not committed):
  - highfly: type `sport`; catalogs Live Now, Today, per-sport; ids `leaf:` (channels, e.g. "CA : TSN 4", description "Live IPTV Recording / Live on …"), `streamed:` (events, "Tenerife vs Ceuta"), also `sf`, `recap`; `posterShape: landscape`; `genres: [Sport, League]`; `releaseInfo` is `"LIVE"` or `"15 Nov 2026 · 13:00 UTC"` (the separator arrives double-encoded as `Â·`); description ends with `LIVE NOW` when live. Streams: m3u8 with a quality label, plus two informational entries ("Upgrade to watch", "Tip: use an external player").
  - Sports Replay: type `movie` per game (`sports-replay:` ids, `releaseInfo` = date, `runtime`, `genre: [Football, NFL]`, poster is a team logo) and type `series` per team (`team.` ids, episodes = games with `released` ISO dates). Streams: TorBox direct link and a magnet.
  - PenguPlay: type `tv` catalogs Live Now, per-sport, Upcoming, Channels (`date` extra), `behaviorHints.epgProvider: true`. All catalogs returned empty with a valid token; item shape unknown.

## 6. Approaches considered

| | Approach | Trade-off | Outcome |
|---|---|---|---|
| A | New section from upstream's own parts (new route, ViewModel, classifier, reuse cards, tabs, stream route) | New code and tests, roughly a few thousand lines; Upcoming only as good as addon hints | **Chosen** |
| B | Port the fork's `livetv/` and Sports UI with an addon adapter | 10k+ lines tied to M3U, channel numbers, catch-up, EPG grid and ~150 settings flags; drifts from upstream | Rejected: wrong fit, heavy upkeep |
| C | Filter only: hide sports catalogs from Home, show them as poster rows on another page | Cheapest, but keeps the poster UI the user finds unusable; no live/upcoming split; no click-to-play | Rejected: does not solve the problem |

The user chose A on 2026-11-15 after reviewing all three.

## 7. Design decisions

### 7.1 Which catalogs the section claims

A pure function `SportsLiveCatalogPolicy.classify(addon, catalog, overrides) -> Claim?` decides. Order of precedence:

1. **User override** for that catalog (include or exclude) wins. Key: the current home-catalog key format `"${addonId}_${type}_${catalogId}"` (`homeCatalogKey`), with the legacy `"${addonBaseUrl}_${type}_${catalogId}_${catalogName}"` form accepted on read, exactly as `isHomeCatalogDisabled` does.
2. **Type rule:** catalog `apiType` in `sport`, `tv`, `channel` → claimed. This is deliberate: the section is Sports **& Live**, so live TV channels from any addon (news, entertainment, not just sports) belong here rather than in movie shelves. Decided by the user on 2026-11-15.
3. **Addon rule:** addon **name** contains a sports keyword → all its catalogs claimed. (Covers "Sports Replay", whose catalogs are typed `movie`/`series`.) Addon descriptions are never matched; they are too chatty and would over-claim.
4. **Catalog rule:** catalog name or id contains a sports keyword → claimed. Applies only to catalogs typed `movie`, `series` or unknown; it exists to catch sports catalogs hiding behind movie/series types.
5. Otherwise not claimed.

Keyword list (case-insensitive, word-boundary match): sport, sports, nfl, nba, mlb, nhl, ufc, mma, f1, formula 1, nascar, motogp, soccer, football, basketball, baseball, hockey, tennis, boxing, cricket, rugby, golf, wrestling, replay, replays, highlights, recap. The builder may extend it; it lives in one place with a unit test that includes negative fixtures (a Cinemeta-style "Popular" movie catalog, a general addon whose description mentions "highlights", a documentary catalog named "Sports Documentaries" is acceptable to claim).

The claim also carries a **kind** used for the tabs:
- `REPLAY` when the addon or catalog name/id matches replay, replays, highlights, recap, or the id prefix is `recap`.
- `CHANNELS` when the catalog name/id contains channel or channels.
- `EVENTS` otherwise (live and upcoming events).

Claimed catalogs are excluded from Home **locally only**: extend `shouldShowOnHome()` in the Home pipeline, the Catalog Order screen and the Layout hero picker to consult the policy. **Do not touch `HomeCatalogSyncSupport.kt`.** The synced home-catalog list keeps its current contents and format, so other devices (including the mobile app) are unaffected and the sync code never needs the per-profile overrides. The Catalog Order screen lists claimed catalogs under a greyed "In Sports & Live" note instead of silently dropping them (builder's call on exact presentation).

**Master switch.** All of this is conditional on the "Show section" setting (7.5). When it is off the app behaves exactly as upstream: nothing is claimed, claimed catalogs return to Home, the "Live now" row is not shown, and the sidebar entry is hidden. The setting is read in the same place the policy is consulted so there is one condition, not four.

### 7.2 How items are bucketed into tabs

Items are loaded from every claimed catalog (first page, `skip=0`, no extras) in parallel, then bucketed by a pure function `SportsLiveItemBucketer`:

- **Live Now:** item `releaseInfo` equals `LIVE` (case-insensitive), or name/description contains `LIVE NOW`, or the catalog name/id contains `live` and the item has no future start time. Includes both events and channels.
- **Upcoming:** item has a parseable start time in the future (from `releaseInfo` such as `15 Nov 2026 · 13:00 UTC`, tolerant of the `Â·` mojibake, or from `released`), or has no time of day, comes from a catalog named upcoming/today/schedule, and is not live. Any other events-catalog item that is not live and has no parseable start time also goes to Upcoming, sorted last as "Time TBA" (amended after Task 10, so manually included catalogs stay visible). Items from a catalog the user included by override that are neither live nor upcoming go to Replays instead (amended after the final review: a finished event shown on demand is a replay). Sorted by start time; items with no parseable time sort last and show "Time TBA". An events-catalog item whose start time (with a time of day, not a bare date) is in the past but within the last 4 hours is treated as live; the moment it starts it is live, with no gap. Older than 4 hours it is neither live nor upcoming. Bare dates (`2026-11-14`) never make an item live. (Amended 2026-11-15 after the Task 2 review found the original 10-minute rule left a gap and had no upper bound.)
- **Channels:** items from `CHANNELS` catalogs, plus items whose description contains `IPTV` or `Live on`. Items whose genres contain "Live TV" (whole phrase, any case) are channels too, whatever the catalog kind. A channel appears in Live Now only when it comes from an `EVENTS` catalog and is live (highfly lists TSN in its "Live Now" catalog); channels from `CHANNELS` catalogs never appear in Live Now even when marked live, and channels never appear in Upcoming. Channel groups are compared case-insensitively and shown upper-cased; the group falls back from second-genre code to name prefix, then the catalog name without emoji and "(tv)", then the addon name. (Amended 2026-11-15, Task 7: Nebula's channels catalog flooded Live Now with about 1000 channels.)
- **Replays:** items from `REPLAY` catalogs, sorted newest first (undated last; amended in Task 7). Items an addon marks finished with a leading ✅ (CNCVerse) are shown in no tab. `series` items (teams) show as folders: OK opens the existing detail page, whose episode list already handles `videos`.

An item may appear in more than one tab (live channel → Live Now and Channels). Within a tab, items are sorted: live first, then by start time, then by name. A **sport filter** row (All + each distinct sport) is derived from the first `genres` entry, `category`, or the catalog name.

### 7.3 Screen layout

```
┌─ Sports & Live ──────────────────────────────────────────────────────────┐
│ [Live Now 14] [Upcoming 22] [Channels 6] [Replays]   All ▾ NFL Soccer …  │
├─────────────────────────────────┬────────────────────────────────────────┤
│ ● LIVE  Lions vs Patriots       │  ┌────────────────────────────────┐    │
│         NFL · Sports Streams    │  │   background art (landscape)   │    │
│ ● LIVE  NFL RedZone             │  └────────────────────────────────┘    │
│         NFL · Sports Streams    │  Lions vs Patriots                     │
│ 4:25 PM Chiefs vs Falcons       │  NFL · Live now · Sports Streams       │
│         NFL · Sports Streams    │  Description text…                     │
│ 8:20 PM Jets vs Bills           │  [▶ Watch]   [Details]                 │
└─────────────────────────────────┴────────────────────────────────────────┘
```

- Left pane: a `LazyColumn` of landscape cards (16:9 thumbnail, state pill, title, "league · addon"). About 45% of the width.
- Right pane: art, title, meta line, description, two buttons.
- Focus model: Up/Down moves the list; Right from the list moves to Watch; Left returns; Up from the first card reaches the tabs, then the sport chips; Back opens the sidebar like other root screens. Long-press on a card opens the detail page.
- OK on a card = Watch: navigate to `Screen.Stream.createRoute(videoId = item.id, contentType = item.apiType, title, poster, backdrop, logo, contentId = item.id, contentName = item.name, …)`. Existing autoplay settings decide whether the stream list is shown.
- Tab counts are shown when known. Empty tabs show a one-line empty state. With no claimed catalogs at all, the section shows "Install a sports addon" with a button to the add-on manager.
- Live Now refreshes automatically every 60 seconds while the screen is visible; a Refresh action on the tab row forces a reload.
- Reuse: tab pattern from `FolderDetailScreen.TabbedGridContent`, `ContentCard`/`LightweightCard` for cards, `HeroCarouselBackdrop` as the art reference, `EmptyScreenState`, `LoadingIndicator`, `ErrorState`, `dpadRepeatThrottle`.

### 7.4 Home integration

- Claimed catalogs are removed from Home (7.1).
- A **Live now** row: a new `liveNowItems` state in `HomeUiState`, produced by the same loader and bucketer, limited to 20 items, rendered directly after Continue Watching in Modern, Classic and Grid layouts in the same way each layout renders Continue Watching. OK goes straight to the Stream route; a trailing "See all" card opens the section. The row is hidden when empty or when the setting is off. The row label is "Live now".

### 7.5 Settings

A new settings category **Sports & Live** (rail group chosen by the builder, near Layout):

- Show section: on/off (default on). This is the master switch for the whole feature (see 7.1). The sidebar entry is shown when this is on **and** at least one claimed catalog exists.
- Show "Live now" row on Home: on/off (default on). Only effective while the section is on.
- Catalogs: a list of every enabled addon catalog with its automatic classification, each togglable to force include or exclude. Overrides are stored per profile in a new `SportsLivePreferencesDataStore` (feature name `sports_live_settings`).

### 7.6 Navigation and strings

- `Screen.SportsLive("sports_live")` added as a root route, registered with `rootComposable`, added to `rootRoutes` and `drawerItems` in `MainActivity` with a new `res/raw/sidebar_sports.svg` icon, and to `StartupLoadingPolicy` if that mapping needs it.
- New strings go in `res/values/strings.xml` only; other locales fall back to English. If lint's MissingTranslation blocks the build, the builder adds `tools:ignore` on those strings rather than translating 40 locales.

### 7.7 Tests

- Unit tests for `SportsLiveCatalogPolicy` and `SportsLiveItemBucketer` using sanitized fixtures derived from the three addons (tokens stripped, stored under `app/src/test/resources/sportslive/`).
- Time parsing test covering `LIVE`, `15 Nov 2026 · 13:00 UTC`, the mojibake variant, ISO `released`, and unparseable values.
- `SportsLiveViewModel` test with a fake `CatalogRepository` (pattern: `SearchViewModelPaginationTest`).
- Home pipeline test asserting claimed catalogs are excluded and `liveNowItems` is populated.
- `SettingsCatalogTest` updated for the new category.

## 8. Left to the builder

- Exact Compose structure and dimensions; whether to split list/pane into separate composables.
- Where the loader lives (a `SportsLiveRepository` wrapping `CatalogRepository`, or loading inside the ViewModel).
- Rail group placement for the settings category and the exact look of the catalog override list.
- How the Catalog Order screen marks claimed catalogs.
- Whether `liveNowItems` shares a loader instance with the section (preferred) or loads independently.
- Any throttling of parallel catalog requests.

## 9. Known rough edges accepted for v1

- Informational pseudo-streams from highfly ("Upgrade to watch", "Tip") still appear in stream selection. Hiding them is a follow-up that touches the shared stream screen.
- PenguPlay live catalogs are empty from this network. If they later return items, the type rule already claims them; bucketing may need tuning once the shape is known.
- Upcoming is only as accurate as the addons' time hints.
