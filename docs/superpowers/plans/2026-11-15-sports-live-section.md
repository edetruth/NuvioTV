# Sports & Live Section Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Sports & Live" root section to NuvioTV that lists live, upcoming, channel and replay items from installed Stremio addons as a tabbed list with a details pane, plays on OK, removes those catalogs from Home, and adds one "Live now" row on Home.

**Architecture:** A pure classifier (`SportsLiveCatalogPolicy`) decides which addon catalogs the section claims; a pure bucketer sorts loaded `MetaPreview`s into tabs. A `SportsLiveRepository` combines installed addons, per-profile overrides and the master switch, loads claimed catalogs through the existing `CatalogRepository`, and exposes flows consumed by a new `SportsLiveViewModel` and by `HomeViewModel` (for exclusion and the "Live now" row). Playback reuses `Screen.Stream` unchanged.

**Tech Stack:** Kotlin 2.3, Jetpack Compose + TV Material 3, Hilt (constructor injection), DataStore Preferences via `ProfileDataStoreFactory`, Coil, JUnit4 + MockK + kotlinx-coroutines-test, Gradle 8.13 / AGP 8.13.2, JDK 17 at `D:\Nuvio\tools`.

**Spec:** `docs/superpowers/specs/2026-11-15-sports-live-section-design.md`

## Global Constraints

- Branch `feature/sports-live-section` in `D:\Nuvio\NuvioTV`. Commit after every task. Every shell session first runs `source /d/Nuvio/tools/env.sh` (Git Bash) so JDK/SDK/Gradle home resolve to D:.
- Unit tests: `./gradlew :app:testFullDebugUnitTest --tests "<class or package>"`. Build check: `./gradlew :app:compileFullDebugKotlin` (fast) and `./gradlew :app:assembleFullDebug` (full, ~15 min first time).
- Base package `com.nuvio.tv`; Kotlin sources under `app/src/main/java/com/nuvio/tv/` (written as `…/tv/` below); tests under `app/src/test/java/com/nuvio/tv/`.
- Do not modify `core/sync/HomeCatalogSyncSupport.kt`, `ui/screens/stream/**`, `ui/screens/player/**`. Do not add network dependencies.
- New strings go only in `app/src/main/res/values/strings.xml`. If lint fails on `MissingTranslation`, add `tools:ignore="MissingTranslation"` to those `<string>` elements.
- Both flavors build (`full`, `playstore`): no flavor-specific source sets.
- Profile-scoped preferences use `ProfileDataStoreFactory` + `ProfileManager` exactly like `data/local/AnimeSkipSettingsDataStore.kt`.
- Catalog identity key everywhere in this feature: `catalogRowLegacyKey(addonId, apiType, catalogId)` from `domain/model/CatalogRow.kt` (format `"${addonId}_${type}_${catalogId}"`).
- Master switch: when `showSection == false` the app behaves exactly as upstream (nothing claimed, no row, no sidebar entry).
- Keyword list (word-boundary, case-insensitive): sport, sports, nfl, nba, mlb, nhl, ufc, mma, f1, formula 1, nascar, motogp, soccer, football, basketball, baseball, hockey, tennis, boxing, cricket, rugby, golf, wrestling, replay, replays, highlights, recap. Replay keywords: replay, replays, highlights, recap. Channel keywords: channel, channels. Live-catalog keywords: live. Upcoming-catalog keywords: upcoming, today, schedule.

## Review Focus

1. One addon times out or returns an error while others succeed: the section must still show the successful catalogs and surface the failure as a non-blocking message, never an empty error screen. (Test added in Task 4.)
2. `releaseInfo` that is unparseable, blank, or carries the `Â·` mojibake: item lands in Upcoming with "Time TBA" or in Live Now; never a crash or a silently dropped item. (Tests in Task 2.)
3. An item typed `tv` or `sport` with no season/episode: OK must reach stream selection and list streams. (Manual verification in Task 10; no unit test can prove it.)
4. Switching profile: claimed set, overrides and section content must follow the new profile without restart. (Test in Task 3 for the store; manual check in Task 10.)
5. Turning the master switch off and on: Home rows must come back and disappear without restart, and the sidebar entry must follow. (Test in Task 5; manual check in Task 10.)

---

## File map

| Path | Responsibility |
|---|---|
| `…/tv/domain/model/SportsLive.kt` | `SportsLiveClaim`, `SportsLiveKind`, `SportsLiveTab`, `SportsLiveItem`, `SportsLiveOverride`, `ClaimedCatalog` |
| `…/tv/core/sportslive/SportsLiveKeywords.kt` | keyword sets + `containsKeyword` |
| `…/tv/core/sportslive/SportsLiveCatalogPolicy.kt` | `classify(addon, catalog, overrides)` |
| `…/tv/core/sportslive/SportsLiveTimeParser.kt` | `parseStartMs(releaseInfo, released, nowMs)` |
| `…/tv/core/sportslive/SportsLiveItemBucketer.kt` | `bucket(items, nowMs)` → tabs; `sportOf(item)` |
| `…/tv/data/local/SportsLivePreferencesDataStore.kt` | master switch, home row switch, overrides (per profile) |
| `…/tv/domain/repository/SportsLiveRepository.kt` | interface |
| `…/tv/data/repository/SportsLiveRepositoryImpl.kt` | claimed catalogs flow, loading, caching, live-now flow |
| `…/tv/core/di/RepositoryModule.kt` | bind impl |
| `…/tv/ui/screens/sportslive/SportsLiveUiState.kt` | UI state |
| `…/tv/ui/screens/sportslive/SportsLiveViewModel.kt` | state + refresh + play intent |
| `…/tv/ui/screens/sportslive/SportsLiveScreen.kt` | tabs + chips + list + pane |
| `…/tv/ui/screens/sportslive/SportsLiveCards.kt` | list card + details pane composables |
| `…/tv/ui/screens/settings/SportsLiveSettingsContent.kt` + `SportsLiveSettingsViewModel.kt` | settings page |
| `…/tv/ui/navigation/Screen.kt`, `NuvioNavHost.kt`, `MainActivity.kt` | route + sidebar |
| `…/tv/ui/screens/home/HomeViewModel*.kt`, `HomeScreen.kt` | exclusion + Live now row |
| `…/tv/ui/screens/addon/CatalogOrderViewModel.kt`, `…/settings/LayoutSettingsViewModel.kt` | exclusion mirrors |
| `app/src/test/resources/sportslive/*.json` | sanitized addon fixtures |

