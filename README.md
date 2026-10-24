
<p align="center">
  <img src="assets/NekoPlayer.svg" alt="App Icon" height="250"/>
</p>

<p align="center">
  <a href="https://github.com/hcy917/NekoPlayer/releases/latest">
    <img src="https://img.shields.io/github/v/release/hcy917/NekoPlayer?include_prereleases&logo=github&style=for-the-badge&label=Latest%20Release" alt="Latest release">
  </a>
  <a href="https://github.com/hcy917/NekoPlayer/releases">
    <img src="https://img.shields.io/github/downloads/hcy917/NekoPlayer/total?logo=github&style=for-the-badge" alt="Total downloads">
  </a>
  <a href="https://github.com/sponsors/hcy917">
    <img src="https://img.shields.io/badge/Sponsor-GitHub%20Sponsors-EA4AAA?style=for-the-badge&logo=githubsponsors&logoColor=white" alt="Sponsor on GitHub Sponsors">
  </a>
  <img src="https://img.shields.io/badge/Android-11%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android 11+">
  <img src="https://img.shields.io/badge/License-GPLv3-blue?style=for-the-badge" alt="GPLv3 license">
</p>

<p align="center">
  <img src="assets/screenshot1.jpeg" alt="NekoPlayer home screen" width="205"/>
  <img src="assets/screenshot2.jpeg" alt="NekoPlayer now playing screen" width="205"/>
  <img src="assets/screenshot3.jpeg" alt="NekoPlayer library screen" width="205"/>
  <img src="assets/screenshot4.jpeg" alt="NekoPlayer lyrics screen" width="205"/>
</p>

## What It Is

