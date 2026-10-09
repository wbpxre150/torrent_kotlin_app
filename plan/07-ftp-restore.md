# Plan 07 — Restore embedded FTP server (non-blocking w.r.t. transmission)

Source of truth for the removed code: commit `b1c7ffe^` (FTP removed in `b1c7ffe`).
Design doc for the original feature: `plan/06-ftp-server.md`.

## 0. Hard constraints

- Do NOT `git revert b1c7ffe`. That commit also contains native bug fixes
  (`file-posix.cc` EIO on 0-byte read, `file-saf.cc` real mtime) that must stay.
  Restore only Kotlin/Gradle/manifest/test FTP parts.
- Do NOT touch native code (`app/src/main/cpp/**`) or `libtransmissiond.so`.
- Do not change behaviour of existing `SafStorageGateway` methods used by
  transmission IPC (`openFd`, `truncate`, `statSize`, `renameDoc`, `removeDoc`,
  `mkdirs`, `availableBytes`, `exists`, `stat`, `list`). Only ADD new methods.
- Build with JDK 21 (see CLAUDE.md):
  `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ANDROID_HOME=$HOME/Android/Sdk ./gradlew testDebugUnitTest assembleDebug`

## 1. Restore files from `b1c7ffe^` (verbatim first, then modify per §2)

Use `git checkout b1c7ffe^ -- <path>` for:

- `app/src/main/java/com/torrent/app/ftp/*.kt` (FtpCredentials, FtpFileSource,
  FtpServerController, FtpStatus, FtpUserManager, LiteralPathListingCommand,
  SafFtpFile, SafFtpFileSource, SafFtpFileSystemFactory, SafFtpFileSystemView,
  WifiGateMonitor, WifiGateReducer)
- `app/src/test/java/com/torrent/app/ftp/*.kt` (all tests)

Re-apply the FTP hunks of `b1c7ffe` in reverse for these non-FTP files
(`git diff b1c7ffe b1c7ffe^ -- <file> | git apply` works cleanly for most):

- `app/build.gradle.kts` (ftpserver-core 1.2.0, slf4j-jdk14 1.7.36)
- `app/src/main/AndroidManifest.xml` (FGS subtype string)
- `config/AppConfig.kt`, `config/ConfigStore.kt`, `config/ConfigValidator.kt`,
  `config/WizardSubmit.kt`, `MainActivity.kt`
- `config/*Test.kt` FTP test hunks
- `ControlServer.kt`, `DaemonService.kt`: hunks conflict near the constructor /
  ControlServer call-site because `7b8e527` removed the SAF validation harness.
  Re-apply by hand: re-add the FTP endpoints/sections/methods and the `ftpStatus`/
  `applyFtp` wiring, but do NOT re-add anything `7b8e527` removed
  (SafValidator, runSafValidation, /saf/validate, probe/saftest).
- Skip the `UsbFdBridge.kt` comment-only hunk.

## 2. Isolation changes so FTP never blocks transmission

Background: transmission (separate native process) reaches the USB through
`SafIpcServer` (Dispatchers.IO, one socket per native thread) -> `SafStorageGateway`.
Its single `tr-diskio` worker performs `OP_OPEN`/stat round-trips synchronously,
so anything that stalls the gateway (per-path `ReentrantLock`s held across
`ContentResolver.query`), the ExternalStorageProvider binder pool, or the FUSE
daemon stalls ALL torrent I/O. The old FTP called the same gateway methods
(lock-taking `resolveUri`, unlocked writes into the shared `cache`) from
Apache FtpServer/MINA threads at default priority with tiny 4 KiB transfer reads.

### 2.1 Lock-free, cache-isolated read path for FTP (SafStorageGateway)

Add a separate FTP-only API to `SafStorageGateway` (new methods only), e.g.
`readOnlyStat(relPath)`, `readOnlyListDetailed(relPath)`, `readOnlyOpenRead(relPath)`,
backed by a private `resolveUriReadOnly(relPath)` that:

- reads the shared `cache` (read-only: NEVER puts/removes entries in it),
- on miss walks the tree using a SEPARATE private `ConcurrentHashMap<String, Uri>`
  (`roCache`), with NO `pathLocks` acquisition at all (duplicate concurrent queries
  are acceptable for FTP),
- `readOnlyListDetailed` populates only `roCache`.
- Stale entries: if `openFileDescriptor`/query on a `roCache` URI fails with
  FileNotFound / empty cursor, drop that key (and descendants) from `roCache`
  and retry once with a fresh walk. Also cap `roCache` size (e.g. clear when
  > 4096 entries) to bound memory.

Rationale: FTP can then never hold a lock transmission waits on, and can never
inject a stale URI into the cache transmission trusts (renames/removes done by
transmission invalidate only the shared cache).

