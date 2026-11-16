# Sports & Live Filter Row and Channel Groups Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Sports & Live a dedicated, visually distinct filter-chip row (sports on Live Now/Upcoming/Replays, country/provider groups on Channels).

**Architecture:** A pure `SportsLiveChannelGrouper` derives a group for channel items; the bucketer stores it on `SportsLiveItem.group`. The ViewModel's `sports`/`selectedSport` generalise to `filters`/`selectedFilter` whose source depends on the tab. The screen splits chips into a tab row and a filter row with an outlined chip style.

**Tech Stack:** Kotlin 2.3, Compose + TV Material 3, Hilt, JUnit4 + MockK + coroutines-test.

**Spec:** `docs/superpowers/specs/2026-11-15-sports-live-chips-and-channel-groups-design.md` (builds on `2026-11-15-sports-live-section-design.md`)

## Global Constraints

- Branch `feature/sports-live-section` in `D:\Nuvio\NuvioTV`, on top of the completed Sports & Live tasks. Commit after each task.
- Every shell: `source /d/Nuvio/tools/env.sh`; `./gradlew --stop` before each Gradle run; never two Gradle runs at once.
- Group derivation order: (1) leading token of `meta.genres[1]` split on `-` when it matches `^[A-Z]{2,3}$`; (2) name prefix before `" : "` (trimmed) when it matches `^[A-Z]{2,3}$`; (3) `addonName`. Non-channel items: `group = null`.
- Filter values per tab: Live Now / Upcoming / Replays → distinct non-null `sport`; Channels → distinct non-null `group`; both sorted case-insensitively. Filter row hidden when fewer than 2 distinct values.
- Selected filter resets to null (All) on tab change; a filter absent from the tab counts as null.
- Strings only in `res/values/strings.xml`. No changes outside `core/sportslive`, `domain/model/SportsLive.kt`, `ui/screens/sportslive`, and the chip composable if a style parameter is needed.

## Review Focus

1. A channel whose second genre is a long provider code like `UK-TNT-SPORT-13434` must group as `UK`, and `(HD) : TNT SPORT 1` must not group as `(HD)`. (Grouper tests, Task 1.)
2. Switching from Channels (group selected) to Live Now must not filter Live Now by a group string. (ViewModel test, Task 2.)
3. Focus path Up from the first card → filter row → tab row, and Down the reverse, with the filter row hidden for one-value tabs. (Emulator check, Task 3.)
4. Mixed addons where one channel has no genres at all: falls back to the addon name, never a blank chip. (Grouper test, Task 1.)
5. Existing Sports & Live unit tests stay green after the rename. (Task 2 run.)

---

### Task 1: Channel grouper and `SportsLiveItem.group`

**Files:**
- Create: `app/src/main/java/com/nuvio/tv/core/sportslive/SportsLiveChannelGrouper.kt`
- Modify: `app/src/main/java/com/nuvio/tv/domain/model/SportsLive.kt` (add `val group: String? = null` to `SportsLiveItem`)
- Modify: `app/src/main/java/com/nuvio/tv/core/sportslive/SportsLiveItemBucketer.kt` (`describe` sets `group` when `isChannel`)
- Test: `app/src/test/java/com/nuvio/tv/core/sportslive/SportsLiveChannelGrouperTest.kt`, extend `SportsLiveItemBucketerTest.kt`

**Interfaces:**
- Produces: `object SportsLiveChannelGrouper { fun groupOf(meta: MetaPreview, addonName: String): String }`; `SportsLiveItem.group: String?`.

