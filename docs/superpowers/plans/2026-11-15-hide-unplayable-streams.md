# Hide Unplayable Streams Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Drop addon "streams" that can never play (site-root or `/health` links) at the repository boundary so every stream consumer ignores them.

**Architecture:** A pure predicate in `core/streams/` applied in `StreamRepositoryImpl` after DTO→domain mapping (per-addon fetch and inline-meta streams) and in `StreamScreenViewModel.getEmbeddedStreamsFromMeta`.

**Tech Stack:** Kotlin, JUnit4, MockK, Moshi.

**Spec:** `docs/superpowers/specs/2026-11-15-hide-unplayable-streams-design.md`

## Global Constraints

- Junk = `url` scheme http/https AND path (query ignored, case-insensitive) in {"", "/", "/health"}. Never junk: `magnet:`, non-null `infoHash`, `ytId`, `externalUrl` with null `url`, debrid/server streams.
- Branch `feature/sports-live-section`; `source /d/Nuvio/tools/env.sh`; `./gradlew --stop` before each run; one Gradle run at a time.
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01K2go64gSK4YGNPmEjTxDuk`.

## Review Focus

1. A legitimate stream whose URL is a short redirect like `https://cdn.example.com/` would be dropped — acceptable by rule; test documents it.
2. A stream with both `url` junk-shaped and a usable `infoHash` must be kept.
3. An addon whose every stream is junk yields an empty addon group, not an error.

---

### Task J1: Predicate, repository filtering, tests, emulator check

**Files:**
- Create: `app/src/main/java/com/nuvio/tv/core/streams/UnplayableStreamFilter.kt` — `object UnplayableStreamFilter { fun isUnplayableJunk(stream: Stream): Boolean }`
- Modify: `app/src/main/java/com/nuvio/tv/data/repository/StreamRepositoryImpl.kt` (after `toDomain` in `getStreamsFromAddon` ~712-714 and `fetchInlineStreamsFromMeta` ~777-779: `.filterNot(UnplayableStreamFilter::isUnplayableJunk)`)
- Modify: `app/src/main/java/com/nuvio/tv/ui/screens/stream/StreamScreenViewModel.kt` (`getEmbeddedStreamsFromMeta` ~1196-1222, same filter)
- Test: `app/src/test/java/com/nuvio/tv/core/streams/UnplayableStreamFilterTest.kt`; fixture `app/src/test/resources/streams/highfly_streams.json` (copy of `D:\Nuvio\scratch\addons\highfly.stream.json` with the `ft=` JWT query value replaced by `REDACTED`; verify `grep -c "eyJ" ` = 0)

- [ ] **Step 1: Failing tests**
```kotlin
@Test fun `google root link is junk`()                 // url "https://www.google.com" -> true
@Test fun `health link is junk`()                      // "https://sports.example.invalid/health" -> true
@Test fun `m3u8 playlist is not junk`()                // "https://x.cfd/m3u/1/live.m3u8?ft=REDACTED" -> false
@Test fun `magnet and infoHash are never junk`()
@Test fun `external url only entry is not junk`()      // url null, externalUrl "https://pengu.uk/donate" -> false
@Test fun `junk shaped url with infoHash is kept`()
@Test fun `highfly fixture keeps only the leaf stream`() // map fixture via StreamDto.toDomain, filter -> 1 stream named "Leaf (Strong)…"
@Test fun `first stream autoplay skips junk`()           // StreamAutoPlaySelector.selectAutoPlayStream(filtered list, FIRST_STREAM, ...) returns the Leaf stream
```
- [ ] **Step 2:** `./gradlew :app:testFullDebugUnitTest --tests "com.nuvio.tv.core.streams.UnplayableStreamFilterTest"` → FAIL.
- [ ] **Step 3:** Implement predicate (use `java.net.URI`, wrapped in `runCatching` → not junk on parse failure) and the three filter call sites.
- [ ] **Step 4:** Same test → PASS; also `--tests "com.nuvio.tv.data.repository.*" --tests "com.nuvio.tv.ui.screens.stream.*"` → PASS; compile both flavors.
- [ ] **Step 5: Emulator** (`nuvio_tv`, reinstall x86_64 debug APK): open a highfly live game → stream list has no "Upgrade to watch"/"Tip" entries; play the Leaf stream; press Up → Sources → panel lists only real streams; switch to another source if more than one exists. Screenshots `/d/Nuvio/scratch/screens/junk-*.png`.
- [ ] **Step 6:** Commit `feat(streams): hide addon streams that can never play`.
