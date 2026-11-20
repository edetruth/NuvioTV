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
  and faster starts; first-run setup; settings synced with your Nuvio account.
- **Planned:** a Results redesign, a Channels batch (logos, naming, guide), a "Report a problem" shortcut and more —
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