NekoPlayer is an Android music player maintained by [@hcy917](https://github.com/hcy917). It focuses on local playback, self-hosted music libraries, expressive Material 3 UI, and user-controlled online lookups.

The app works offline by default. Optional online services are disabled until you enable them in setup or settings.

Package name: `moe.shimmerfly.nekoplayer`

## Why This Exists

NekoPlayer keeps the player FOSS-oriented and removes integrations that are not part of that direction.

Removed integrations include Telegram, NetEase, QQ Music, Google Drive, Gemini, Cast, Wear OS, Play Store billing, Firebase, Crashlytics, and Google Play Services runtime dependencies.

Cloud playback is limited to self-hosted sources: Navidrome/Subsonic and Jellyfin.

## Features

| Area | Highlights |
| --- | --- |
| Playback | Media3 playback engine, FFmpeg support, gapless playback, crossfade, custom transitions, queue controls, shuffle, repeat, sleep timer, external file playback |
| Library | Local scanning for MP3, FLAC, AAC, OGG, WAV, M4A, albums, artists, genres, folders, favorites, playlists, stats, metadata editing |
| Self-hosted | Navidrome/Subsonic and Jellyfin login, sync, streaming, artwork, and app-private offline downloads |
| Lyrics | Embedded lyrics, local `.lrc` files, lyrics import/editing, optional LRCLIB lookup |
| Artwork | Local artwork, album-art palette extraction, optional Deezer artist image lookup |
| Metadata | On-demand MusicBrainz matching for recording, release, and artist identifiers |
| UI | Jetpack Compose, Material 3, dynamic color, light/dark themes, Glance widgets, animated player surfaces |
| Backup | Preferences, playlists, favorites, lyrics, stats, and app state backup/restore |

## Online Services

NekoPlayer separates offline playback from network lookups.

| Service | Purpose | Default |
| --- | --- | --- |
| Navidrome/Subsonic | Self-hosted library sync, streaming, and offline downloads | User login required |
| Jellyfin | Self-hosted library sync, streaming, and offline downloads | User login required |
| MusicBrainz | On-demand metadata matching and identifier enrichment | Only when requested |
| LRCLIB | Search online lyrics when local or embedded lyrics are missing | Off |
| Deezer | Fetch missing artist artwork and cache it locally | Off |

LRCLIB and Deezer can be enabled during first-run setup or later from `Settings > Music Management > Optional online services`. MusicBrainz searches run only when you choose the lookup action for a track.

## Requirements

| Requirement | Version |
| --- | --- |
| Android | 11 or newer, API 30+ |
| JDK | 21 |
| Android SDK | compile/target 37 |

## Build From Source

Clone the repository:

```sh
git clone https://github.com/hcy917/NekoPlayer.git
cd NekoPlayer
```

Build the debug APK:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleDebug
```

Build one universal debug APK for local installation:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleDebug -Pnekoplayer.enableAbiSplits=false
```

Build a universal unsigned release APK suitable for F-Droid verification:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleRelease -Pnekoplayer.enableAbiSplits=false -Pnekoplayer.disableReleaseSigning=true
```

Run unit tests:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:testDebugUnitTest
```

Generate the baseline profile with a connected device or emulator:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :baselineprofile:generateBaselineProfile
```

## Download

GitHub releases are available at:

```text
https://github.com/hcy917/NekoPlayer/releases
```

Obtainium app id:

```text
moe.shimmerfly.nekoplayer
```

Public releases are planned on a regular weekly cadence when `main` passes the release checklist.

This fork is distributed through GitHub releases only; it is not published on F-Droid. The upstream F-Droid metadata and packaging notes are retained in `fastlane/metadata/android/en-US` and [docs/FDROID.md](docs/FDROID.md) in case a listing is ever requested.

### Alpha builds

Every merge into `main` automatically publishes a pre-release tagged like `v0.3.0-alpha.N` on the [releases page](https://github.com/hcy917/NekoPlayer/releases). These are cutting-edge builds for testing new changes before they reach a stable release — expect rough edges.

To install one, download the APK for your device from the release assets and sideload it: `arm64-v8a` fits most modern devices, `armeabi-v7a` is for older 32-bit ones.

Alpha builds are signed with a dedicated CI key, so they update over each other, but switching between alpha and a stable install requires an uninstall/reinstall. In Obtainium, enable "Include prereleases" to get alphas automatically.

## Support

If NekoPlayer is useful to you, you can support ongoing development through [GitHub Sponsors](https://github.com/sponsors/hcy917).

## Project Structure

```text
app/src/main/java/moe/shimmerfly/nekoplayer/
- data/             Room, repositories, preferences, services, workers
- di/               Hilt modules and qualifiers
- presentation/     Compose screens, components, navigation, ViewModels
- ui/               Theme and Glance widgets
- utils/            Shared utilities

baselineprofile/      Macrobenchmark and baseline profile generation
```

## Tech Stack

| Area | Technology |
| --- | --- |
| Language | Kotlin |
| UI | Jetpack Compose |
| Design | Material 3 |
| Playback | AndroidX Media3, ExoPlayer, FFmpeg |
| Database | Room |
| Dependency Injection | Hilt |
| Preferences | DataStore |
| Background Work | WorkManager |
| Networking | Retrofit, OkHttp |
| Images | Coil |
| Metadata | TagLib |

## Contributing

Contributions are welcome. Open an issue or pull request with a focused change and include test/build results when possible.

Useful local checks:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:compileDebugKotlin
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:lintDebug
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:testDebugUnitTest
```

Release process: [docs/RELEASE.md](docs/RELEASE.md)

F-Droid notes: [docs/FDROID.md](docs/FDROID.md)

Privacy policy: [PRIVACY.md](PRIVACY.md)

Security policy: [SECURITY.md](SECURITY.md)

## License

NekoPlayer is licensed under the [GNU General Public License v3.0](LICENSE) (`SPDX-License-Identifier: GPL-3.0-or-later`).

```
NekoPlayer
Copyright (C) 2026 Theo Vilardo

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```

Distributed APKs include third-party components under their own licenses. In particular, the optional FFmpeg decoder dependency `org.jellyfin.media3:media3-ffmpeg-decoder` is GPL-3.0; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Attribution

NekoPlayer is a fork of **PixelPlayerOSS**, the GPLv3 Android music player originally published by [@lostf1sh](https://github.com/lostf1sh) at
<https://github.com/PixelPlayerHQ/PixelPlayerOSS>, which itself derives from the non-FOSS PixelPlayer by [@theovilardo](https://github.com/theovilardo).
Upstream copyright, authorship, and licensing notices are retained: this fork only changes the application name, package id, and distribution channel.

<p align="center">
  Maintained by <a href="https://github.com/hcy917">hcy917</a>
</p>
<p align="center">
  Logo designed by <a href="https://github.com/NPSummers">Aureal</a>.
</p>
