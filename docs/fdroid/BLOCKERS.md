# F-Droid readiness

**Status: the recipe builds from a tag, scan included, and nothing is outstanding in this repo.**
`fdroid build --test --refresh com.torrent.app` (fdroidserver 2.4.6, 2026-11-26) against
**`v1.0.2`** — no `--skip-scan`, no `--no-tarball` — ends with `success: com.torrent.app` /
`1 build succeeded`, producing a 16.7 MB `arm64-v8a` APK (`versionCode 8`, `versionName 1.0.2`,
16 733 667 bytes) signed by F-Droid's test key. `fdroid scanner com.torrent.app:8` reports
**0 errors / 0 warnings**, `fdroid lint com.torrent.app` is silent, and the source tarball is
25.5 MB. No MR has been filed yet; what it has to contain is in
[What the MR contains](#what-the-mr-contains).

`v1.0` is deliberately not listed as a build: it predates the build-script knobs, so it cannot be
built by this recipe.

The recipe is [`metadata.yml`](metadata.yml) (target path in the MR:
`metadata/com.torrent.app.yml`); the srclib definitions it needs are in [`srclibs/`](srclibs/).

## What running the build actually found

None of this was visible from reading the docs — each one only showed up when the build ran.

| Symptom | Cause | Fix |
|---|---|---|
| Staged srclibs sat unused; tarballs appeared next to them | The five "existence-checked" fetches skipped only the *extract*; the `curl` was unconditional | `stageOrFetch()` — every tarball fetch returns early when a tree is staged, and fails loudly if `-PprebuiltNativeSrc` is set without one |
| Everything re-downloaded after staging | fdroid runs `gradle clean` *between* `prebuild` and the build, and clean deletes `app/build/` — where the staging went | `-PnativeRoot`, pointed outside `build/` and (later) outside the checkout entirely |
| `assemble-PprebuiltNativeSrcRelease` | `gradle:` entries are flavour names, not flags | properties moved to `gradleprops:` |
| mbedtls configure: "run `git submodule update --init`" | mbedtls keeps `framework/` in a submodule; the release tarball vendors it | `git -C $$mbedtls$$ submodule update --init --recursive` |
| same for transmission | `third-party/` is a submodule set | same, for `$$transmission$$` |
| curl: `./configure` cannot be started | curl's git tree ships `configure.ac`, and there is no `buildconf` script | `(cd $$curl$$ && autoreconf -fi)` + autoconf/automake/libtool |
| `No apks match .../app-release.apk` | without `keystore.properties` the build type is unsigned, so AGP names it `app-release-unsigned.apk` | `output:` points at the unsigned name; F-Droid signs it |
| `Can't build due to 1312 errors while scanning` | the scan runs **after** `prebuild` (`common.prepare_source` → `gradle clean` → `scanner.scan_source`), so with the staging inside the checkout it saw the staged trees. 1299 of the 1312 were under `app/native-root/src` (transmission's libpsl/libdeflate fuzz corpora, mbedtls `framework/data_files` + `programs/fuzz`, libuv's `Index.zip`, transmission's `macosx/Sparkle.framework`); the other 13 are in the vendored `dht_crawler` submodule (libbloom collision tables, civetweb/duktape test archives, cJSON/uthash `package.json`, the CRAM parser fixtures) | staged the trees outside the checkout (which also shrank the source tarball from 99.9 MB to 25.5 MB), and six narrow `scanignore` entries for the crawler submodule — see below. Nothing in that set is compiled or shipped: the APK holds four executables and dex/resources only |
| `Categories 'Internet' is not valid` (and every category, if the repo has no `config/`) | fdroiddata moved to a granular taxonomy in `config/categories.yml`; `fdroid lint` loads it from the *repository* it is run in, so a bare test repo with no `config/` fails every category check | `Categories: Download` + `File Transfer` (what LibreTorrent and BiglyBT use), and the local test repo needs `config/categories.yml` |

### The `scanignore` block, and why those six entries

The staged trees need no entry at all: `prebuild` now copies them to `<fdroid build root>/native-root`,
next to the checkout rather than inside it, so the scanner never walks them. That is the same
relationship fdroid has with `build/srclib/`, which it also never scans. Putting them inside the
checkout and ignoring `app/native-root` also worked, but it cost a 99.9 MB source tarball, because
`tarfile.add(build_dir)` tars whatever is in the checkout.

The six remaining entries are the vendored `dht_crawler` submodule — the app's own source, so they
name the third-party data directories and the parser fixtures exactly instead of ignoring the tree.
Each one is load-bearing: the scanner counts an unused `scanignore` path as an error (and a path that
matches nothing at all as an exception), so the block cannot silently rot.

## Accepted costs

1. **sqlite amalgamation is still fetched.** `fetchSqlite` downloads the official
   `sqlite-amalgamation-3490100.zip`. The amalgamation is generated upstream with tclsh and is not
   in sqlite's git tree, so a staged srclib cannot replace it. (Adding `tcl` and running
   `make sqlite3.c` in the checkout is possible but untested here — this host has no tclsh.)
2. **Go toolchain for cloudflared.** `go.mod` requires go1.27.4, so the buildserver downloads that
   toolchain and the module graph. Decided: accept it. `-PgoToolchain=<ver|local>` and
   `-PgoVendor` exist if that ever needs to be hermetic.
3. **The APK is not byte-reproducible across build roots.** Repeated builds from the same staging
   path produced byte-identical APKs, but moving the staging from `app/native-root` to
   `<build root>/native-root` changed the v1.0.1 APK from 16 733 915 to 16 733 671 bytes (v1.0.2,
   same staging, measures 16 733 667 — the 4 bytes are the versionCode/versionName resources): the
   daemons embed their absolute source paths (`__FILE__` — 192 such strings in libdhtcrawler, 56 in
   libtransmissiond), so a shorter path means a smaller binary. No `-ffile-prefix-map` is passed.
   F-Droid does not require reproducibility for inclusion; if it is ever wanted (binary
   transparency), that is a build-script change and therefore a new tag, not a recipe change.
4. **The source tarball is 25.5 MB** (`com.torrent.app_8_src.tar.gz`, 25 488 950 bytes measured;
   it was 99.9 MB while the staged trees lived in the checkout). Most of it is the dht_crawler
   submodule's history and test fixtures. Accepted.

## What the MR contains

1. `metadata/com.torrent.app.yml` — [`metadata.yml`](metadata.yml) verbatim.
2. Four new srclibs — `transmission`, `libuv`, `llama.cpp`, `cloudflared`. **`mbedtls` and `curl`
   already exist upstream**; the copies in [`srclibs/`](srclibs/) are there so the local test
   environment is self-contained and are marked "do not add". Each file records the commit its
   pinned ref resolves to, verified against the upstream repos on 2026-11-26:

   | srclib ref | resolves to |
   |---|---|
   | `mbedtls@mbedtls-3.6.6` | `5b64a9fdb979c8971561ec78221b528e3cc4e00a` |
   | `curl@curl-8_20_0` | `6e3f8dc1f173b47de9a68516ce4b95bf25598c2f` |
   | `transmission@4.1.4` | `a6d907d9ea2313143af714d7830feb6c1b60b84c` |
   | `libuv@v1.48.0` | `2afed16f6d377e22d1b1bbc6d209ee63a35eb3e0` |
   | `llama.cpp@18ab62a8…` | the commit itself (2026-11-06) |
   | `cloudflared@2026.11.0` | `71319b1dd12a2dae1a34f344aa747170145a9b1f` |

   `llama.cpp` pins a commit rather than a tag: llama.cpp tags rarely and the crawler's movie-kind
   head is built against that exact tree.

Resolved along the way (kept here so nobody re-litigates them):

- ~~Point the recipe at a tag that contains the build-script knobs.~~ **Done.** `v1.0.1`
  (versionCode 7) is the first tag carrying `-PnativeRoot`/`-PprebuiltNativeSrc`, so
  `UpdateCheckMode: Tags` / `AutoUpdateMode: Version` track it normally. The MR ships **`v1.0.2`**
  (versionCode 8) instead, the first tag that also carries the tracked gradle wrapper and the
  finished recipe; 1.0.2 has no functional change over 1.0.1.
- ~~Consider `scanignore`.~~ **Done, and it was needed.** See the table above.
- ~~The published APK is not stripped.~~ **Fixed.** The daemons were shipping ~90 MB of
  `debug_info` each because AGP's `stripReleaseDebugSymbols` reports UP-TO-DATE and emits
  unstripped output for these files with no warning (they are PIE executables, not shared
  objects). Each producing task now runs `llvm-strip --strip-unneeded` before the copy into
  `jniLibs`, which is where fdroid's own strip step was getting its numbers from
  (libftpserver 168 880 → 42 272 bytes, identical to the fdroid build). The release APK went
  from 36.3 MB to 16.7 MB.
- ~~`gradle-wrapper.jar` is gitignored.~~ **Fixed.** It is now tracked — byte-identical
  (`sha256 7d3a4ac4…`) to the jar in `gradle/gradle` `v8.14.3`, matching
  `distributionUrl=gradle-8.14.3-bin.zip` — so `./gradlew` works in a fresh clone. F-Droid was
  never affected; it brings its own Gradle.
  Note that fdroid's scanner *deletes* the tracked jar from its own checkout before building
  (`INFO: Removing gradle-wrapper.jar at gradle/wrapper/gradle-wrapper.jar`) — it always supplies
  its own Gradle, so tracking it changes nothing for F-Droid.

## Reviewer notes (not blockers)

- **License mix.** `License: MIT` covers the app and the weights, but the APK also contains the
  GPLv2 transmission-daemon (as an executable), Apache-2.0 mbedTLS and cloudflared, the curl
  license and MIT llama.cpp. All corresponding source is published here.
- **`AntiFeatures: TetheredNet`.** The classifier weights (~500 MB) and the BEP51 cache bootstrap
  both come from `unjumble.au`, defaults compiled into the crawler (`model_fetch.h`, `config.c`),
  with no mirror list. Both are skippable — the daemons run and the classifiers report
  `clf_ver 0` — and core torrenting, FTP and the dashboard never touch that host. Declared so a
  reviewer does not have to find it in the binary.
- **Daemons are exec'd in place** from `nativeLibraryDir` rather than dlopen'd — `lib*.so` files
  are PIE executables, not shared libraries. Deliberate; see `AGENTS.md` invariant 3.
- **arm64-v8a only.** `abiFilters` is set, so the APK declares one ABI.
- **Signing.** `keystore.properties` is gitignored and optional, which is why F-Droid's build is
  unsigned until F-Droid signs it. Anyone moving from the GitHub release to F-Droid must uninstall
  first (different keys).
- **`FOREGROUND_SERVICE_SPECIAL_USE`** for the daemon supervisor; the dashboard binds `0.0.0.0:8090`
  behind a token.

## Reproducing the test locally

```bash
uv tool install --python 3.12 --with 'setuptools<81' fdroidserver   # needs pkg_resources
mkdir ~/fdroid-test && cd ~/fdroid-test && fdroid init --no-prompt --android-home "$HOME/Android/Sdk"
mkdir -p metadata srclibs config
cp docs/fdroid/metadata.yml metadata/com.torrent.app.yml
cp docs/fdroid/srclibs/*.yml srclibs/
# fdroid lint reads the taxonomy from the repo it runs in; without this every category is invalid.
# Strip the icon: lines (they point at PNGs only fdroiddata has) or copy the PNGs too.
curl -s https://gitlab.com/fdroid/fdroiddata/-/raw/master/config/categories.yml   | grep -v '^\s*icon:' > config/categories.yml
curl -s https://gitlab.com/fdroid/fdroiddata/-/raw/master/config/antiFeatures.yml | grep -v '^\s*icon:' > config/antiFeatures.yml
git init -q && git add -A && git commit -qm recipe   # fdroid reads SOURCE_DATE_EPOCH from this repo
# config.yml: sdk_path, ndk_paths: {r27: .../ndk/android-ndk-r27}, build_tools: 35.0.1,
#             gradle: <path to a gradle 8.14.3 binary>  (gradlew-fdroid is not shipped in the wheel)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk fdroid build --test --refresh com.torrent.app
```

Cheap iterations, both of which catch the two failure modes that used to be invisible:

```bash
fdroid lint com.torrent.app      # categories, AntiFeatures, field validity
fdroid scanner --json com.torrent.app:7 | python3 -c 'import json,sys; d=json.load(sys.stdin)["com.torrent.app"]["7"]; print({k: len(v) for k, v in d.items()})'
# expect {'infos': N, 'warnings': 0, 'errors': 0} — "Unused scanignore path" lands in errors
```

Two traps in that setup: `fdroid init` crashes with `ModuleNotFoundError: pkg_resources` on
setuptools >= 81, and `fdroid build` crashes with `TypeError: str expected, not NoneType` if the
recipe directory is not a git repo (it derives `SOURCE_DATE_EPOCH` from git).
