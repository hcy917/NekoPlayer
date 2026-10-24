# Third-Party Notices

NekoPlayer is licensed under the GNU General Public License v3.0 (GPL-3.0) and includes third-party open-source components.

Bundled fonts:

| Asset | Source | License |
| --- | --- | --- |
| Google Sans Flex (`gflex_variable.ttf`) | `https://fonts.google.com/specimen/Google+Sans+Flex/license?preview.script=Latn` | SIL Open Font License 1.1 |
| Roboto Flex (`genre_variable.ttf`) | `https://github.com/googlefonts/roboto-flex` | SIL Open Font License 1.1 |

Bundled data assets:

| Asset | Source | License |
| --- | --- | --- |
| Artist genres (`nlp/artist-genres.txt`) | Artist-to-genre labels derived from Wikidata, `https://www.wikidata.org` | CC0 1.0 |
| Smart Mix concepts (`nlp/concepts.txt`) | Word-to-genre-family/mood concept table generated offline | GPL-3.0 |

Native runtime artifacts:

| Artifact | Native library | License/source evidence |
| --- | --- | --- |
| `org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1` | `libffmpegJNI.so` | GPL-3.0, `https://github.com/jellyfin/jellyfin-androidx-media` |
| `io.github.kyant0:taglib:1.0.6` | `libtaglib.so` | Apache-2.0, `https://github.com/Kyant0/taglib` |
| `androidx.graphics:graphics-shapes:1.1.0` | `libandroidx.graphics.path.so` | Apache-2.0, AOSP/AndroidX source |

JitPack artifacts:

| Artifact | Source | License |
| --- | --- | --- |
| `com.github.racra:smooth-corner-rect-android-compose:v1.0.0` | `https://github.com/racra/smooth-corner-rect-android-compose` | MIT |
| `com.github.philburk:jsyn` | `https://github.com/philburk/jsyn` | Apache-2.0 |

Main library license families:

| Family | Examples | License |
| --- | --- | --- |
| AndroidX and Jetpack Compose | Activity, AppCompat, Benchmark, Compose, Glance, Hilt extensions, Lifecycle, Media3, Navigation, Paging, Room, Security, WorkManager | Apache-2.0 |
| Google libraries | Material Components, Gson, Guava transitive pieces | Apache-2.0 |
| JetBrains/Kotlin libraries | Kotlin stdlib, coroutines, serialization, immutable collections | Apache-2.0 |
| Square libraries | OkHttp, Retrofit | Apache-2.0 |
| Ktor/Netty | Ktor server CIO/core, Netty transitive libraries | Apache-2.0 |
| Audio/metadata libraries | JAudioTagger, Vorbis Java | LGPL/MPL/BSD-family |
| UI utilities | Accompanist, Coil, Capturable, CodeView, Reorderable, Wavy Slider | Apache-2.0/MIT |
| Text/search utilities | Kuromoji, pinyin4j | Apache-2.0/BSD-style |
| Security/compatibility transitive constraints | Bouncy Castle, Commons Lang, HttpClient, JDOM2, jose4j | MIT/Apache-2.0/Bouncy Castle/JDOM-style |

See `THIRD_PARTY_NOTICES.md` in the source repository for the fuller dependency review notes.
