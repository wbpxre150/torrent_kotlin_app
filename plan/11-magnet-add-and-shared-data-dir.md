# Stage 11 - Magnet "add to transmission" + one shared data directory

Two features on top of stages 01-10:

1. The crawler UI magnet buttons (search results + torrent page) add the torrent
   to transmission-daemon over RPC instead of copying a magnet link to the
   clipboard. Download goes to the root of the configured data directory with
   normal priority. Before adding, the proxy free-space-checks the USB drive
   (the crawler DB already has the torrent size), and rejects the add when it
   will not fit, telling the user their total free space.
2. One shared data directory setting drives BOTH the transmission daemon
   download dir and the FTP server exposed root. `torrent_data` on this setup.
   No separate per-service values.

## Current state (verified)

### App repo (`app/src/main/java/com/torrent/app/`)

- Two dir settings to merge: `config/AppConfig.kt:11` TransmissionSettings.downloadSubpath,
  `:19` FtpSettings.exposedRoot. Both are subpaths under the USB root; blank = root.
- Persisted keys: `config/ConfigStore.kt:214` KEY_TX_SUBPATH = "tx_download_subpath",
  `:219` KEY_FTP_ROOT = "ftp_exposed_root". Load around `:23-29`, save around `:69-76`.
- Validation: `config/ConfigValidator.kt:16-17` (transmission), `:27-28` (ftp),
  shared helper validSubpath `:69-74`.
- Restart coupling: `config/TransmissionApplyMode.kt:5-9` needsRestart compares
  TransmissionSettings and includes downloadSubpath.
- Wizard: `config/WizardSubmit.kt:8` Form.downloadSubpath, `:13` Form.ftpExposedRoot,
  `:26-36` submit; MainActivity fields dlSubpath ~:326, ftpRoot :332, form build :375-379.
- settings.json: `TransmissionConfig.kt:29-39` ensureSettingsJson builds download-dir
  "/saf/usb" + optional subpath; `:87` buildSettingsJson writes download-dir;
  sentinel USB_DOWNLOAD_DIR = "/saf/usb" at `:43`.
- FTP root use: `DaemonService.kt:347-353` and `:412-420` construct FtpServerController
  with rootRelPath = s.exposedRoot; `ftp/FtpServerController.kt:117` passes it to
  SafFtpFileSystemFactory; `ftp/SafFtpFileSystemView.kt:31-40` prefixes rel paths.
- RPC client: `transmission/TransmissionRpcClient.kt` has sessionGet / sessionSet /
  torrentGetSummary only. call() POSTs to `http://127.0.0.1:9091/transmission/rpc`
  with Basic auth + X-Transmission-Session-Id 409 retry at `:61-89`.
- Proxy: `CrawlerProxyServer.kt:49-76` one tail card route("{...}") forwarding everything
  to `127.0.0.1:<crawlerHttpPort>`. Auth reuses ControlServer.resolveAuthToken
  (cookie control_token, ?token=, Basic). Constructed in `DaemonService.kt:488-492`.
- Free space: `storage/SafStorageGateway.kt:178` availableBytes() returns a cached
  value, Long.MAX_VALUE when unknown. `DaemonService.kt:730-734` usbFreeSpace(): Long?
  returns null when the gateway is absent or free space unknown.
- Crawler DB: `DaemonService.kt:~499-503` path filesDir/crawler/data/torrents.db.
  WAL mode (`cpp/dht_crawler/src/database.c:93 PRAGMA journal_mode=WAL`) so concurrent
  readers are safe while the crawler writes. Existing Kotlin reader pattern:
  `migration/TermuxMigration.kt:~705-712` countTorrents() uses
  SQLiteDatabase.openDatabase(path, null, OPEN_READONLY).

### Crawler submodule (`app/src/main/cpp/dht_crawler/`)

- Search-results button `src/http_api.c:~2427`:
  `<button class='copy-btn' onclick='copyMagnet(event, "%s", "%s")' title='Copy magnet link'>🧲</button>`
  with format args hex, hex, encoded_name.
- Search JS `src/http_api.c:~2562-2577` copyMagnet, `:2579-2610` fallbackCopy,
  `:2612-2627` showCopySuccess.
- Torrent page button `src/http_torrent_page.c:~310`:
  `<button class='btn alt' onclick='copyMagnet()'>🧲 Copy magnet</button>`;
  page JS `:365-370` uses INFO_HASH and TORRENT_NAME_ENC globals (`:325-326`).