---

### Task 1: Domain models, keywords and catalog policy

**Files:**
- Create: `…/tv/domain/model/SportsLive.kt`
- Create: `…/tv/core/sportslive/SportsLiveKeywords.kt`
- Create: `…/tv/core/sportslive/SportsLiveCatalogPolicy.kt`
- Test: `app/src/test/java/com/nuvio/tv/core/sportslive/SportsLiveCatalogPolicyTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class SportsLiveKind { EVENTS, CHANNELS, REPLAY }
  enum class SportsLiveOverride { INCLUDE, EXCLUDE }
  data class SportsLiveClaim(val kind: SportsLiveKind, val reason: String)
  data class ClaimedCatalog(val addon: Addon, val catalog: CatalogDescriptor, val key: String, val kind: SportsLiveKind)
  object SportsLiveKeywords { val SPORTS: Set<String>; val REPLAY: Set<String>; val CHANNELS: Set<String>; val LIVE: Set<String>; val UPCOMING: Set<String>; fun containsKeyword(text: String?, keywords: Set<String>): Boolean }
  object SportsLiveCatalogPolicy { fun classify(addon: Addon, catalog: CatalogDescriptor, overrides: Map<String, SportsLiveOverride>): SportsLiveClaim? ; fun keyFor(addon: Addon, catalog: CatalogDescriptor): String }
  ```
  `keyFor` returns `catalogRowLegacyKey(addon.id, catalog.apiType, catalog.id)`.

- [ ] **Step 1: Write the failing tests** in `SportsLiveCatalogPolicyTest.kt`. Build `Addon`/`CatalogDescriptor` with a small helper `addon(id, name, baseUrl, catalogs)` / `catalog(type: String, id, name)` (use `ContentType.fromString(type)` and `rawType = type`).

```kotlin
@Test fun `sport typed catalog is claimed as EVENTS`()          // highfly "sports_live" type "sport" -> kind EVENTS
@Test fun `tv typed catalog from any addon is claimed`()         // addon "News IPTV", catalog type "tv" -> non-null, EVENTS
@Test fun `channel typed catalog named Channels is CHANNELS`()   // type "channel", name "Channels - TV" -> CHANNELS
@Test fun `addon named Sports Replay claims movie catalogs as REPLAY`() // addon name "Sports Replay | Powered By X", catalog type movie "Football" -> REPLAY
@Test fun `addon description is never matched`()                 // addon name "Torrentio", description "movies, sports, highlights", catalog movie "Popular" -> null
@Test fun `movie catalog named NFL Games is claimed by catalog rule`() // -> EVENTS
@Test fun `cinemeta popular movies is not claimed`()             // addon "Cinemeta", movie "Popular" -> null
@Test fun `exclude override beats type rule`()                   // overrides[key] = EXCLUDE on a sport catalog -> null
@Test fun `include override claims a plain movie catalog as EVENTS`()
@Test fun `replay keyword in catalog id wins over events`()      // type sport, id "sports_recap" -> REPLAY
@Test fun `keyword match is word bounded`()                      // catalog name "Transportation Docs" (contains "sport" inside "Transportation") -> null
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testFullDebugUnitTest --tests "com.nuvio.tv.core.sportslive.SportsLiveCatalogPolicyTest"`
Expected: FAIL (unresolved references).

- [ ] **Step 3: Implement** the models in `SportsLive.kt`, the keyword object (regex `\b(kw)\b` built once per set, `IGNORE_CASE`; "formula 1" and "live tv" style multi-word entries are matched as literal phrases), and `classify` with precedence: override → type rule (`apiType` in `sport`,`tv`,`channel`) → addon-name rule → catalog-name/id rule (only when `apiType` is movie/series/unknown) → null. Kind: REPLAY if addon name, catalog name or id matches REPLAY keywords or catalog id starts with `recap`; else CHANNELS if catalog name/id matches CHANNELS keywords; else EVENTS. `reason` is a short string naming the rule (e.g. `"type:sport"`, `"override:include"`), used by the settings list.

- [ ] **Step 4: Run tests to verify they pass**

