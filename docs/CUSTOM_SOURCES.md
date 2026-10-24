# Custom JS audio sources

NekoPlayer can search and play music through user-supplied **LX-style `userApi` JavaScript
sources** (`*.js`). The feature is ported from the downstream [XiangsuPlayerHQ](https://github.com/r3n011/XiangsuPlayerHQ)
fork; this fork keeps it JS-only and exposes it from the **Cloud streaming** sheet instead of
Settings.

## Where to find it

Home → tap the cloud/streaming button → **Cloud streaming** sheet → **Custom sources**
(`云端串流 → 自定义音源`).

Inside the screen:

| Action | Where |
| --- | --- |
| Import a `.js` source from storage | `+` in the top bar |
| Import a `.js` source from a URL | download icon in the top bar |
| Reload / re-register scripts | refresh icon |
| Engine info and registered sources | info icon |
| Export every imported script as a `.zip` | share icon |
| **Source market** (community scripts) | store icon |

The market (`Source market` screen) lists zip assets from the `guoyue2010/lxmusic-` GitHub
repository, downloads them through a mirror chain, and installs every `*.js` inside.

## How it works

| Layer | File | Notes |
| --- | --- | --- |
| JS runtime | `data/lx/LxJsEngine.kt` | QuickJS (`wang.harlon.quickjs:wrapper-android`) executes `userApi` scripts; the lx shim exposes `lx.request`, `lx.utils`, `lx.on`, `lx.send`, … |
| Script storage | `data/lx/LxFileStore.kt` | `filesDir/lx_user_js/*.js`, import by SAF `Uri` or URL, unique file names, zip export |
| HTTP bridge | `data/lx/LxHttpFetcher.kt` | Synchronous HTTP for script callbacks, result caching and rate-limit bookkeeping |
| Search / url / lyric / pic | `LxJsEngine.search/getPlayUrl/getLyric/getPic` | Script actions `musicSearch` → `search`, `musicUrl` → `url`, `lyric`, `pic` |
| Connectivity test | `data/lx/LxSourceTester.kt` | Runs a real search per installed source, then resolves a play URL |
| Market | `data/sourcemarket/*` | GitHub releases → zip → `*.js` entries, path-traversal safe, 4 MB per entry cap |

Playback: a resolved URL is turned into a transient `Song` (`id = "lx:<songId>"`, `path`/`contentUriString`
set to the URL) and queued through the normal player, which already accepts `http`/`https` media URIs.

## Deliberate differences from the downstream fork

The downstream feature is fused with that app's built-in cloud sources. This port is JS-only, so:

- **No built-in sources.** NetEase/QQ/Kugou/Kuwo/Migu implementations, the NetEase stream proxy,
  the `net.moriafly.ncm` dependency and the music-quality catalogue chain are **not** included.
  `wy`/`all` therefore means "every enabled JS source" (up to 8 are merged per search) rather than
  "NetEase".
- **Dead NetEase-only UI stays inert.** Artist and playlist search, song comments, and "save to
  local library" resolve to empty results or a user-visible notice, because they depended on the
  built-in cloud library (`MusicRepository.saveCloudSong`, which does not exist here).
- **Favourites** for custom-source songs are a no-op (they also required a cloud library row).
- **Quality** selection is in-memory and starts at `128k`; there is no persisted quality catalogue.
- **Lyrics** are available from the engine but are not attached to the transient queue item, so the
  player shows no LX lyrics yet.
- **Market** calls GitHub anonymously (60 requests/hour); the downstream token plumbing
  (`BuildConfig.GITHUB_TOKEN_OBF`) is not ported.

## Security notes

- Scripts run inside QuickJS with only the shimmed `lx.request` network access; there is no Android
  or filesystem bridge, but a script can still issue arbitrary HTTPS requests and read the JSON you
  hand it.
- Imported scripts are stored unencrypted in app-private storage and are included in
  `SharedArtworkContentProvider`-independent paths only; they are never executed on app start until
  you open the custom-source screen (`LxJsEngine.ready()` is lazy).
- R8 keep rules for `data.lx.**` and `com.whl.quickjs.**` live at the end of `app/proguard-rules.pro`;
  release builds rely on them so JS callbacks keep resolving.