- DB: torrents table has info_hash BLOB(20) UNIQUE, name TEXT, size_bytes INTEGER
  (`src/database.c:~12-19`).

## Design decisions

### D1. Shared data directory (one setting)

- New top-level field AppConfig.dataSubpath: String, default "" = USB root.
  TransmissionSettings.downloadSubpath and FtpSettings.exposedRoot are removed.
- Persisted key "data_subpath". Migration in ConfigStore.load(): read
  "data_subpath"; when absent, fall back to legacy "tx_download_subpath", then
  "ftp_exposed_root", then default "". save() writes only "data_subpath".
- Transmission: TransmissionConfig.ensureSettingsJson() uses cfg.dataSubpath so
  download-dir becomes "/saf/usb" or "/saf/usb/<dataSubpath>", i.e. the ROOT of the
  data directory.
- FTP: both FtpServerController constructions in DaemonService pass
  rootRelPath = cfg.dataSubpath.
- TransmissionApplyMode.needsRestart(before: AppConfig, after: AppConfig) — signature
  change. Compare transmission fields plus dataSubpath. A data-dir change rewrites
  settings.json, restarts the transmission daemon (existing apply flow), and also
  rebuilds/restarts the FTP controller (mirror what the current FTP apply does after
  a config save).
- UI: ONE field "Data directory (under USB root; blank = root)" on the transmission
  panel (relabel the existing input). The FTP panel and the wizard FTP step lose their
  own dir fields; the wizard keeps one field. Panels already save the whole AppConfig.
- ConfigValidator: validSubpath(cfg.dataSubpath) once, error text
  "data directory must be relative, no '..', alphanumeric/./-/_/space only".
  The two old per-service checks are removed.
- Magnet adds pass explicit normal priority (transmission TR_PRI_NORMAL = 0),
  which is also the daemon default.

### D2. Add-to-transmission endpoint on the proxy

- New route on CrawlerProxyServer: route("/api/transmission/add") registered BEFORE
  the tail card. Ktor matches in registration order and the tail card matches
  everything, so the specific route must come first.
- Auth: same ControlServer.resolveAuthToken as the tail card. On failure respond
  401 with JSON {"ok":false,"error":"unauthorized"} (the caller is fetch, not a human).
- Request: POST with query param hash = 40 lowercase hex chars. No body needed.
  GET is also accepted; the handler requires the hash param either way.
- New class CrawlerAddHandler(store: ConfigStore, gateway: () -> SafStorageGateway?,
  crawlerDbFile: () -> File?) in a new file CrawlerAddHandler.kt — plain Kotlin, no Ktor
  imports, unit-testable. add(hashHex: String): CrawlerAddOutcome:
  1. Validate hash: exactly 40 chars of lowercase hex, else error("bad_hash").
  2. crawlerDbFile() null or missing file: error("db_unavailable").
  3. Open the crawler DB read-only, SELECT name, size_bytes FROM torrents WHERE
     info_hash = ?, binding the 20-byte blob decoded from the 40 hex chars. Row missing:
     error("not_found"). SQLite failure: error("db_unavailable"). Close in a use/finally.
  4. Free space: gateway() null: error("no_storage"). availableBytes() == Long.MAX_VALUE
     means unknown: skip the size check (log it) and continue — do not brick the button
     if the provider stops reporting free space.
  5. If sizeBytes > freeBytes: insufficient(sizeBytes, freeBytes) — the response tells
     the user the torrent size AND their total free space.
  6. Build the magnet: "magnet:?xt=urn:btih:<hex>&dn=<URLEncoder.encode(name, UTF-8)>".
  7. TransmissionRpcClient with the store's transmission username/password — new method
     torrentAdd(filename, downloadDir): TorrentAddResult. arguments: filename = magnet,
     "download-dir" = downloadDir, "bandwidthPriority" = 0.
     result "success": added, duplicate = false. result "duplicate torrent":
     added with duplicate = true. null or anything else: error("rpc_failed").
  8. downloadDir = TransmissionConfig.USB_DOWNLOAD_DIR plus optional "/"+dataSubpath,
     i.e. the root of the data directory.