Run: same as Step 2. Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nuvio/tv/domain/model/SportsLive.kt app/src/main/java/com/nuvio/tv/core/sportslive/ app/src/test/java/com/nuvio/tv/core/sportslive/SportsLiveCatalogPolicyTest.kt
git commit -m "feat(sportslive): add domain models and catalog claim policy"
```

---

### Task 2: Time parser and item bucketer with addon fixtures

**Files:**
- Create: `…/tv/core/sportslive/SportsLiveTimeParser.kt`
- Create: `…/tv/core/sportslive/SportsLiveItemBucketer.kt`
- Modify: `…/tv/domain/model/SportsLive.kt` (add `SportsLiveTab`, `SportsLiveItem`)
- Create fixtures: `app/src/test/resources/sportslive/highfly_live.json`, `highfly_today.json`, `replay_football.json`, `replay_football_teams.json` (copied from `D:\Nuvio\scratch\addons\highfly.live.json`, `example.invalidday.json`, `replay.football.json`, `replay.football_teams.json`; these catalog bodies contain no tokens, verify with `grep -c "auth_token\|token=" app/src/test/resources/sportslive/*.json` → all `0`)
- Test: `app/src/test/java/com/nuvio/tv/core/sportslive/SportsLiveTimeParserTest.kt`, `SportsLiveItemBucketerTest.kt`

**Interfaces:**
- Consumes: Task 1 models, `MetaPreview`, `CatalogMapper` (`data/mapper/CatalogMapper.kt`) + Moshi to turn fixture JSON into `MetaPreview`s the same way `CatalogRepositoryTypeTest` does.
- Produces:
  ```kotlin
  enum class SportsLiveTab { LIVE_NOW, UPCOMING, CHANNELS, REPLAYS }
  data class SportsLiveItem(
      val meta: MetaPreview, val addonId: String, val addonName: String, val addonBaseUrl: String,
      val catalogKey: String, val catalogName: String, val kind: SportsLiveKind,
      val isLive: Boolean, val isChannel: Boolean, val startMs: Long?, val sport: String?
  ) { val id get() = meta.id; val apiType get() = meta.apiType }
  object SportsLiveTimeParser { fun parseStartMs(releaseInfo: String?, released: String?, nowMs: Long): Long? }
  object SportsLiveItemBucketer {
      fun describe(meta: MetaPreview, claimed: ClaimedCatalog, nowMs: Long): SportsLiveItem
      fun bucket(items: List<SportsLiveItem>, nowMs: Long): Map<SportsLiveTab, List<SportsLiveItem>>
      fun sportOf(meta: MetaPreview, catalogName: String): String?
  }
  ```

- [ ] **Step 1: Write failing parser tests**

```kotlin
@Test fun `LIVE returns null`()                                             // parseStartMs("LIVE", null, now) == null
@Test fun `parses day month year dot time UTC`()                            // "15 Nov 2026 · 13:00 UTC" -> epoch of 2026-11-15T13:00Z
@Test fun `parses mojibake separator`()                                     // "15 Nov 2026 Â· 13:00 UTC" -> same value
@Test fun `parses ISO released when releaseInfo is a bare date`()           // releaseInfo "2026-11-14", released "2026-11-14T00:00:00.000Z" -> epoch of that instant
@Test fun `bare date without released gives midnight UTC`()                 // "2026-11-14" -> 2026-11-14T00:00Z
@Test fun `garbage returns null`()                                          // "Season 2", null -> null; blank -> null
```

- [ ] **Step 2: Write failing bucketer tests** (load fixtures with a `loadMetas("highfly_today.json")` helper; `now` fixed at `2026-11-15T14:30:00Z`; a fake `ClaimedCatalog` per fixture with the matching kind)

```kotlin
@Test fun `highfly live fixture all land in LIVE_NOW`()                     // every item: isLive true; LIVE_NOW size == metas size
@Test fun `leaf items are channels and appear in CHANNELS and LIVE_NOW`()   // "leaf:ca-tsn-44343": isChannel true (description contains "IPTV"/"Live on"); present in both tabs
@Test fun `streamed event with LIVE NOW is not a channel`()                 // "streamed:football-tenerife-ceuta-20772": isChannel false
@Test fun `future start goes to UPCOMING sorted by start`()                 // synthetic metas with releaseInfo "15 Nov 2026 · 15:00 UTC" and "... 16:00 UTC" -> UPCOMING order [15:00, 16:00]
@Test fun `start more than 10 minutes past in an events catalog is live`()  // "15 Nov 2026 · 14:00 UTC" with now 14:30 -> LIVE_NOW
@Test fun `unparseable time from an upcoming catalog goes last with null start`() // catalog name "Upcoming", releaseInfo "TBD" -> in UPCOMING, startMs null, after timed items
@Test fun `replay fixtures land in REPLAYS only`()                          // replay_football.json with kind REPLAY -> all in REPLAYS, none in LIVE_NOW
@Test fun `sport comes from first genre then category then catalog name`()  // genres ["Football","NFL"] -> "Football"; empty genres + catalog "Basketball" -> "Basketball"
@Test fun `live first then by start then by name within a tab`()
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :app:testFullDebugUnitTest --tests "com.nuvio.tv.core.sportslive.*"`
Expected: FAIL.

- [ ] **Step 4: Implement** `SportsLiveTimeParser` using `java.time` (desugaring is enabled). Normalize input first: replace `Â·` and `·` with a single space, collapse whitespace, trim. Try, in order: `d MMM yyyy HH:mm 'UTC'` (English locale, UTC), `yyyy-MM-dd'T'HH:mm[:ss[.SSS]]X` from `released`, then `yyyy-MM-dd` → midnight UTC. Return null otherwise. Implement `SportsLiveItemBucketer.describe`: `isLive` = releaseInfo equalsIgnoreCase "LIVE" || name/description contains "LIVE NOW" || (catalog name/id matches LIVE keywords && startMs == null) || (kind == EVENTS && startMs != null && startMs < nowMs - 10 min); `isChannel` = kind == CHANNELS || description contains "IPTV" or "Live on"; `startMs` from parser. `bucket`: LIVE_NOW = isLive && kind != REPLAY; UPCOMING = !isLive && kind != REPLAY && (startMs > now || catalog matches UPCOMING keywords); CHANNELS = isChannel && kind != REPLAY; REPLAYS = kind == REPLAY. Sort each tab: live first, then startMs ascending with null last, then name.

- [ ] **Step 5: Run tests to verify they pass**

Run: same as Step 3. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/nuvio/tv/core/sportslive/ app/src/main/java/com/nuvio/tv/domain/model/SportsLive.kt app/src/test/java/com/nuvio/tv/core/sportslive/ app/src/test/resources/sportslive/
git commit -m "feat(sportslive): parse start times and bucket items into tabs"
```

---

### Task 3: Per-profile preferences store

**Files:**
- Create: `…/tv/data/local/SportsLivePreferencesDataStore.kt`
- Test: `app/src/test/java/com/nuvio/tv/data/local/SportsLivePreferencesDataStoreTest.kt`

**Interfaces:**
- Consumes: `ProfileDataStoreFactory.get(profileId, feature)`, `ProfileManager.activeProfileId: StateFlow<Int>`, `TestPreferencesStore`.
- Produces:
  ```kotlin
  @Singleton class SportsLivePreferencesDataStore @Inject constructor(factory: ProfileDataStoreFactory, profileManager: ProfileManager) {
      companion object { const val FEATURE = "sports_live_settings" }
      val showSection: Flow<Boolean>          // default true
      val showHomeRow: Flow<Boolean>          // default true
      val overrides: Flow<Map<String, SportsLiveOverride>>  // JSON object {catalogKey: "INCLUDE"|"EXCLUDE"} in a string key "sports_live_overrides"
      suspend fun setShowSection(v: Boolean); suspend fun setShowHomeRow(v: Boolean)
      suspend fun setOverride(catalogKey: String, override: SportsLiveOverride?)  // null clears
  }
  ```

- [ ] **Step 1: Write failing tests.** Mock `ProfileDataStoreFactory` with MockK so `get(1, FEATURE)` returns one `TestPreferencesStore` and `get(2, FEATURE)` another; `ProfileManager.activeProfileId` is a `MutableStateFlow(1)`.

```kotlin
@Test fun `defaults are on with no overrides`()                 // showSection.first() true, showHomeRow true, overrides empty
@Test fun `setOverride round trips and null clears`()           // setOverride("a_sport_x", EXCLUDE) -> overrides == {"a_sport_x": EXCLUDE}; setOverride("a_sport_x", null) -> empty
@Test fun `switching profile reads the other store`()           // set EXCLUDE on profile 1; activeProfileId.value = 2; overrides.first() empty
@Test fun `malformed overrides json yields empty map`()         // pre-seed the string key with "not json" -> empty map, no throw
```

- [ ] **Step 2: Run tests to verify they fail.** Run: `./gradlew :app:testFullDebugUnitTest --tests "com.nuvio.tv.data.local.SportsLivePreferencesDataStoreTest"`. Expected: FAIL.

- [ ] **Step 3: Implement** following `AnimeSkipSettingsDataStore.kt` exactly (flatMapLatest over `activeProfileId`). Serialize overrides with Gson (`libs.gson`, already a dependency): `Gson().toJson(map)` / `fromJson(json, object : TypeToken<Map<String, String>>() {}.type)` wrapped in `runCatching { … }.getOrDefault(emptyMap())`. Do not use `org.json`, which is stubbed in unit tests.

- [ ] **Step 4: Run tests to verify they pass.** Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nuvio/tv/data/local/SportsLivePreferencesDataStore.kt app/src/test/java/com/nuvio/tv/data/local/SportsLivePreferencesDataStoreTest.kt
git commit -m "feat(sportslive): add per-profile preferences store"
```

---

### Task 4: SportsLiveRepository (claims, loading, live-now flow)

**Files:**
- Create: `…/tv/domain/repository/SportsLiveRepository.kt`
- Create: `…/tv/data/repository/SportsLiveRepositoryImpl.kt`
- Modify: `…/tv/core/di/RepositoryModule.kt` (add `@Binds` for the new interface next to the `CatalogRepository` binding at ~L35)
- Test: `app/src/test/java/com/nuvio/tv/data/repository/SportsLiveRepositoryImplTest.kt`

**Interfaces:**
- Consumes: `AddonRepository.getInstalledAddons(): Flow<List<Addon>>`, `enabledAddons()`, `CatalogRepository.getCatalog(...)`, `NetworkResult`, Tasks 1–3.
- Produces:
  ```kotlin
  data class SportsLiveLoad(val items: List<SportsLiveItem>, val failures: List<SportsLiveFailure>, val loadedAtMs: Long)
  data class SportsLiveFailure(val catalog: ClaimedCatalog, val message: String)
  interface SportsLiveRepository {
      val enabled: Flow<Boolean>                                  // showSection
      val claimedCatalogs: Flow<List<ClaimedCatalog>>             // empty when disabled
      val claimedCatalogKeys: Flow<Set<String>>                   // keys of the above
      val sectionAvailable: Flow<Boolean>                         // enabled && claimedCatalogs.isNotEmpty()
      suspend fun load(force: Boolean = false): SportsLiveLoad    // parallel first page of each claimed catalog; cached 60 s unless force
      val latest: StateFlow<SportsLiveLoad?>                      // last successful load
      fun liveNow(limit: Int = 20): Flow<List<SportsLiveItem>>    // from latest, LIVE_NOW tab, trimmed; empty when !showHomeRow or disabled
  }
  ```

- [ ] **Step 1: Write failing tests** with a hand-written `FakeCatalogRepository : CatalogRepository` that maps `(addonId, catalogId)` → `NetworkResult` sequence (`Loading` then `Success(CatalogRow)` or `Error("boom")`), a `MutableStateFlow<List<Addon>>` behind a MockK `AddonRepository`, and a real `SportsLivePreferencesDataStore` over `TestPreferencesStore` (Task 3 setup). Use `MainDispatcherRule` and `runTest`.

```kotlin
@Test fun `claimed catalogs come only from enabled addons that the policy claims`()
@Test fun `disabled master switch yields no claimed catalogs and sectionAvailable false`()
@Test fun `load returns items from all claimed catalogs and records failures separately`() // two catalogs ok, one Error -> items from two, failures size 1, no throw
@Test fun `load within 60 seconds returns cached result unless forced`()      // second load() does not hit the fake (count calls)
@Test fun `liveNow is empty when showHomeRow is false`()
@Test fun `liveNow is capped at limit and only contains live items`()
@Test fun `overrides change reclassifies without restart`()                   // setOverride(key, EXCLUDE) -> claimedCatalogKeys no longer contains key
```

- [ ] **Step 2: Run tests to verify they fail.** Run: `./gradlew :app:testFullDebugUnitTest --tests "com.nuvio.tv.data.repository.SportsLiveRepositoryImplTest"`. Expected: FAIL.

- [ ] **Step 3: Implement** `SportsLiveRepositoryImpl @Inject constructor(addonRepository: AddonRepository, catalogRepository: CatalogRepository, prefs: SportsLivePreferencesDataStore)` with a secondary constructor parameter `nowMs: () -> Long = { System.currentTimeMillis() }` and `ioDispatcher: CoroutineDispatcher = Dispatchers.IO` (tests pass the test dispatcher). `load` runs on `ioDispatcher`; no long-lived scope is needed because `claimedCatalogs` is a cold combined flow and `latest` is a `MutableStateFlow` written by `load`. `claimedCatalogs = combine(enabled, addons.map { it.enabledAddons() }, prefs.overrides) { … policy.classify … }`. `load`: `coroutineScope { claimed.map { async { catalogRepository.getCatalog(baseUrl, id, name, catalog.id, catalog.name, catalog.apiType, skip = 0, supportsSkip = false).filter { it !is Loading }.first() } }.awaitAll() }`, each wrapped in `runCatching`/`withTimeoutOrNull(15_000)`; items via `SportsLiveItemBucketer.describe`. Cache in a `StateFlow<SportsLiveLoad?>`; `loadedAtMs` compared with `System.currentTimeMillis()` (inject a `nowMs: () -> Long` default for tests). `liveNow` = `combine(latest, prefs.showHomeRow, enabled)`.

- [ ] **Step 4: Run tests to verify they pass.** Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nuvio/tv/domain/repository/SportsLiveRepository.kt app/src/main/java/com/nuvio/tv/data/repository/SportsLiveRepositoryImpl.kt app/src/main/java/com/nuvio/tv/core/di/RepositoryModule.kt app/src/test/java/com/nuvio/tv/data/repository/SportsLiveRepositoryImplTest.kt
git commit -m "feat(sportslive): add repository that claims and loads addon catalogs"
```

---

### Task 5: Home exclusion and the "Live now" row

**Files:**
- Modify: `…/tv/ui/screens/home/HomeViewModel.kt` (constructor: add `internal val sportsLiveRepository: SportsLiveRepository`; add `internal var sportsLiveClaimedKeys: Set<String> = emptySet()`; collect `sportsLiveRepository.claimedCatalogKeys` in init and on change call `rebuildCatalogOrder(addonsCache)` + `loadAllCatalogsPipeline(addonsCache)`; collect `sportsLiveRepository.liveNow()` into a new `HomeUiState.liveNowItems: List<SportsLiveItem>`; after each home catalog load call `sportsLiveRepository.load()` in `viewModelScope` without blocking)
- Modify: `…/tv/ui/screens/home/HomeUiState.kt` (add `val liveNowItems: List<SportsLiveItem> = emptyList()`)
- Modify: `…/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt` L223-235 and `HomeViewModelCatalogUtils.kt` `buildDefaultCatalogOrder` (~L311): add `|| catalogKey(addon.id, it.apiType, it.id) in sportsLiveClaimedKeys` to the `filterNot`
- Modify: `…/tv/ui/screens/home/HomeViewModelCatalogPipeline.kt` `updateCatalogRowsPipeline` (~L720): when `liveNowItems.isNotEmpty()`, insert `HomeRow.Catalog(SportsLiveHomeRow.build(liveNowItems, title))` as the first row
- Create: `…/tv/ui/screens/home/SportsLiveHomeRow.kt`:
  ```kotlin
  object SportsLiveHomeRow {
      const val ADDON_ID = "nuvio.sportslive"; const val BASE_URL = "nuvio://sports-live"; const val CATALOG_ID = "live_now"
      fun build(items: List<SportsLiveItem>, title: String): CatalogRow  // type UNKNOWN, rawType "sport", hasMore=false, supportsSkip=false, items = items.map { it.meta }
      fun isLiveNowRow(addonBaseUrl: String) = addonBaseUrl == BASE_URL
  }
  ```
- Modify: `…/tv/ui/screens/home/HomeScreen.kt` L75-95: add params `onNavigateToSportsLive: () -> Unit = {}` and `onLiveNowItemClick: (SportsLiveItem) -> Unit = {}`; wrap the `onNavigateToDetail` passed down to the layouts: if `SportsLiveHomeRow.isLiveNowRow(addonBaseUrl)` → `viewModel.findLiveNowItem(itemId)?.let(onLiveNowItemClick)` else original; wrap `onNavigateToCatalogSeeAll` so `addonId == SportsLiveHomeRow.ADDON_ID` → `onNavigateToSportsLive()`
- Modify: `…/tv/ui/screens/addon/CatalogOrderViewModel.kt` L405 and `…/tv/ui/screens/settings/LayoutSettingsViewModel.kt` L1043: also filter out keys in `sportsLiveRepository.claimedCatalogKeys` (inject the repository; collect into a field)
- Modify: `app/src/main/res/values/strings.xml`: `<string name="sports_live_home_row_title">Live now</string>`
- Test: `app/src/test/java/com/nuvio/tv/ui/screens/home/SportsLiveHomeRowTest.kt` (pure), and extend an existing Home pipeline test or add `HomeSportsLiveExclusionTest.kt` using the same construction pattern as `ui/screens/home/HomePosterWatchedTest.kt`

**Interfaces:**
- Consumes: Task 4 `SportsLiveRepository`, `HomeRow.Catalog`, `CatalogRow`, `catalogKey(...)`.
- Produces: `HomeViewModel.findLiveNowItem(id: String): SportsLiveItem?`; `HomeScreen(onNavigateToSportsLive, onLiveNowItemClick)`.

- [ ] **Step 1: Write failing tests**

```kotlin
// SportsLiveHomeRowTest
@Test fun `build produces a row with the sentinel ids and the items metas`()
@Test fun `isLiveNowRow matches only the sentinel base url`()
// HomeSportsLiveExclusionTest (ViewModel with fakes, pattern HomePosterWatchedTest)
@Test fun `claimed catalog keys are left out of the home catalog order`()      // addon with 2 catalogs, 1 claimed -> order has 1 key
@Test fun `clearing claimed keys brings the catalog back without restart`()    // flow emits emptySet -> order has 2 keys
@Test fun `live now items produce a first HomeRow Catalog with the sentinel addon id`()
@Test fun `no live now row when liveNow is empty`()
```

- [ ] **Step 2: Run tests to verify they fail.** Run: `./gradlew :app:testFullDebugUnitTest --tests "com.nuvio.tv.ui.screens.home.SportsLiveHomeRowTest" --tests "com.nuvio.tv.ui.screens.home.HomeSportsLiveExclusionTest"`. Expected: FAIL.

- [ ] **Step 3: Implement** the modifications listed under Files. Keep the filter change to one added condition in each of the two places. `findLiveNowItem` reads `uiState.value.liveNowItems.firstOrNull { it.id == id }`. Existing HomeViewModel tests construct the ViewModel with many mocks: add `sportsLiveRepository = mockk(relaxed = true) { every { claimedCatalogKeys } returns flowOf(emptySet()); every { liveNow() } returns flowOf(emptyList()) }` wherever they break (search tests for `HomeViewModel(`).

- [ ] **Step 4: Run the new tests plus every existing Home test**

Run: `./gradlew :app:testFullDebugUnitTest --tests "com.nuvio.tv.ui.screens.home.*" --tests "com.nuvio.tv.ui.screens.addon.*" --tests "com.nuvio.tv.ui.screens.settings.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A app/src/main/java/com/nuvio/tv/ui/screens/home app/src/main/java/com/nuvio/tv/ui/screens/addon/CatalogOrderViewModel.kt app/src/main/java/com/nuvio/tv/ui/screens/settings/LayoutSettingsViewModel.kt app/src/main/res/values/strings.xml app/src/test/java/com/nuvio/tv/ui/screens/home
git commit -m "feat(sportslive): hide claimed catalogs from Home and add Live now row"
```

---

### Task 6: Route, sidebar entry and screen placeholder

**Files:**
- Modify: `…/tv/ui/navigation/Screen.kt` (after `Library` at L144): `data object SportsLive : Screen("sports_live")`
- Modify: `…/tv/ui/navigation/NuvioNavHost.kt`: add `rootComposable(Screen.SportsLive.route) { SportsLiveScreen(onPlay = …, onOpenDetail = …, onOpenAddons = { navController.navigate(Screen.AddonManager.route) }) }`; in the Home registration (L271-314) pass `onNavigateToSportsLive = { navController.navigate(Screen.SportsLive.route) { launchSingleTop = true } }` and `onLiveNowItemClick = { item -> navController.navigate(streamRouteFor(item)) }` where `streamRouteFor(item) = Screen.Stream.createRoute(videoId = item.id, contentType = item.apiType, title = item.meta.name, poster = item.meta.poster, backdrop = item.meta.backdropUrl, logo = item.meta.logo, contentId = item.id, contentName = item.meta.name, returnToHomeOnBack = true)` — put `streamRouteFor` in `…/tv/ui/screens/sportslive/SportsLiveRoutes.kt` so Task 7's screen uses the same one
- Modify: `…/tv/MainActivity.kt` L1101-1165: `rootRoutes` adds `Screen.SportsLive.route` when `sportsLiveAvailable`; `drawerItems` inserts `DrawerItem(Screen.SportsLive.route, strNavSportsLive, iconRes = R.raw.sidebar_sports)` after Home (after Discover if present); `sportsLiveAvailable` is `collectAsStateWithLifecycle` of `SportsLiveRepository.sectionAvailable` obtained through the existing `MainViewModel`/`mainUiPrefs` path (builder: look at how `discoverLocation` reaches this scope and mirror it)
- Create: `app/src/main/res/raw/sidebar_sports.svg` (24×24 outline icon, same stroke style as `sidebar_library.svg`; a simple trophy or play-in-circle is fine)
- Modify: `app/src/main/res/values/strings.xml`: `nav_sports_live` = `Sports &amp; Live`
- Create: `…/tv/ui/screens/sportslive/SportsLiveScreen.kt` with a placeholder body (`EmptyScreenState(title = stringResource(R.string.nav_sports_live))`) so the app compiles; Task 8 replaces it

- [ ] **Step 1: Make the changes above.**

- [ ] **Step 2: Verify compile**

Run: `./gradlew :app:compileFullDebugKotlin :app:compilePlaystoreDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add -A app/src/main/java/com/nuvio/tv/ui/navigation app/src/main/java/com/nuvio/tv/MainActivity.kt app/src/main/res/raw/sidebar_sports.svg app/src/main/res/values/strings.xml app/src/main/java/com/nuvio/tv/ui/screens/sportslive
git commit -m "feat(sportslive): add route and sidebar entry"
```

---

### Task 7: SportsLiveViewModel and UI state

**Files:**
- Create: `…/tv/ui/screens/sportslive/SportsLiveUiState.kt`
- Create: `…/tv/ui/screens/sportslive/SportsLiveViewModel.kt`
- Test: `app/src/test/java/com/nuvio/tv/ui/screens/sportslive/SportsLiveViewModelTest.kt`

**Interfaces:**
- Consumes: Task 4 repository, Task 2 bucketer.
- Produces:
  ```kotlin
  data class SportsLiveUiState(
      val isLoading: Boolean = true, val hasClaimedCatalogs: Boolean = false,
      val tabs: Map<SportsLiveTab, List<SportsLiveItem>> = emptyMap(),
      val selectedTab: SportsLiveTab = SportsLiveTab.LIVE_NOW,
      val sports: List<String> = emptyList(), val selectedSport: String? = null,   // null = All
      val visibleItems: List<SportsLiveItem> = emptyList(),                        // selectedTab filtered by selectedSport
      val selectedItemId: String? = null, val failures: List<SportsLiveFailure> = emptyList(),
      val lastRefreshedMs: Long? = null
  )
  @HiltViewModel class SportsLiveViewModel @Inject constructor(repo: SportsLiveRepository) : ViewModel() {
      val uiState: StateFlow<SportsLiveUiState>
      fun selectTab(tab: SportsLiveTab); fun selectSport(sport: String?); fun selectItem(id: String?)
      fun refresh(force: Boolean = true); fun onScreenVisible(visible: Boolean)   // visible -> 60 s auto refresh loop of LIVE_NOW
  }
  ```

- [ ] **Step 1: Write failing tests** (MockK `SportsLiveRepository` with `latest` as `MutableStateFlow`, `load` recording calls; `MainDispatcherRule`; `advanceTimeBy`)

```kotlin
@Test fun `initial load populates tabs and picks LIVE_NOW with the first item selected`()
@Test fun `selecting a sport filters visible items and keeps selection valid`()   // selected item not in filter -> selection moves to first visible
@Test fun `selecting UPCOMING shows items sorted by start`()
@Test fun `failures are exposed but items still show`()
@Test fun `auto refresh calls load every 60 seconds only while visible`()        // onScreenVisible(true); advanceTimeBy(61_000) -> 2 loads; onScreenVisible(false); advanceTimeBy(120_000) -> still 2
@Test fun `no claimed catalogs sets hasClaimedCatalogs false and stops loading`()
```

- [ ] **Step 2: Run tests to verify they fail.** Run: `./gradlew :app:testFullDebugUnitTest --tests "com.nuvio.tv.ui.screens.sportslive.SportsLiveViewModelTest"`. Expected: FAIL.

- [ ] **Step 3: Implement** the ViewModel: combine `repo.claimedCatalogs`, `repo.latest` and local selection state into `uiState`; `refresh(force)` launches `repo.load(force)` and copies `failures`; the auto-refresh loop is a `Job` started/cancelled by `onScreenVisible`.

- [ ] **Step 4: Run tests to verify they pass.** Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nuvio/tv/ui/screens/sportslive app/src/test/java/com/nuvio/tv/ui/screens/sportslive
git commit -m "feat(sportslive): add section view model"
```

---

### Task 8: Sports & Live screen UI

**Files:**
- Modify: `…/tv/ui/screens/sportslive/SportsLiveScreen.kt` (replace placeholder)
- Create: `…/tv/ui/screens/sportslive/SportsLiveCards.kt`
- Modify: `app/src/main/res/values/strings.xml` (strings below)

**Interfaces:**
- Consumes: Task 7 state and actions; `streamRouteFor` (Task 6); `EmptyScreenState`, `LoadingIndicator`, `ErrorState`, `LightweightCard`, `NuvioTheme`, `dpadRepeatThrottle` (`ui/util/DpadThrottleModifier.kt`), tab pattern from `FolderDetailScreen.TabbedGridContent` (L277-302: per-tab `FocusRequester`, `BackHandler` returning focus to the active tab).
- Produces: `@Composable fun SportsLiveScreen(viewModel: SportsLiveViewModel = hiltViewModel(), onPlay: (SportsLiveItem) -> Unit, onOpenDetail: (SportsLiveItem) -> Unit, onOpenAddons: () -> Unit)`.

Strings (`strings.xml`): `sports_live_tab_live_now` "Live Now", `sports_live_tab_upcoming` "Upcoming", `sports_live_tab_channels` "Channels", `sports_live_tab_replays` "Replays", `sports_live_filter_all` "All", `sports_live_watch` "Watch", `sports_live_details` "Details", `sports_live_refresh` "Refresh", `sports_live_live_pill` "LIVE", `sports_live_time_tba` "Time TBA", `sports_live_empty_tab` "Nothing here right now", `sports_live_no_addons_title` "No sports or live addons", `sports_live_no_addons_subtitle` "Install an addon that offers live sports or TV channels to use this section", `sports_live_open_addons` "Open add-ons", `sports_live_partial_failure` "%1$d source(s) didn\'t respond".

Layout (from the spec, section 7.3):
- Top row: tab chips with counts (`"Live Now 14"`), then sport chips (`All` + `uiState.sports`), then a Refresh chip at the end.
- Body: `Row` with a `LazyColumn` of cards taking 45% width and a details pane taking the rest.
- Card (`SportsLiveListCard(item, selected, onClick, onLongClick, onFocus, modifier)`): a `LightweightCard` with a 16:9 `AsyncImage` of `meta.poster` (fallback `backdropUrl`), a pill (red `LIVE` when `isLive`, else local time `h:mm a` from `startMs`, else `Time TBA` for Upcoming), title, second line `"${sport ?: catalogName} · $addonName"`.
- Pane (`SportsLiveDetailsPane(item, onWatch, onDetails, modifier)`): `backdropUrl` image at 16:9, title, meta line `sport · state · addonName`, description (max 6 lines), buttons Watch and Details.
- Focus: `onFocus` of a card calls `selectItem`; Right from the list moves focus to Watch (use a `FocusRequester` on Watch and `focusProperties { right = watchFocusRequester }` on cards); Left from Watch returns to the selected card; Up from the first card reaches the tab row. `BackHandler` when focus is in the list → focus active tab; otherwise default (sidebar).
- OK on a card and Watch both call `onPlay(item)`. Details and long-press call `onOpenDetail(item)`. For `REPLAY` items whose `apiType == "series"`, OK calls `onOpenDetail` instead of `onPlay`.
- States: `isLoading && tabs.isEmpty()` → `LoadingIndicator`; `!hasClaimedCatalogs` → `EmptyScreenState(title = no_addons_title, subtitle = …)` plus an "Open add-ons" button; a tab with no items → `EmptyScreenState(sports_live_empty_tab)`; `failures.isNotEmpty()` → a single-line notice under the chips with `sports_live_partial_failure`.
- `DisposableEffect`/lifecycle: call `viewModel.onScreenVisible(true)` on `ON_RESUME` and `false` on `ON_PAUSE` (same `LocalLifecycleOwner` pattern as `HomeScreen`).
- In `NuvioNavHost`, `onPlay = { navController.navigate(streamRouteFor(it)) }`, `onOpenDetail = { navController.navigate(Screen.Detail.createRoute(itemId = it.id, itemType = it.apiType, addonBaseUrl = it.addonBaseUrl)) }` (check `Screen.Detail.createRoute` parameter names at `Screen.kt` L8-29).

- [ ] **Step 1: Implement the screen and cards as specified.**

- [ ] **Step 2: Verify compile for both flavors.** Run: `./gradlew :app:compileFullDebugKotlin :app:compilePlaystoreDebugKotlin`. Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Build and install the debug APK on a TV or emulator, open Sports & Live, and check:** tabs switch, sport chips filter, focus moves list → Watch → back, OK opens stream selection and a highfly stream plays, long-press opens detail, Refresh reloads, Back returns to the sidebar. Record what was checked in the commit message body.

Run: `./gradlew :app:assembleFullDebug` then `adb install -r app/build/outputs/apk/full/debug/app-full-arm64-v8a-debug.apk` (or the universal APK for an emulator).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nuvio/tv/ui/screens/sportslive app/src/main/res/values/strings.xml app/src/main/java/com/nuvio/tv/ui/navigation/NuvioNavHost.kt
git commit -m "feat(sportslive): add tabbed list and details pane screen"
```

---

### Task 9: Settings category

**Files:**
- Modify: `…/tv/ui/screens/settings/SettingsCatalog.kt`: add `SPORTS_LIVE(SettingsRailGroup.WATCH)` immediately after `CONTENT_DISCOVERY`; no visibility rule (always visible)
- Modify: `…/tv/ui/screens/settings/SettingsScreen.kt`: `SettingsSectionSpec(category, stringResource(R.string.settings_sports_live), Icons.Default.SportsSoccer /* or LiveTv */, destination = Inline)` at ~L162, a focus-requester entry at ~L229-240, and `SettingsCategory.SPORTS_LIVE -> SportsLiveSettingsContent(...)` in the `when` at ~L857-982
- Create: `…/tv/ui/screens/settings/SportsLiveSettingsViewModel.kt` (`@HiltViewModel`; injects `SportsLivePreferencesDataStore`, `AddonRepository`, `SportsLiveRepository`; exposes `showSection`, `showHomeRow`, and `catalogs: List<SportsLiveCatalogSetting(key, addonName, catalogName, apiType, autoClaim: SportsLiveClaim?, override: SportsLiveOverride?)>` for every catalog of every enabled addon; `setShowSection`, `setShowHomeRow`, `cycleOverride(key)` which goes Auto → INCLUDE → EXCLUDE → Auto)
- Create: `…/tv/ui/screens/settings/SportsLiveSettingsContent.kt`: two `SettingsToggleRow`s (`SettingsDesignSystem.kt` L740) then a "Catalogs" section listing each catalog as a row whose subtitle reads `"Auto · in Sports & Live (type:sport)"`, `"Auto · on Home"`, `"Included"` or `"Excluded"`; OK cycles the override. Use the existing section header/row components from `SettingsSectionComponents.kt`/`SettingsValueRows.kt`.
- Modify: `strings.xml`: `settings_sports_live` "Sports &amp; Live", `sports_live_setting_show_section` "Show Sports &amp; Live section", `sports_live_setting_show_section_subtitle` "Moves live sports and TV catalogs out of Home into their own section", `sports_live_setting_home_row` "Show \"Live now\" row on Home", `sports_live_setting_catalogs` "Catalogs", `sports_live_override_auto_in` "Auto · in Sports &amp; Live", `sports_live_override_auto_out` "Auto · on Home", `sports_live_override_included` "Included", `sports_live_override_excluded` "Excluded".
- Test: update `app/src/test/java/com/nuvio/tv/ui/screens/settings/SettingsCatalogTest.kt` expected list (insert `SPORTS_LIVE` after `CONTENT_DISCOVERY`); add `SportsLiveSettingsViewModelTest.kt` with `cycleOverride goes Auto Include Exclude Auto`.

- [ ] **Step 1: Update `SettingsCatalogTest` and write the ViewModel test; run them to see them fail.**

Run: `./gradlew :app:testFullDebugUnitTest --tests "com.nuvio.tv.ui.screens.settings.SettingsCatalogTest" --tests "com.nuvio.tv.ui.screens.settings.SportsLiveSettingsViewModelTest"`. Expected: FAIL.

- [ ] **Step 2: Implement** the category, content and ViewModel.

- [ ] **Step 3: Run tests; verify PASS. Then compile both flavors.**

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/nuvio/tv/ui/screens/settings app/src/main/res/values/strings.xml app/src/test/java/com/nuvio/tv/ui/screens/settings
git commit -m "feat(sportslive): add settings page with catalog overrides"
```

