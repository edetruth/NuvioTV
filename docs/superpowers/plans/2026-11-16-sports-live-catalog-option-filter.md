# Catalog Option Filter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Let the user pin one option of a claimed catalog's optional extra (e.g. a private addon Live TV genre = United States) so Sports & Live loads only those items.

**Spec:** `docs/superpowers/specs/2026-11-16-sports-live-catalog-option-filter-design.md`

## Global Constraints

- Eligible extra: optional, non-empty `options`; prefer name `genre`, else the first eligible.
- Prefs key `sports_live_catalog_options`: Gson map `catalogKey -> {"extra": name, "value": value}`; per profile.
- Repository passes `extraArgs = mapOf(extra to value)`; pinned value is part of the cached-catalog identity used by carry-over and the cache freshness check.
- Strings (values/strings.xml only): `sports_live_filter_action` ("Filter: %1$s"), `sports_live_filter_all_option` ("All"), `sports_live_filter_picker_title` ("Show only"), `sports_live_stat_no_items_for` ("No items for %1$s").
- Memory sequencing for builds: stop emulator + daemons → build → `./gradlew --stop` → start emulator → install/check. One Gradle run at a time.
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01K2go64gSK4YGNPmEjTxDuk`.

## Review Focus

1. A pinned value that the addon no longer offers (options changed) → treated as "All" with no crash; the stale pin is shown as "Filter: All".
2. Changing the pin refetches only that catalog.
3. Profile switch: pins follow the profile.

### Task C1: Option detection, storage, repository loading
`core/sportslive/SportsLiveCatalogOptions.kt` (pure: `eligibleExtra(catalog): CatalogExtra?`), prefs store additions (`catalogOptions: Flow<Map<String, CatalogOption>>`, `setCatalogOption(key, option?)`), repository passes extraArgs and includes the value in cache/carry-over identity; tests for each. Commit `feat(sportslive): pin a catalog option to load only matching items`.

### Task C2: Settings picker + stats wording
Settings VM exposes per-catalog `eligibleOptions` and current pin; content adds the "Filter: …" action and picker dialog; stats line uses "No items for %1$s" when a pinned catalog loaded zero items. Tests for VM; both flavor compiles; `assembleFullDebug`; emulator screenshot of the picker (any addon with options — a private addon event catalogs or EasyPlay if installed; otherwise use a fixture-backed unit test and mark the emulator check UNCLEAR). Commit `feat(sportslive): choose a catalog filter in settings`.