- Response JSON, always Content-Type application/json. Success:
  {"ok":true,"added":true|false,"duplicate":bool,"name":str,"checked":bool}.
  Errors: {"ok":false,"error":"bad_hash|not_found|db_unavailable|no_storage|rpc_failed|unauthorized"}
  and {"ok":false,"error":"insufficient_space","sizeBytes":N,"freeBytes":M}.
- No CORS headers needed: the fetch is same-origin :8080 to :8080, so cookie auth flows.

### D3. Crawler UI buttons (submodule)

- src/http_api.c search-results button becomes
  <button class='copy-btn' onclick='addMagnet(event, "<hex>")' title='Add to Transmission'>➕</button>
- src/http_torrent_page.c button becomes
  <button class='btn alt' onclick='addMagnet()'>➕ Add to Transmission</button>
- New inline JS in both files, kept small, with a fmtGiB(bytes) helper:

  function fmtGiB(b) { return (b / 1073741824).toFixed(2) + ' GiB'; }
  function addMessage(j) {
    if (!j) return 'Add failed: the proxy answered with an error. Log in on the control server (:8090) and retry.';
    if (j.ok) return j.duplicate ? 'Already in Transmission — nothing added.'
      : 'Added "' + (j.name || '') + '" to Transmission (normal priority, data directory root).';
    if (j.error === 'insufficient_space')
      return 'Not added — not enough space on the drive.\nTorrent: ' + fmtGiB(j.sizeBytes) + '. Total free space: ' + fmtGiB(j.freeBytes) + '.';
    if (j.error === 'not_found') return 'Torrent not found in the crawler DB.';
    if (j.error === 'rpc_failed') return 'Transmission did not accept the torrent (daemon down or RPC error).';
    if (j.error === 'no_storage') return 'USB storage is not mounted.';
    if (j.error === 'db_unavailable') return 'The crawler database could not be read.';
    if (j.error === 'bad_hash') return 'Bad infohash.';
    return 'Add failed (' + (j.error || 'unknown') + '). Log in on the control server (:8090) and retry.';
  }

  Search-results page (event needed because the button sits inside a clickable row):

  function addMagnet(event, hash) {
    event.stopPropagation();
    fetch('/api/transmission/add?hash=' + hash, { method: 'POST' })
      .then(function(r) { return r.json().catch(function() { return null; }); })
      .then(function(j) { alert(addMessage(j)); })
      .catch(function() { alert('Add failed: the app proxy did not answer.\nAdd to Transmission is only available through the authenticated proxy (:8080).'); });
  }

  Torrent page (no row event; reads the existing INFO_HASH global):

  function addMagnet() {
    fetch('/api/transmission/add?hash=' + INFO_HASH, { method: 'POST' })
      .then(function(r) { return r.json().catch(function() { return null; }); })
      .then(function(j) { alert(addMessage(j)); })
      .catch(function() { alert('Add failed: the app proxy did not answer.\nAdd to Transmission is only available through the authenticated proxy (:8080).'); });
  }

- Both pages need fmtGiB and addMessage once; keep them inline with the rest of the JS.
- copyMagnet / fallbackCopy / showCopySuccess: replace. Grep first for other callers of
  these helpers; if a helper has another caller (e.g. a copy button for something else),
  keep that helper and only remove the magnet-specific copyMagnet.
- No server-side C route changes are needed: Kotlin reads the crawler DB directly.

## Task 1 - Kotlin app repo (one implementer)

Files under `app/src/main/java/com/torrent/app/` unless noted.

1. `config/AppConfig.kt` — add dataSubpath: String to AppConfig (default "");
   remove TransmissionSettings.downloadSubpath and FtpSettings.exposedRoot;
   ConfigDefaults.TRANSMISSION and .FTP drop the removed params.
2. `config/ConfigStore.kt` — new private const KEY_DATA_SUBPATH = "data_subpath".
   load(): dataSubpath = prefs KEY_DATA_SUBPATH ?: prefs legacy tx key ?: prefs legacy
   ftp key ?: "". save(): write only data_subpath; delete the old per-dir reads/writes.
   Keep the legacy KEY_TX_SUBPATH / KEY_FTP_ROot consts if still referenced by the
   fallback reads.
3. `config/ConfigValidator.kt` — remove the two old per-dir checks; add one
   validSubpath(cfg.dataSubpath) check with error text per D1.