- [ ] **Step 1: Failing tests**
```kotlin
@Test fun `country from second genre token`()          // name "CA : TSN 4", genres ["Sports","CA-TSN-44343"] -> "CA"
@Test fun `provider code genre still yields country`()  // "(HD) : TNT SPORT 1", ["Sports","UK-TNT-SPORT-13434"] -> "UK"
@Test fun `name prefix when genre has no code`()       // "US : ESPN HD", ["Sports"] -> "US"
@Test fun `addon name fallback`()                       // "Sky Sports Main Event", [] , addon "Sports Streams" -> "Sports Streams"
@Test fun `HD marker is not a group`()                  // "(HD) : TNT SPORT 1", ["Sports"] -> addon name
// bucketer
@Test fun `leaf channels from fixture get country groups`() // highfly_live.json leaf items -> groups subset of {CA, UK, US}
@Test fun `non channel items have null group`()
```
- [ ] **Step 2:** Run `./gradlew :app:testFullDebugUnitTest --tests "com.nuvio.tv.core.sportslive.*"` → FAIL.
- [ ] **Step 3:** Implement per the Global Constraints derivation order.
- [ ] **Step 4:** Same command → PASS.
- [ ] **Step 5:** Commit `feat(sportslive): derive channel groups`.

---

### Task 2: ViewModel filters

**Files:**
- Modify: `ui/screens/sportslive/SportsLiveUiState.kt` (`sports` → `filters: List<String>`, `selectedSport` → `selectedFilter: String?`; keep KDoc accurate)
- Modify: `ui/screens/sportslive/SportsLiveViewModel.kt` (`selectSport` → `selectFilter(value: String?)`; filter source per tab per Global Constraints; reset on tab change)
- Modify: `ui/screens/sportslive/SportsLiveScreen.kt` call sites only (rename), so it compiles
- Test: `app/src/test/java/com/nuvio/tv/ui/screens/sportslive/SportsLiveViewModelTest.kt` (update renamed members; add tests)

**Interfaces:**
- Consumes: Task 1 `SportsLiveItem.group`.
- Produces: `SportsLiveUiState.filters`, `selectedFilter`; `SportsLiveViewModel.selectFilter(value: String?)`.

- [ ] **Step 1: Failing tests**
```kotlin
@Test fun `channels tab exposes groups as filters`()          // two channels CA, UK -> filters [CA, UK]
@Test fun `live tab exposes sports as filters`()
@Test fun `selecting a group filters channels`()
@Test fun `switching tabs resets the filter`()                 // select "UK" on CHANNELS, selectTab(LIVE_NOW) -> selectedFilter null, visibleItems = all live
```
- [ ] **Step 2:** Run `--tests "com.nuvio.tv.ui.screens.sportslive.*"` → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run `--tests "com.nuvio.tv.ui.screens.sportslive.*" --tests "com.nuvio.tv.core.sportslive.*"` → PASS; `./gradlew :app:compileFullDebugKotlin` → OK.
- [ ] **Step 5:** Commit `refactor(sportslive): generalise sport filter to per-tab filters`.

---

### Task 3: Two-row header and outlined filter chips

**Files:**
- Modify: `ui/screens/sportslive/SportsLiveScreen.kt` (tab row + Refresh on row 1; filter row on row 2 shown when `filters.size >= 2`, with "All" first; horizontally scrollable with start padding like the tab row)
- Modify: `ui/components/SourceStatusFilterChip.kt` only if needed: add `outlined: Boolean = false` that draws a 1dp border in the unselected state and no fill, keeping current behaviour as the default
- Modify: `res/values/strings.xml` only if a new label is needed (none expected; reuse `sports_live_filter_all`)

**Interfaces:**
- Consumes: Task 2 state and `selectFilter`.

