# Fork release setup — design

Date: 2026-11-16
Status: approved (the user asked for the app's own ID, auto-update like the Launcher fork and a scheduled sync/build job; "after you are done with this build, go ahead and start on the release. i'm eager to test on the tv.")

## Intent

Install the fork on the onn box next to the official Nuvio, keep it updated from the user's GitHub fork (edetruth/NuvioTV), and keep pulling upstream Nuvio changes in automatically.

## Rulings

1. **Identity (full flavor only):** applicationId `io.github.edetruth.nuviotv`; app name "Nuvio Live" (debug: "Nuvio Live Debug"). The playstore flavor stays as upstream. Check everything tied to the package name: FileProvider/ContentProvider authorities (`${applicationId}` placeholders), deep links/intent filters, WorkManager/notification channels, any hard-coded `com.nuvio.tv` strings used at runtime (not the Kotlin package, which stays `com.nuvio.tv`). Sign-in flows that redirect back to the app (Trakt uses the OOB redirect; TV login uses a QR/web code) must still work — verify by reading the code.
2. **Version:** `versionName = "<upstream versionName>-live.<N>"`, `versionCode = upstream versionCode * 100 + N` so ours is always newer than the matching upstream build and increases with every fork release. N comes from a single property in `gradle.properties` (`NUVIO_LIVE_BUILD`), bumped by the release workflow.
3. **In-app updater:** points at `edetruth/NuvioTV` (`GITHUB_OWNER`/`GITHUB_REPO` build config). Confirm how the updater chooses the asset (ABI split names, universal APK) and compares versions, and make the fork's release assets match what it expects. Only full-flavor releases.
4. **Signing:** a new release keystore for the fork (RSA 4096, 30-year validity), generated locally at `D:\Nuvio\tools\keys\nuvio-live-release.jks`, passwords generated randomly and stored only in `local.properties` (gitignored) and as GitHub Actions secrets on edetruth/NuvioTV. The user keeps a backup of the .jks and passwords (we tell them where). Never committed.
5. **Build secrets:** release builds read API keys from `local.properties`. The workflow restores it from a `LOCAL_PROPERTIES_BASE64` secret containing only the keys the fork needs (no sdk.dir, no store path). Missing optional keys must not fail the build.
6. **Release branch:** the fork's default branch becomes `live` (created from `feature/sports-live-section`); scheduled workflows only run on the default branch.
7. **Scheduled sync + release workflow** (`.github/workflows/live-sync-release.yml`, fork-only — guarded by `if: github.repository == 'edetruth/NuvioTV'`):
   - Daily (and `workflow_dispatch`): fetch upstream `NuvioMedia/NuvioTV` `dev`, merge into `live`.
   - Merge conflict → abort, open (or update) one GitHub issue "Upstream merge needs attention" with the conflicting files; no release.
   - Clean merge with new upstream commits, or a manual run → run unit tests (known flaky tests excluded by name), build `assembleFullRelease`, bump `NUVIO_LIVE_BUILD`, commit + push, tag `v<versionName>`, publish a GitHub release with the APKs (universal + per-ABI) and short notes (upstream commits merged + fork commits since last tag).
   - No new commits and not manual → do nothing.
   - Upstream's own workflows that would misfire on the fork (release, PR comment bots, stale-issue bots) are disabled on the fork via an `if: github.repository == 'NuvioMedia/NuvioTV'` guard or by being left unchanged if they already only run on dispatch — decide per file, minimal edits.
8. **First release:** built and published by the workflow (manual dispatch) after secrets are set. The user then registers the APK download link (stable `releases/latest/download/<universal apk>` URL) at aftv.news for a Downloader code.
9. **Privacy:** no addon URLs/tokens anywhere; the pre-push scan runs before every push; secrets only via `gh secret set`.

## Non-goals

Play Store publishing. Auto-merging upstream when conflicts exist. Signing the playstore flavor.

## Risks

- Upstream's in-app updater may assume upstream's tag/asset naming — verify and adapt naming rather than patch updater logic where possible.
- Account sign-in/sync depends on the Supabase values in `local.properties`; the release must carry the same values the debug build uses, or sign-in won't work.
- Public releases: anyone can download the APK (contains no private data).

## Verification

`assembleFullRelease` locally with the new keystore; install the release APK on the emulator next to the debug build (different IDs) and launch; updater points at the fork (unit test or code check); workflow lint (`actionlint` if available) and one manual dispatch run on GitHub producing a release with the universal APK.
