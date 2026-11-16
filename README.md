# Nuvio Live

**Nuvio Live** is a fork of [Nuvio TV](https://github.com/NuvioMedia/NuvioTV) with a **Sports & Live** experience for Android TV: live games, upcoming events, live TV channels and replays from the addons you install, all in one place built for the remote.

> Nuvio Live does not provide any content; it works with addons you install.

## Features

- **Sports & Live section** with Live Now, Upcoming, Channels and Replays tabs, plus one "Live now" row on Home ([#1](https://github.com/edetruth/NuvioTV/issues/1))
- Sport filter chips and channel groups ([#2](https://github.com/edetruth/NuvioTV/issues/2)), times in your local time zone ([#3](https://github.com/edetruth/NuvioTV/issues/3))
- Unplayable streams and ended games are hidden ([#4](https://github.com/edetruth/NuvioTV/issues/4), [#5](https://github.com/edetruth/NuvioTV/issues/5))
- Works with any sports or live TV addon ([#6](https://github.com/edetruth/NuvioTV/issues/6)), with an optional per-catalog filter ([#8](https://github.com/edetruth/NuvioTV/issues/8))
- **Zap** between games with Up/Down while watching, a live games panel on Left, and rewind ([#7](https://github.com/edetruth/NuvioTV/issues/7))
- **My Teams**: follow teams and see their games first ([#9](https://github.com/edetruth/NuvioTV/issues/9))
- One card per game across addons, with automatic stream failover ([#10](https://github.com/edetruth/NuvioTV/issues/10))
- **Dual screen**: two games side by side, sound follows the highlight ([#11](https://github.com/edetruth/NuvioTV/issues/11))
- Hover preview ([#12](https://github.com/edetruth/NuvioTV/issues/12)), mini player ([#13](https://github.com/edetruth/NuvioTV/issues/13)) and picture-in-picture ([#14](https://github.com/edetruth/NuvioTV/issues/14))

## Install on Android TV

- **Downloader code:** coming soon.
- **APK:** download the latest from [github.com/edetruth/NuvioTV/releases/latest](https://github.com/edetruth/NuvioTV/releases/latest).
- Nuvio Live has its own app ID, so it installs **next to** the official Nuvio app instead of replacing it.

## Updates

In-app updates come from this fork's releases. The fork syncs with upstream Nuvio TV daily, so upstream fixes and features keep arriving.

## Roadmap

- Live scores: score chips, linescore, Results and a spoiler setting ([#17](https://github.com/edetruth/NuvioTV/issues/17))
- Game page with Box Score and Standings ([#18](https://github.com/edetruth/NuvioTV/issues/18))
- Plays tab, then UFC and tennis ([#19](https://github.com/edetruth/NuvioTV/issues/19))
- Release setup ([#15](https://github.com/edetruth/NuvioTV/issues/15)) and TV box testing ([#16](https://github.com/edetruth/NuvioTV/issues/16))

All open items: [issues](https://github.com/edetruth/NuvioTV/issues).

Nuvio Live is based on [NuvioMedia/NuvioTV](https://github.com/NuvioMedia/NuvioTV). Credit for the app itself goes to the Nuvio team. Please report Nuvio Live problems here, not upstream.

---

<div align="center">

  <img src="assets/brand/app_logo_wordmark.png" alt="Nuvio" width="300" />

  <p>
    A free, open-source media app for your phone, your desktop, and the TV you already own.
    <br />
    Bring your own sources. Nuvio turns them into a library with artwork, ratings, subtitles, and your place saved on every screen.
  </p>

  [Website](https://nuvio.tv) · [GitHub releases](https://github.com/NuvioMedia/NuvioTV/releases/latest) · [Support Nuvio](https://nuvio.tv/support)

</div>

## Get the original Nuvio TV

These links are for the official Nuvio TV app, not Nuvio Live:

- [Android TV on Google Play](https://play.google.com/store/apps/details?id=com.nuvio.app)
- [Android TV APK](https://github.com/NuvioMedia/NuvioTV/releases/latest)

## Build from source

To build the original Nuvio TV:

```bash
git clone https://github.com/NuvioMedia/NuvioTV.git
cd NuvioTV
./gradlew :app:assembleFullDebug
```

To build Nuvio Live, clone `https://github.com/edetruth/NuvioTV.git` instead and run the same command.

Nuvio TV is built with Kotlin, Jetpack Compose, TV Material 3, and Media3. Development requires Android Studio, a JDK, and the Android SDK.

## License

[GNU General Public License v3.0](./LICENSE)
