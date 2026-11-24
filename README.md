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
- **New in live.26:** Live now opens settled (no empty flash, rows stay put); "All sports" next to My sports, with channel up/down through the whole list; Dual Screen picker rebuilt from Live now; "Watch with" your team's game; hidden replays open under the matchup name with link names masked; Home follows the Scores setting at once. Known issues: Hold OK in a replay, replay team folders (#240).
- **New in live.25:** one shared game order for every live list and channel up/down; coming back from the player lands on the game you were watching; Live now refreshes cleanly after a long absence; a screenshot is saved next to problem reports; polish across the Sports & Live screens.
- **New in live.24:** opens much faster on low-end boxes (first list in about 2-4 s, was about 9 s); one consistent
  game view everywhere (college ranks, tags and logos, "Final · OT"); Channels from IPTV lists with country tags and
  favorites; dual screen: Left/Right swap the big game, the picker lists every live game, alerts go to the quiet side;
  alerts: OK with a short countdown, Watch both, a spoiler-free full time card, and alerts wait their turn instead of
  being dropped; scores stay put on busy days. Earlier: live.23 added Live Now ranked by importance, the in-player
  games table and Results that hide your teams' results until you reveal them.
- **Planned:** live.25 — one shared order for every live list and channel flipping, a grouped "watch with another
  game" picker, a pinned corner score, replay labels, league channels, Results standings and stats, and more speed —
  see the [issues](https://github.com/edetruth/NuvioTV/issues).

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
