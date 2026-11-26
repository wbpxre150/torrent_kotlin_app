# F-Droid readiness — what stands between this repo and an accepted MR

v1.0 is tagged and released on GitHub. None of this blocks a direct install from the release
page; these are the items that decide whether `fdroid build com.torrent.app` succeeds on F-Droid's
buildserver and whether the MR is acceptable to reviewers.

The recipe sketch is in [`metadata.yml`](metadata.yml) (target path in the MR:
`metadata/com.torrent.app.yml`). It is still **untested** — see "What is left" at the bottom.

## 1. Hermetic build — fixed, with one exception

Gradle fetches seven source archives itself. Every one of them can now be pre-staged:

| Task | Downloads | Staged tree | Tarball sha256-pinned |
|---|---|---|---|
| `buildMbedtls` | mbedtls 3.6.6 `.tar.bz2` | `app/build/native/src/mbedtls-3.6.6` | no |
| `buildCurl` | curl 8.20.0 `.tar.gz` | `app/build/native/src/curl-8.20.0` | no |
| `buildTransmission` | transmission 4.1.4 `.tar.xz` | `app/build/native/src/transmission-4.1.4` | no |
| `buildLibuv` | libuv v1.48.0 `.tar.gz` | `app/build/native/src/libuv-v1.48.0` | no |
| `fetchSqlite` | sqlite amalgamation 3.49.1 `.zip` | `app/build/native/src/sqlite-amalgamation` | no |
| `fetchLlamaCpp` | llama.cpp `18ab62a8` `.tar.gz` | `app/build/native/src/llama-18ab62a8` | **yes** |
| `fetchCloudflaredSrc` | cloudflared 2026.11.0 `.tar.gz` | `app/build/native/src/cloudflared-2026.11.0` | **yes** |

The first five skip their download when the extracted tree already exists. The last two used to
call `sha256Of(tarball)` unconditionally, so staging a checkout did not stop the download — they
now honour `-PprebuiltNativeSrc`, which says "the caller staged a pinned tree, skip the tarball
entirely" and fails loudly if the tree is missing. Default behaviour without the property is
unchanged: download, verify, extract.

Staging still lands under `app/build/`, which is gitignored and wiped by `gradle clean`, so the
`prebuild` step must run after any clean.

## 2. Go toolchain for cloudflared — decided: the buildserver fetches it

cloudflared's `go.mod` requires **go1.27.4**, so `buildCloudflared` sets `GOTOOLCHAIN=go1.27.4`
and the host's Go downloads that toolchain (and the module graph) on first build.

**Decision: keep the default and let F-Droid's buildserver fetch it.** That is a build-time
network dependency, accepted deliberately — the alternative (vendor the modules and require Go
>= 1.27.4 on the buildserver) has more moving parts and cannot be tested here, since this host
ships Go 1.27.3, older than cloudflared's own requirement.

Both knobs still exist if that changes: `-PgoToolchain=<version|local>` overrides the toolchain
(`local` fails fast with a clear message rather than downloading), and `-PgoVendor` builds with
`-mod=vendor` and `GOPROXY=off` against a staged `vendor/` tree.

## 3. The dht_crawler submodule

`app/src/main/cpp/dht_crawler` is a public submodule (`wbpxre150/dht_crawler`), so
`submodules: true` works, and the three `lib/` fixes it needs are in-tree patches applied by
`prepareDhtCrawlerSrc`. F-Droid discourages submodules though; the cleaner variant is to declare
the crawler as a `srclib` and let `prepareDhtCrawlerSrc` copy it — that task already copies the
tree into `app/build/native/src/dht_crawler` before patching, so it is close to free.

## 4. Classifier weights — resolved: MIT, no anti-feature

The APK carries no models; `dht_crawler --fetch-models` pulls CRAM v15 and movie-kind v13 from
`https://unjumble.au/bep51/models` on first start, verifying each against manifests pinned in the
crawler binary. The weights are MIT licensed (LICENSE in the `classifier-model` repo), so the
listing declares **no** anti-features.

Consequences to keep in the listing text, not the metadata: first run needs network and ~500 MB of
free space, and without the weights the app runs but classifies nothing (`clf_ver` 0 — an
all-or-nothing degraded mode, not a partial one).

## 5. License mix

`License: MIT` covers the app and the weights, but the shipped APK also contains the GPLv2
transmission-daemon (as an executable), Apache-2.0 mbedTLS and cloudflared, the curl license and
MIT llama.cpp. Full corresponding source is published in this repo, so there is no compliance
gap, but expect a reviewer question about the combined work.

## 6. ABI — fixed

`abiFilters += "arm64-v8a"` is now set. Before that the APK advertised `armeabi-v7a`, `x86` and
`x86_64` (androidx's graphics-path helper ships those) while all four daemons existed only for
arm64, so a 32-bit or emulator install succeeded and then failed at daemon start. The APK now
carries `lib/arm64-v8a/` only, and Play/F-Droid will not offer it to other ABIs.

## 7. NDK and host tool pins

`build.gradle.kts` resolves the NDK as the highest-numbered directory under `$ANDROID_HOME/ndk`
— fine locally, but the recipe pins it (`ndk: r27`). The native builds need `cmake` and `ninja`
(hence the `sudo:` block in the draft).

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

## What is left before filing

1. Run `fdroid build --test com.torrent.app` locally against the draft recipe — the
   `prebuild`/`srclibs` block is untested, and `srclib` versions must be checked to exist as tags
   (e.g. `transmission@4.1.4`, `libuv@v1.48.0`, `sqlite@version-3.49.1`).
2. Confirm the transmission srclib checkout carries its vendored third-party sources, or add
   `--recurse-submodules` to it.
3. File the MR.

The v1.0 release asset was replaced in place with the ABI-filtered build (same tag, same
versionCode 6, new APK sha256), so the published APK matches this tree.