- [ ] **Step 1:** Implement the two rows. Filter chips select on focus like the tab chips (`onFocusSelect`), OK moves focus into the list. Focus: Up from the first card → filter row's selected chip (or tab row when hidden); Up from filter row → active tab; Down from the tab row → filter row (or list when hidden); Down from filter row → selected card.
- [ ] **Step 2:** `./gradlew :app:compileFullDebugKotlin :app:compilePlaystoreDebugKotlin` → OK; `./gradlew :app:assembleFullDebug` (background).
- [ ] **Step 3: Emulator check** (`nuvio_tv` running; `adb install -r app/build/outputs/apk/full/debug/app-full-x86_64-debug.apk`): screenshot Live Now with the filter row; select a sport and confirm the list filters; switch to Channels and confirm group chips (CA/UK/US) and filtering; switch back and confirm the filter reset; walk Up/Down through the rows. Record PASS/FAIL with screenshot paths in the report.
- [ ] **Step 4:** Commit `feat(sportslive): separate filter row with channel groups`.

---

### Task 4: Local time instead of addon UTC text (added 2026-11-15 at the user's request)

The user reads "… · 21:00 UTC" in the details pane. Card pills already use the device zone; the UTC text comes from the addon's `description`, rendered verbatim.

**Files:**
- Create: `app/src/main/java/com/nuvio/tv/core/sportslive/SportsLiveLocalTime.kt`
- Modify: `ui/screens/sportslive/SportsLiveCards.kt` (pane description and meta line)
- Test: `app/src/test/java/com/nuvio/tv/core/sportslive/SportsLiveLocalTimeTest.kt`

**Interfaces:**
- Produces: `object SportsLiveLocalTime { fun localizeUtcStamps(text: String, zone: ZoneId, locale: Locale): String }` — replaces every `d MMM yyyy · HH:mm UTC` stamp (also the `Â·` mojibake and a plain-space separator) with the same instant formatted as `EEE h:mm a` in `zone`/`locale` when the date is within 6 days of now, otherwise `EEE d MMM h:mm a`; text without stamps is returned unchanged. Reuse `SportsLiveTimeParser`'s formatter/normalisation instead of duplicating patterns.

- [ ] **Step 1: Failing tests**
```kotlin
@Test fun `utc stamp becomes chicago local`()        // "Tenerife vs Ceuta\n15 Nov 2026 · 21:00 UTC", America/Chicago, en_US -> contains "3:00 PM", no "UTC"
@Test fun `mojibake separator handled`()
@Test fun `text without stamp unchanged`()
@Test fun `multiple stamps all replaced`()
```
- [ ] **Step 2:** Run `--tests "com.nuvio.tv.core.sportslive.SportsLiveLocalTimeTest"` → FAIL.
- [ ] **Step 3:** Implement; in the pane, pass the description through `localizeUtcStamps(it, ZoneId.systemDefault(), Locale.getDefault())` (remembered on item id), and make the pane's meta line include the local start (`DateFormat` short date+time) when `startMs != null && !isLive`.
- [ ] **Step 4:** Tests PASS; compile both flavors.
- [ ] **Step 5: Emulator check** (emulator zone is America/Chicago): an Upcoming item's pane shows local time and no "UTC". Screenshot.
- [ ] **Step 6:** Commit `feat(sportslive): show addon event times in local time`.

---

### Task 5: Nebula Sports compatibility (added 2026-11-15, user-supplied test addon)

Observed in `org.nebulastreams.sports` (type `sports`, ids `streamed:`): `releaseInfo` "🔴 LIVE", description "… · Live now", start format "2026-11-15 21:56 UTC", genres ["F1","Replay"].

**Files:** `core/sportslive/SportsLiveCatalogPolicy.kt` (type rule adds `sports`), `core/sportslive/SportsLiveItemBucketer.kt` (live detection), `core/sportslive/SportsLiveTimeParser.kt` (format `yyyy-MM-dd HH:mm 'UTC'`), fixtures `app/src/test/resources/sportslive/nebula_live.json`, `nebula_replays.json` (from `D:\Nuvio\scratch\addons\nebula.streamed-events-live-now.json` and the first 20 metas of `nebula.streamed-replays.json`; no tokens in catalog bodies, verify).