4. `config/TransmissionApplyMode.kt` — needsRestart(before: AppConfig, after: AppConfig)
   comparing transmission fields + dataSubpath; update its callers and its test.
5. `config/WizardSubmit.kt` — Form drops downloadSubpath and ftpExposedRoot, gains
   dataSubpath; submit() wires it into AppConfig.
6. `MainActivity.kt` — one wizard field "Data directory (under USB root; blank = root)";
   remove the FTP-step root field.
7. `TransmissionConfig.kt` — ensureSettingsJson() reads cfg.dataSubpath.
8. `DaemonService.kt` — both FtpServerController constructions use cfg.dataSubpath;
   CrawlerProxyServer construction gains the new handler deps.
9. `transmission/TransmissionRpcClient.kt` — add TorrentAddResult data class and
   torrentAdd(filename, downloadDir): TorrentAddResult via call("torrent-add", args).
10. New `CrawlerAddHandler.kt` — the plain logic class per D2 steps 1-8, exposing
    add(hashHex): CrawlerAddOutcome, where CrawlerAddOutcome is a sealed type with
    toJson(): String plus an httpStatus (400 bad_hash, 404 not_found, 200 otherwise;
    502 for rpc_failed).
11. `CrawlerProxyServer.kt` — register route("/api/transmission/add") BEFORE the tail
    card; auth via resolveAuthToken with JSON 401 on failure; call the handler and
    respondText(json, ContentType.Application.Json, status).
12. `ControlServer.kt` — transmission panel keeps the single dir field (name
    dataSubpath, label per D1); FTP panel drops its root input; POST handlers carry
    dataSubpath through the existing apply flow; when dataSubpath changed, the apply
    path must also rebuild/restart FTP (mirror the existing post-apply FTP rebuild).
13. Tests: update `config/ConfigStoreTest.kt` (legacy-key fallback + new key),
    `config/ConfigValidatorTest.kt`, `config/TransmissionApplyModeTest.kt`,
    `config/WizardSubmitTest.kt`, `CrawlerProxyServerTest.kt` (new route auth +
    tail passthrough still works). Add `CrawlerAddHandlerTest.kt` (Robolectric):
    temp SQLite DB with the crawler torrents schema, covering found / not_found /
    bad_hash / db_unavailable paths, free-space compare logic with a fake gateway
    lambda, and the magnet string (dn URL-encoding). Add or extend a
    transmission test asserting the torrent-add request body shape
    (filename, download-dir, bandwidthPriority 0) using the existing pure-helper
    style (buildRequestBody or a small body builder).
14. Validation (JDK 21 required):

   export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
   export ANDROID_HOME="$HOME/Android/Sdk"
   export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/android-ndk-r27"
   ./gradlew :app:testDebugUnitTest :app:assembleDebug

    All green before handoff.

Do NOT touch app/src/main/cpp/dht_crawler (Task 2). Do NOT commit — the parent
reviews and commits.

## Task 2 - crawler submodule (second implementer, after Task 1)

1. `src/http_api.c` — button + JS per D3 (search results).
2. `src/http_torrent_page.c` — button + JS per D3 (torrent page).
3. Grep for other callers of fallbackCopy / showCopySuccess / copyMagnet before
   removing; remove only unreferenced helpers.
4. Validation: the submodule builds as part of the app via the
   ./gradlew :app:buildDhtCrawler task (CMake + NDK, env above); `make` in the
   submodule (gcc) should also still compile the desktop target. No C unit tests
   exist; keep the diff minimal.

## Task 3 - integrate, install, commit (parent)

1. ./gradlew installDebug (env above) — installs on the phone
   (adb target 192.168.0.113:37079, CPH2305).
2. Commit the submodule first, then the parent repo (gitlink + Kotlin + plan).
3. Manual acceptance on the phone: log in on :8090, open crawler search on :8080,
   click the magnet button, torrent appears in transmission (normal priority, data
   dir root); a bigger-than-free torrent reports total free space.

## Acceptance

- Magnet buttons on /search results and the /torrent page add to transmission or
  report exactly why not; nothing is copied to the clipboard anymore.
- Insufficient space message includes the torrent size and the user's total free space.
- One dataSubpath setting drives transmission download-dir AND the FTP root; existing
  installs keep their torrent_data value via the legacy-key fallback.
- ./gradlew :app:testDebugUnitTest :app:assembleDebug green; app installed on the
  phone; both repos committed.