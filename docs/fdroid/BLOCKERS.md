# F-Droid readiness — what stands between this repo and an accepted MR

v1.0 is tagged and released on GitHub. None of this blocks a direct install from the release
page; these are the items that would make `fdroid build com.torrent.app` succeed on F-Droid's
buildserver and the MR acceptable to reviewers.

The recipe sketch is in [`metadata.yml`](metadata.yml) (target path in the MR:
`metadata/com.torrent.app.yml`).

## 1. The build is not hermetic — hard blocker

Gradle downloads seven source archives itself, inside `doLast` blocks, and F-Droid wants every
input to come from a declared `srclib` (or from the repo). What each task wants and where:

| Task | Downloads | Staged tree | sha256-pinned tarball |
|---|---|---|---|
| `buildMbedtls` | mbedtls 3.6.6 `.tar.bz2` (GitHub release) | `app/build/native/src/mbedtls-3.6.6` | no |
| `buildCurl` | curl 8.20.0 `.tar.gz` (curl.se) | `app/build/native/src/curl-8.20.0` | no |
| `buildTransmission` | transmission 4.1.4 `.tar.xz` (GitHub release) | `app/build/native/src/transmission-4.1.4` | no |
| `buildLibuv` | libuv v1.48.0 `.tar.gz` | `app/build/native/src/libuv-v1.48.0` | no |
| `fetchSqlite` | sqlite amalgamation 3.49.1 `.zip` | `app/build/native/src/sqlite-amalgamation` | no |
| `fetchLlamaCpp` | llama.cpp `18ab62a8` `.tar.gz` | `app/build/native/src/llama-18ab62a8` | **yes** (`34bd70e2…`) |
| `fetchCloudflaredSrc` | cloudflared 2026.11.0 `.tar.gz` | `app/build/native/src/cloudflared-2026.11.0` | **yes** (`99134523…`) |

The first five skip their download when the extracted tree already exists, so a `prebuild` that
copies a srclib checkout into place is enough for them.

**The last two are different:** both call `sha256Of(tarball)` *unconditionally*, after a
`if (!tarball.exists()) curl` block. Staging a git checkout of llama.cpp or cloudflared does not
stop the download — the task still curls the archive to hash it. A hermetic build needs a small
change in those two tasks (e.g. accept a pre-staged tree when `-PprebuiltNativeSrc` or an env
var is set, skipping the tarball verification). Roughly ten lines each; not done yet because it
touches the pinning path that protects the movie-kind and tunnel binaries.

Second-order problem: all staging lives under `app/build/`, which is gitignored and wiped by
`gradle clean`. Any `prebuild` staging must run after the last clean, and a recipe that runs
`gradle clean` will silently undo itself.

## 2. Go toolchain for cloudflared

`buildCloudflared` sets `GOTOOLCHAIN=go1.27.4`, so the host Go downloads that toolchain (and the
module graph) on first build. Either vendor with `go mod vendor` and pin the toolchain to what
the buildserver ships, or accept a network-dependent build — which conflicts with item 1.

## 3. The dht_crawler submodule

`app/src/main/cpp/dht_crawler` is a public submodule (`wbpxre150/dht_crawler`), so
`submodules: true` works, and the three `lib/` fixes it needs are in-tree patches applied by
`prepareDhtCrawlerSrc`. F-Droid discourages submodules though; the cleaner variant is to declare
the crawler as a `srclib` and let `prepareDhtCrawlerSrc` copy it — that task already copies the
tree into `app/build/native/src/dht_crawler` before patching, so it is close to free.

## 4. ~500 MB of classifier weights downloaded at runtime

The APK carries no models. `dht_crawler --fetch-models` pulls CRAM v15 and movie-kind v13 from a
network URL on first start, verifying each against manifests pinned in the crawler binary. Three
consequences for the listing:

- first run needs network, and roughly 500 MB of free space on top of the index;
- without them the app still runs but classifies nothing (`clf_ver` 0 — an all-or-nothing
  degraded mode, not a partial one);
- if the weights are not published under a free license, the app needs
  `AntiFeatures: NonFreeNet` (and reviewers may push back on "partly non-free"). **Decision
  needed before filing.** The metadata draft declares no anti-features.

## 5. License mix

`License: MIT` is the app's own license, but the shipped APK also contains the GPLv2
transmission-daemon (as an executable), Apache-2.0 mbedTLS and cloudflared, the curl license and
MIT llama.cpp. Full corresponding source is published in this repo, so there is no compliance
gap, but expect a reviewer question about the combined work and about the metadata's `License:`
field.

## 6. arm64-v8a only

There is no `abiFilters`. The four daemons exist only for `arm64-v8a`, yet the APK still declares
`armeabi-v7a`, `x86` and `x86_64` entries for `libandroidx.graphics.path.so`, so a 32-bit or
emulator install *succeeds* and then fails at daemon start. Worth fixing before filing:

```kotlin
defaultConfig { ndk { abiFilters += "arm64-v8a" } }
```

## 7. NDK and host tool pins

`build.gradle.kts` resolves the NDK as the highest-numbered directory under `$ANDROID_HOME/ndk`
— fine locally, but the recipe should pin it (`ndk: r27`). The native builds need `cmake` and
`ninja` (hence the `sudo:` block in the draft).

## 8. Signing — already correct

`keystore.properties` is gitignored and optional: without it the release build type stays
unsigned, which is exactly what F-Droid wants (it signs with its own key). Note for the release
notes / issue tracker: the GitHub APK is signed with the project key, so anyone moving from the
GitHub release to F-Droid has to uninstall first.

## 9. Not blockers, but reviewers will ask

- `useLegacyPackaging = true` and the daemons are **exec'd in place** from `nativeLibraryDir`
  rather than dlopen'd (`libtransmissiond.so` is a PIE binary, not a shared library). Deliberate;
  see `AGENTS.md` invariant 3.
- `FOREGROUND_SERVICE_SPECIAL_USE` for the daemon supervisor.
- The dashboard binds `0.0.0.0:8090` and is protected by a token in a cookie.

## Minimum work before filing a buildable MR

1. Escape hatch in `fetchLlamaCpp` and `fetchCloudflaredSrc` so a pre-staged tree is accepted
   without the tarball (item 1).
2. Pin or vendor the Go toolchain for cloudflared (item 2).
3. `abiFilters += "arm64-v8a"` (item 6).
4. Decide the weights' license and set `AntiFeatures` accordingly (item 4).
5. Run `fdroid build --test com.torrent.app` locally against the draft recipe before opening the
   MR — the `prebuild`/`srclibs` block in `metadata.yml` is untested.