- [ ] Tests: type `sports` claimed by the type rule; `releaseInfo` containing the whole word `LIVE` after stripping non-letters (e.g. "🔴 LIVE") is live; description containing "Live now" case-insensitively as a whole phrase is live (keep "Live on" channel check case-sensitive); "2026-11-15 21:56 UTC" parses to that instant with a time of day; Nebula live fixture → all LIVE_NOW; Nebula replays fixture (catalog name "Replays") → REPLAYS.
- [ ] Implement; run `--tests "com.nuvio.tv.core.sportslive.*"`; commit `feat(sportslive): understand Nebula Sports live markers and times`.

---

### Task 6: CNCVerse compatibility and large channel lists (added 2026-11-15, user-supplied test addon)

Observed in `com.cncverse.stremiobridge.*`: type `tv` catalogs "⚡SportzX Live Events (tv)" etc. (names like "🔜 AFG vs ZIM", no time, no genres) and "EasyPlay Live TV (tv)" with 4,857 channels named "UK| SKY SPORTS 1" (132 prefixes, ~60 each; 3.8 MB response).

**Rulings:**
- Kind: a catalog whose name or id contains the phrase "live tv" (case-insensitive) is CHANNELS (in addition to channel/channels); "events" in the name keeps EVENTS.
- Status emoji in item names: leading 🔴 → live; leading 🔜 → upcoming (not live, start unknown → Upcoming "Time TBA"); the emoji and following whitespace are stripped from the displayed name and from team parsing. A "live"-named catalog no longer marks 🔜 items live.
- Channel group rule (amends Task 1): prefix before `|` or ` : ` (trimmed), accepted when it is 2–8 characters of letters, digits or `-` and contains a letter (e.g. `UK`, `ASIA-IN`, `DSTV`); the second-genre code rule stays first; addon name last.
- Performance: the section's 60 s auto-refresh reloads only EVENTS and REPLAY catalogs, not CHANNELS catalogs (channels reload on manual Refresh and on the first load per app session). Bucketing runs once per load, not on every selection change (separate the bucketed tabs from selection in the ViewModel's combine).

**Files:** `core/sportslive/SportsLiveCatalogPolicy.kt`, `SportsLiveItemBucketer.kt`, `SportsLiveChannelGrouper.kt` (from Task 1), `data/repository/SportsLiveRepositoryImpl.kt` (`load(force, includeChannels: Boolean = true)`), `ui/screens/sportslive/SportsLiveViewModel.kt`, fixtures `cnc_live_events.json` (from `D:\Nuvio\scratch\addons\cnc.sportzx.json`) and `cnc_live_tv_sample.json` (first 200 metas of `cnc.cnc_EasyPlayLiveTV_tv.json`); verify no tokens (catalog ids are base64 channel descriptors, not secrets).

