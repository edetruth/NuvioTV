<div align="center">

  <img src="assets/brand/app_logo_wordmark.png" alt="Nuvio" width="300" />

  <p>
    A free, open-source media app for your phone, your desktop, and the TV you already own.
    <br />
    Bring your own sources. Nuvio turns them into a library with artwork, ratings, subtitles, and your place saved on every screen.
  </p>

  [Website](https://nuvio.tv) · [GitHub releases](https://github.com/NuvioMedia/NuvioTV/releases/latest) · [Support Nuvio](https://nuvio.tv/support)

</div>

## Nuvio Live Sports (this fork)

An unofficial fork of Nuvio TV with a **Sports & Live** section for the live and sports catalogs of the addons you
already use. It installs next to the official app (`io.github.edetruth.nuviotv`) and follows upstream daily.

- **Install:** [latest universal APK](https://github.com/edetruth/NuvioTV/releases/latest/download/app-full-universal-release.apk) (in-app updates on the Beta channel).
- **What's in it:** Live Now, Upcoming, Replays, Results and Channels tabs; one card per game across addons; live
  scores, records and rankings with spoiler controls (Scores Show / Hide / Off, Watch later); Game, Team and Player
  pages with a pre-game Preview; followed teams, reminders and actionable alerts; channel zapping with an in-player
  games panel; dual screen (side by side or big + small) and a mini player; stream health with automatic failover
  and fast starts (about 4 s on an onn 4K Pro); "▶ Ready" for your teams' games; a Your teams strip; Report a
  problem (hold OK on any screen) with privacy-safe reports and crash capture; first-run setup; settings synced with
  your Nuvio account.
- **New in live.23:** faster tabs and calmer loading; Live Now ranked by importance with a single "More live" row
  instead of All sports; scores shown about a second after the games; the in-player games table is the default (opens
  on Now playing, with Just watched); Results keeps your teams' results hidden until you reveal them, with Jump to;
  Channels favorites and browse; dual screen keeps the game you hear on Back without a reload; video decoder safety.
- **Planned:** live.24 — speed on low-end boxes, one consistent game view everywhere (college ranks in the table),
  Channels from IPTV lists; live.25 — grouped "watch with another game" picker, pinned corner score, replay labels,
  league channels, Results standings and stats — see the [issues](https://github.com/edetruth/NuvioTV/issues).

## Get Nuvio TV

- [Android TV on Google Play](https://play.google.com/store/apps/details?id=com.nuvio.app)
- [Android TV APK](https://github.com/NuvioMedia/NuvioTV/releases/latest)

## Build from source

```bash
git clone https://github.com/NuvioMedia/NuvioTV.git
cd NuvioTV
./gradlew :app:assembleFullDebug
```

Nuvio TV is built with Kotlin, Jetpack Compose, TV Material 3, and Media3. Development requires Android Studio, a JDK, and the Android SDK.

## License

[GNU General Public License v3.0](./LICENSE)