`SafFtpFileSource` uses only these read-only methods. `delete` keeps using
`gateway.removeDoc` (existing, transmission-safe), but ALSO must evict from
`roCache` (add `fun evictReadOnly(relPath)` or have `removeDoc` clear `roCache`
prefix too — clearing roCache from removeDoc/renameDoc is allowed since it is
private FTP state and cheap; prefer this so transmission renames also invalidate
FTP's view).

### 2.2 Bound FTP's pressure on binder / FUSE

- In `SafFtpFileSource` (or a small `FtpSafThrottle` object), guard every gateway
  metadata/open call with a fair `java.util.concurrent.Semaphore(2)` so FTP uses at
  most 2 concurrent ContentResolver calls, leaving the provider's binder threads
  for transmission. Acquire with timeout (e.g. 30 s) -> IOException/null on timeout.
  Streaming reads on an already-open fd are NOT under the semaphore.
- Configure Apache FtpServer limits explicitly: `ConnectionConfigFactory` with
  `maxLogins = 10`, `maxThreads` small (e.g. 4) and keep passive ports
  `30000-30009` (≤ 10 data connections). Verify the actual API in the
  ftpserver-core 1.2.0 jar before using it.

### 2.3 Lower CPU priority for FTP threads

Every entry point executed on an FtpServer/MINA thread (SafFtpFileSource
methods, the streamProvider, and the returned InputStream's first read) calls a
helper `FtpThreads.lowerPriority()` that does
`android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)` once per
thread (ThreadLocal flag; catch and ignore exceptions so JVM unit tests work —
wrap behind an injectable lambda / check for Robolectric-less environment).
These threads are owned by FTP only, so this never affects transmission/IPC.

### 2.4 Efficient streaming (fewer FUSE round-trips)

- `streamProvider`: open the SAF pfd and wrap it in `ParcelFileDescriptor.
  AutoCloseInputStream(pfd)` — every close (explicit, finalize) routes through the
  idempotent `pfd.close()`, so the fd is closed exactly once and no raw fd int is
  handled. Device finding (OPPO CPH2305 / ColorOS Android 15): the pre-removal
  `detachFd()` + `FileInputStream(fd)` ownership pattern made the fd EBADF right
  after `detachFd()` (pre-detach reads succeeded), so every RETR failed with 551;
  do NOT reintroduce it here. Seek via `channel.position(offset)` for REST, and wrap
  in `BufferedInputStream(fis, 512 KiB)` so FtpServer's small per-read buffer becomes
  large sequential FUSE reads (keep/extend `FtpStreamProviderFdCloseTest`).
- Optional download rate cap: add `FtpSettings.maxDownloadKiBps: Int = 0`
  (0 = unlimited) persisted in ConfigStore, validated (>= 0) and exposed in the
  /settings/ftp form; apply via Apache's `TransferRatePermission(maxDownloadRate
  bytes/s, 0)` on the user in `FtpUserManager`. This lets the user protect USB
  bandwidth for transmission.

### 2.5 Remove N+1 stat in directory listings

`SafFtpFile.listFiles()` builds children with a lazy `source.stat()` each, which
re-introduces one SAF query per child on LIST. Add an optional prefetched
`entry: FtpFileSource.Entry?` constructor parameter; children created from
`source.list()` pass their `Entry` so no per-child stat happens. Add a unit test
asserting `stat` is not called per child during listFiles + metadata access.

### 2.6 Keep FTP start/stop off the main thread

`DaemonService.observeWifiForFtp()` previously collected on `MainScope` and called
`FtpServerController.start/stop/rebind` (socket bind, MINA dispose) on the main
thread. Run those calls on a dedicated single-thread executor / `Dispatchers.IO`
(`withContext`) so a slow stop never stalls the service main thread. Ensure
`onDestroy` stops FTP before `gateway.shutdown()` and before/independent of the
transmission supervisor shutdown, and never holds any lock that IPC uses.

## 3. Tests

- All restored ftp tests pass (adapt to new read-only gateway API where
  `SafFtpFileSource` is tested with fakes).
- New unit tests:
  - read-only resolution never writes the shared cache / never takes path locks
    (test via a fake or by checking `cache` contents through an internal
    test hook; keep the hook `internal`).
  - semaphore bound: with 2 permits, a 3rd concurrent FTP metadata call waits.
  - listFiles uses prefetched entries (no N+1).
  - BufferedInputStream wrapper still closes the fd exactly once.
  - ConfigStore/Validator/WizardSubmit round-trip for `maxDownloadKiBps`.
- `./gradlew testDebugUnitTest assembleDebug` green with JDK 21.

## 4. Out of scope

Native transmission changes, raising transmission disk-io priority, write support
for FTP.