- [ ] Tests: "Live TV" catalog → CHANNELS; 🔜 item not live and in Upcoming; 🔴 item live; displayed names stripped; "AFG vs ZIM" parses (for later team features); grouper gives `UK`, `ASIA-IN`, `DSTV`; auto-refresh calls `load(includeChannels = false)`; ViewModel buckets once per load (count calls via a seam or measure that selection changes don't re-invoke the bucketer).
- [ ] Emulator: install CNCVerse; Channels tab with group chips stays responsive (scroll, filter) with ~4.8k channels; Live Now shows no channels from "Live TV". Screenshots.
- [ ] Commit `feat(sportslive): handle large live TV catalogs and status emoji`.

---

### Task 7: Plain live TV addons (added 2026-11-15, user-supplied Ultra MAX TV)

Observed in `org.kris.ultra.max.tv.*`: type `tv` catalogs "🇺🇸 United States TV" (200) and "🇬🇧 United Kingdom TV" (91); items have `genres: ["Live TV"]`, no prefix, no time, no live marker; one m3u8 stream each.

**Rulings:**
- An item whose genres contain "Live TV" (case-insensitive, whole phrase) is a channel (`isChannel = true`), whatever its catalog's kind.
- Channels appear only in the Channels tab, never in Live Now or Upcoming, unless the item comes from an EVENTS-kind catalog (e.g. highfly lists TSN in its "Live Now" catalog) AND is live. Channels from CHANNELS-kind catalogs never appear in Live Now even when marked live (Nebula's Channels catalog marks ~1000 channels "live", flooding Live Now to 1073 items on the emulator). This amends spec §7.2 ("A channel that is live also appears in Live Now").
- Replays are sorted newest first (start descending, null last) instead of oldest first.
- Items whose name starts with the status emoji ✅ (finished, as CNCVerse marks them) are excluded from every tab; the emoji is never shown.
- Channel group names are compared case-insensitively and displayed upper-cased ("BiH" and "BIH" are one chip "BIH").
- Channel group fallback order becomes: second-genre code → name prefix (Task 6 rule) → **catalog name with emoji, "(tv)" and surrounding whitespace removed** → addon name.

**Files:** `core/sportslive/SportsLiveItemBucketer.kt`, `SportsLiveChannelGrouper.kt`, spec §7.2 amendment (`git add -f`), fixture `ultra_tv_uk.json` (copy of `D:\Nuvio\scratch\addons\ultra.tv_uk.json`; ids are base64 channel names, no secrets).
- [ ] Tests: Ultra fixture items are channels, absent from LIVE_NOW and UPCOMING, present in CHANNELS; group "United Kingdom TV"; highfly leaf items (with "LIVE NOW") still appear in both LIVE_NOW and CHANNELS; CNCVerse Live TV sample stays out of LIVE_NOW.
- [ ] Tests: Nebula channels fixture (first 30 metas of D:/Nuvio/scratch/addons/nebula.streamed-events-dlhd-channels.json, read as UTF-8; catalog kind CHANNELS) stays out of LIVE_NOW; Replays order newest first. Tests: ✅ item appears in no tab; groups "BiH" and "BIH" yield one filter "BIH".
- [ ] Commit `feat(sportslive): treat plain live TV addons as channels`.

---

### Task 8: Unknown-addon safety net and recognition stats (added 2026-11-15, user request "Go ahead" on scalability)

**Rulings:**
- Per catalog, after describing items: if no item has a per-item signal (parseable start, explicit live marker, 🔜/🔴, or channel marker), classify all items from the catalog name: LIVE words (live, live now, en vivo, ao vivo, direct, direto) → live; SCHEDULE words (schedule, upcoming, today, next, coming up, programación, agenda) → Upcoming "Time TBA"; REPLAY words (existing + full match, highlights, resumen, melhores momentos) → Replays; otherwise unchanged. Keyword sets live in `SportsLiveKeywords`.
- Live items from the fallback are still subject to the ended-games probe.
- Recognition stats per catalog key from the last load: counts of live, timed, upcoming-TBA, channel, unrecognised (= items that landed in no tab). Kept in memory by the repository (`val catalogStats: StateFlow<Map<String, CatalogStats>>`); shown in Settings → Sports & Live as the catalog row's second line, e.g. "12 live · 8 timed · 3 unrecognised" (strings with plurals), only for claimed catalogs that have been loaded this session.

**Files:** `core/sportslive/SportsLiveKeywords.kt`, `SportsLiveItemBucketer.kt` (or a new `SportsLiveCatalogFallback.kt`), `data/repository/SportsLiveRepositoryImpl.kt`, `ui/screens/settings/SportsLiveSettingsViewModel.kt` + `SportsLiveSettingsContent.kt`, `values/strings.xml`.
- [ ] Tests: signal-less catalog named "Live Events" → all live; "Agenda de hoy" → Upcoming TBA; "Full Match Replays" → Replays; catalog with at least one signalled item → per-item rules unchanged; stats counts on a mixed fixture.
- [ ] Commit `feat(sportslive): fall back to catalog names for unknown addons and show recognition stats`.
