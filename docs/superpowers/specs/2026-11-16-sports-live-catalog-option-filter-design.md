# Sports & Live: catalog option filter — short design

Date: 2026-11-16
Status: approved (user asked for "live TV just for US channels"; approach in the controller's recommendation, user said "Keep going on all of this until completion")
Depends on: Sports & Live section, follow-on (stats, groups)

## Intent

CNCVerse's "EasyPlay Live TV" catalog lists ~4,800 channels from ~130 countries. The addon itself supports a `genre` catalog extra whose options are countries ("United States", "United Kingdom", …). Sports & Live currently loads every claimed catalog with no extras, so the user gets the whole list. Let the user pin one option value per catalog so the section asks the addon only for that (e.g. US channels only). Generic: any addon whose catalog declares an optional extra with `options` (CNCVerse's event catalogs expose sport options too).

## Rulings

1. **Which catalogs:** claimed catalogs that declare an optional (non-required) extra with a non-empty `options` list. If several such extras exist, prefer `genre`; else the first. One pinned value per catalog.
2. **Storage:** per profile in `SportsLivePreferencesDataStore`, key `sports_live_catalog_options` (Gson map catalogKey → {extraName, value}). Clearing returns to "All".
3. **Loading:** `SportsLiveRepositoryImpl.load` passes `extraArgs = mapOf(extraName to value)` to `CatalogRepository.getCatalog` for catalogs with a pinned value. The pinned value is part of the cache/carry-over identity (a changed value forces a refetch of that catalog).
4. **Settings UI:** in Settings → Sports & Live, each catalog row that supports options shows a second action "Filter: All" / "Filter: United States"; OK opens a picker dialog listing "All" + the options (scrollable, D-pad friendly). The stats line keeps working.
5. **Empty result guard:** if a pinned value returns zero items, the stats line shows "No items for United States" so the user knows the filter, not the addon, emptied it.
6. **No change** to the section UI itself; the Channels tab's group chips still work on whatever was loaded.

## Non-goals

Multiple values per catalog. Free-text search extras. Filtering catalogs whose extras have no `options`.

## Verification

Unit: option detection (genre preferred, required extras ignored), prefs round-trip and per-profile isolation, repository passes extraArgs and refetches on change, carry-over identity includes the value. Emulator: CNCVerse (if the user reinstalls it and the addon is serving data) pinned to "United States" shows only US channels; otherwise UNCLEAR with unit evidence.