---

### Task 10: Full build, full unit test run, and device verification

**Files:** none new. Modify `docs/superpowers/specs/2026-11-15-sports-live-section-design.md` status line to `implemented` only after this task passes.

- [ ] **Step 1: Run the whole unit test suite for the full flavor**

Run: `./gradlew :app:testFullDebugUnitTest`
Expected: BUILD SUCCESSFUL, 0 failures. Fix any regression before continuing.

- [ ] **Step 2: Build both flavors**

Run: `./gradlew :app:assembleFullDebug :app:assemblePlaystoreDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Device checks** (install the full debug APK; sign in or add the three addons by URL in Add-ons):
  1. Home: no highfly / Sports Replay rows between movie rows; a "Live now" row under Continue Watching when something is live; OK on a card opens stream selection; "See all" opens the section.
  2. Sidebar shows Sports & Live; opening it shows Live Now with highfly items; Channels shows the TSN/ESPN leaf items; Replays shows Sports Replay games; Upcoming is sorted by time.
  3. OK on a `sport` item → stream list → a stream plays. OK on a Sports Replay `movie` → stream list shows TorBox/Torrent.
  4. Settings → Sports & Live → turn the section off: sidebar entry gone, Home rows back immediately. Turn it on: rows gone again, entry back.
  5. Switch to a second profile with different addons: the section follows.
  6. Catalog override: exclude a highfly catalog → it returns to Home; include a Cinemeta catalog → it appears in the section's Events.

- [ ] **Step 4: Record results** in `D:\Nuvio\tasks\todo.md` (Phase 3 review section) with what passed and what did not.

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/specs/2026-11-15-sports-live-section-design.md
git commit -m "docs(sportslive): mark design implemented"
```
