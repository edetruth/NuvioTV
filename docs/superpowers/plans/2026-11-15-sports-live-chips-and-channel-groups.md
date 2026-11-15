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
