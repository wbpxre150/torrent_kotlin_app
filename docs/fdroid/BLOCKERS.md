# F-Droid readiness

**Status: the recipe builds.** `fdroid build --test com.torrent.app` (fdroidserver 2.4.6,
2026-11-26) ends with `success: com.torrent.app` / `1 build succeeded` and produces a 16.7 MB
`arm64-v8a` APK signed by F-Droid's test key. No MR has been filed.

The recipe is [`metadata.yml`](metadata.yml) (target path in the MR:
`metadata/com.torrent.app.yml`); the srclib definitions it needs are in [`srclibs/`](srclibs/).

## What running the build actually found

None of this was visible from reading the docs — each one only showed up when the build ran.

| Symptom | Cause | Fix |
|---|---|---|
| Staged srclibs sat unused; tarballs appeared next to them | The five "existence-checked" fetches skipped only the *extract*; the `curl` was unconditional | `stageOrFetch()` — every tarball fetch returns early when a tree is staged, and fails loudly if `-PprebuiltNativeSrc` is set without one |
| Everything re-downloaded after staging | fdroid runs `gradle clean` *between* `prebuild` and the build, and clean deletes `app/build/` — where the staging went | `-PnativeRoot=app/native-root`, outside `build/` |
| `assemble-PprebuiltNativeSrcRelease` | `gradle:` entries are flavour names, not flags | properties moved to `gradleprops:` |
| mbedtls configure: "run `git submodule update --init`" | mbedtls keeps `framework/` in a submodule; the release tarball vendors it | `git -C $$mbedtls$$ submodule update --init --recursive` |
| same for transmission | `third-party/` is a submodule set | same, for `$$transmission$$` |
| curl: `./configure` cannot be started | curl's git tree ships `configure.ac`, and there is no `buildconf` script | `(cd $$curl$$ && autoreconf -fi)` + autoconf/automake/libtool |
| `No apks match .../app-release.apk` | without `keystore.properties` the build type is unsigned, so AGP names it `app-release-unsigned.apk` | `output:` points at the unsigned name; F-Droid signs it |

## Still a network dependency (accepted)

1. **sqlite amalgamation.** `fetchSqlite` downloads the official `sqlite-amalgamation-3490100.zip`.
   The amalgamation is generated upstream with tclsh and is not in sqlite's git tree, so a staged
   srclib cannot replace it. (Adding `tcl` and running `make sqlite3.c` in the checkout is possible
   but untested here — this host has no tclsh.)
2. **Go toolchain for cloudflared.** `go.mod` requires go1.27.4, so the buildserver downloads that
   toolchain and the module graph. Decided: accept it. `-PgoToolchain=<ver|local>` and
   `-PgoVendor` exist if that ever needs to be hermetic.

## Before filing

1. **Point the recipe at a tag that contains the build-script knobs.** `v1.0` predates
   `-PnativeRoot`/`-PprebuiltNativeSrc`; the recipe currently carries a raw commit SHA. Either cut
   the next release tag and use it, or keep the SHA.
2. **Add the srclibs to fdroiddata.** Some may already exist upstream (`mbedtls`, `curl`,
   `transmission`, `libuv`, `sqlite`); `llama.cpp` and `cloudflared` likely need adding from
   [`srclibs/`](srclibs/). Verify the tags resolve: `mbedtls-3.6.6`, `curl-8_20_0`, `4.1.4`,
   `v1.48.0`, `2026.11.0`, and commit `18ab62a8…`.
3. **Consider `scanignore`.** The staged sources land under `app/native-root/`, outside the repo,
   so the scanner should not see them — but if it complains about vendored third-party sources in
   the transmission checkout, add them there.
4. **The published APK is not stripped.** The v1.0 release asset is ~36 MB and its daemons carry
   `debug_info`; the same tree built clean by fdroid is 16.7 MB with stripped daemons (running
   `llvm-strip --strip-unneeded` by hand reproduces fdroid's sizes exactly, e.g. libftpserver
   168 880 → 42 272 bytes). Locally `:app:stripReleaseDebugSymbols` reports UP-TO-DATE and emits
   unstripped output with no warning, so AGP is not stripping this project's `lib*.so` — cause not
   established (they are PIE executables, not shared objects, which may be what it filters on).
   Cosmetic: the binary works either way, it is just twice the download.

## Reviewer notes (not blockers)

- **License mix.** `License: MIT` covers the app and the weights, but the APK also contains the
  GPLv2 transmission-daemon (as an executable), Apache-2.0 mbedTLS and cloudflared, the curl
  license and MIT llama.cpp. All corresponding source is published here.
- **Daemons are exec'd in place** from `nativeLibraryDir` rather than dlopen'd — `lib*.so` files
  are PIE executables, not shared libraries. Deliberate; see `AGENTS.md` invariant 3.
- **arm64-v8a only.** `abiFilters` is set, so the APK declares one ABI.
- **Signing.** `keystore.properties` is gitignored and optional, which is why F-Droid's build is
  unsigned until F-Droid signs it. Anyone moving from the GitHub release to F-Droid must uninstall
  first (different keys).
- **`FOREGROUND_SERVICE_SPECIAL_USE`** for the daemon supervisor; the dashboard binds `0.0.0.0:8090`
  behind a token.
- **`gradle-wrapper.jar` is gitignored**, so `./gradlew` does not work in a fresh clone. F-Droid is
  unaffected (it brings its own Gradle via `gradle-wrapper.properties`), but contributors trip on
  it. Committing the jar would be the conventional fix.

## Reproducing the test locally

```bash
uv tool install --python 3.12 --with 'setuptools<81' fdroidserver   # needs pkg_resources
mkdir ~/fdroid-test && cd ~/fdroid-test && fdroid init --no-prompt --android-home "$HOME/Android/Sdk"
mkdir -p metadata srclibs
cp docs/fdroid/metadata.yml metadata/com.torrent.app.yml
cp docs/fdroid/srclibs/*.yml srclibs/
git init -q && git add -A && git commit -qm recipe   # fdroid reads SOURCE_DATE_EPOCH from this repo
# config.yml: sdk_path, ndk_paths: {r27: .../ndk/android-ndk-r27}, build_tools: 35.0.1,
#             gradle: <path to a gradle 8.14.3 binary>  (gradlew-fdroid is not shipped in the wheel)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk fdroid build --test --no-tarball --skip-scan com.torrent.app
```

Two traps in that setup: `fdroid init` crashes with `ModuleNotFoundError: pkg_resources` on
setuptools >= 81, and `fdroid build` crashes with `TypeError: str expected, not NoneType` if the
recipe directory is not a git repo (it derives `SOURCE_DATE_EPOCH` from git).
