package com.torrent.app.config

data class ValidationResult(val ok: Boolean, val errors: List<String>)

object ConfigValidator {
    fun validateTransmission(s: TransmissionSettings): ValidationResult {
        val errors = mutableListOf<String>()
        if (s.username.isBlank()) errors.add("username must not be empty")
        if (s.password.length < ConfigDefaults.PASSWORD_MIN_LEN)
            errors.add("password must be at least ${ConfigDefaults.PASSWORD_MIN_LEN} characters")
        if (!validPort(s.peerPort)) errors.add("peer port must be 1..65535")
        if (s.speedLimitDown < 0) errors.add("speed-limit-down must be >= 0")
        if (s.speedLimitUp < 0) errors.add("speed-limit-up must be >= 0")
        if (s.altSpeedDown < 0) errors.add("alt-speed-down must be >= 0")
        if (s.altSpeedUp < 0) errors.add("alt-speed-up must be >= 0")
        return ValidationResult(errors.isEmpty(), errors)
    }

    fun validateFtp(s: FtpSettings): ValidationResult {
        val errors = mutableListOf<String>()
        if (s.username.isBlank()) errors.add("username must not be empty")
        if (s.password.length < ConfigDefaults.PASSWORD_MIN_LEN)
            errors.add("password must be at least ${ConfigDefaults.PASSWORD_MIN_LEN} characters")
        if (!validPort(s.port)) errors.add("port must be 1..65535")
        if (s.maxDownloadKiBps < 0) errors.add("max download must be >= 0")
        return ValidationResult(errors.isEmpty(), errors)
    }

    /** Shared data directory (Stage 11): one setting drives the transmission download dir and the FTP root. */
    fun validateDataDir(cfg: AppConfig): ValidationResult {
        val errors = mutableListOf<String>()
        if (!validSubpath(cfg.dataSubpath))
            errors.add("data directory must be relative, no '..', alphanumeric/./-/_/space only")
        return ValidationResult(errors.isEmpty(), errors)
    }

    /**
     * Validates crawler settings. Ranges follow the C crawler's `config.c`
     * clamping where it has one (find_node/BEP51 1..100, get_peers 1..1000,
     * num_trees 1..64, nice -20..19); the C side caps metadata workers only
     * at 1, so a sane 1..1000 is enforced here. Advanced-override parse
     * errors are included.
     */
    fun validateCrawler(s: CrawlerSettings): List<String> {
        val errors = mutableListOf<String>()
        if (!validPort(s.dhtPort)) errors.add("DHT port must be 1..65535")
        if (!validPort(s.proxyPort)) errors.add("proxy port must be 1..65535")
        if (!validPort(s.crawlerHttpPort)) errors.add("crawler HTTP port must be 1..65535")
        if (s.proxyPort == s.crawlerHttpPort)
            errors.add("proxy port and crawler HTTP port must differ")
        if (s.numTrees !in 1..64) errors.add("num trees must be 1..64")
        if (s.treeFindNodeWorkers !in 1..100) errors.add("find node workers must be 1..100")
        if (s.treeBep51Workers !in 1..100) errors.add("BEP51 workers must be 1..100")
        if (s.treeGetPeersWorkers !in 1..1000) errors.add("get peers workers must be 1..1000")
        if (s.treeMetadataWorkers !in 1..1000) errors.add("metadata workers must be 1..1000")
        if (s.niceLevel !in -20..19) errors.add("nice level must be -20..19")
        if (s.logLevel !in CrawlerKeys.LOG_LEVELS)
            errors.add("log level must be one of DEBUG, INFO, WARN, ERROR")
        if (s.languageFilterNonLatinThreshold !in 0..100)
            errors.add("non-Latin threshold must be 0..100")
        if (s.bloomErrorRate <= 0.0 || s.bloomErrorRate >= 1.0)
            errors.add("bloom error rate must be > 0 and < 1")
        if (s.treeInfohashQueueCapacity <= 0) errors.add("infohash queue capacity must be > 0")
        if (s.treePeersQueueCapacity <= 0) errors.add("peers queue capacity must be > 0")
        if (s.bloomCapacity <= 0) errors.add("bloom capacity must be > 0")
        if (s.failureBloomCapacity <= 0) errors.add("failure bloom capacity must be > 0")
        errors.addAll(validateAdultMode(s.adultMode).errors)
        errors.addAll(validateClassifyBackfillMode(s.classifyBackfillMode).errors)
        errors.addAll(validateFraction("adult hide threshold", s.adultHideThreshold))
        errors.addAll(CrawlerKeys.parseAdvanced(s.advancedOverrides).errors)

        return errors
    }

    /**
     * `adult_mode` is `tag` or `drop` (the C crawler compares case-sensitively
     * and keeps the previous value on a mismatch, so the input is normalised
     * here and by the config writer).
     */
    fun validateAdultMode(raw: String): ValidationResult {
        val v = raw.trim().lowercase()
        return if (v in CrawlerKeys.ADULT_MODES) ValidationResult(true, emptyList())
        else ValidationResult(false, listOf("adult mode must be one of ${CrawlerKeys.ADULT_MODES.joinToString(", ")}"))
    }

    /**
     * `classify_backfill_mode` is `all`, `adult` or `off`. Like `adult_mode` the C
     * crawler compares case-sensitively and keeps the previous value on a mismatch,
     * so the input is normalised here and by the config writer.
     */
    fun validateClassifyBackfillMode(raw: String): ValidationResult {
        val v = raw.trim().lowercase()
        return if (v in CrawlerKeys.CLASSIFY_BACKFILL_MODES) ValidationResult(true, emptyList())
        else ValidationResult(
            false,
            listOf("classify backfill mode must be one of ${CrawlerKeys.CLASSIFY_BACKFILL_MODES.joinToString(", ")}")
        )
    }

    /**
     * Adult probability thresholds must be inside `(0, 1]`. 0 is rejected: for
     * `adult_hide_threshold` it is the crawler's hidden "use the manifest"
     * escape hatch, which is not something the form should write.
     */
    fun validateFraction(name: String, value: Double): List<String> =
        if (value > 0.0 && value <= 1.0) emptyList()
        else listOf("$name must be > 0 and <= 1")


    /**
     * Validates the app-side backup/bootstrap settings (BEP51 cache backup).
     * App-side only — nothing here reaches the crawler config.ini. When a
     * feature is disabled its values may be empty; when enabled the R2
     * endpoint/bucket/keys (upload) or the bootstrap URL must be present,
     * and every URL must be `https://` with a non-empty host (plain
     * `http://` is rejected).
     */
    fun validateBackup(b: BackupSettings): ValidationResult {
        val errors = mutableListOf<String>()
        if (b.uploadEnabled) {
            if (b.r2Endpoint.isBlank()) errors.add("R2 endpoint must not be empty when upload is enabled")
            else if (!validHttpsUrl(b.r2Endpoint))
                errors.add("R2 endpoint must be an https:// URL with a host")
            if (b.r2Bucket.isBlank()) errors.add("R2 bucket must not be empty when upload is enabled")
            if (b.r2AccessKeyId.isBlank()) errors.add("R2 access key ID must not be empty when upload is enabled")
            if (b.r2SecretAccessKey.isBlank()) errors.add("R2 secret access key must not be empty when upload is enabled")
        }
        if (b.bootstrapEnabled && !validHttpsUrl(b.bootstrapUrl))
            errors.add("bootstrap URL must be an https:// URL with a host")
        return ValidationResult(errors.isEmpty(), errors)
    }

    /**
     * Validates the daily incremental DB-dump upload settings (plan 2,
     * Stage 3). Shares the R2 endpoint/bucket/keys with [validateBackup];
     * when [BackupSettings.dbBackupEnabled] is on the endpoint must be an
     * `https://` URL with a host and the DB bucket/keys/prefix must be
     * present. The prefix must be non-blank and free of '/' (it is the
     * single path segment of every dump object key, per
     * [com.torrent.app.backup.DbObjectKey.build]).
     */
    fun validateDbBackup(b: BackupSettings): ValidationResult {
        val errors = mutableListOf<String>()
        if (b.dbBackupEnabled) {
            if (b.r2Endpoint.isBlank()) errors.add("R2 endpoint must not be empty when DB backup is enabled")
            else if (!validHttpsUrl(b.r2Endpoint))
                errors.add("R2 endpoint must be an https:// URL with a host")
            if (b.r2DbBucket.isBlank()) errors.add("R2 DB bucket must not be empty when DB backup is enabled")
            if (b.r2AccessKeyId.isBlank()) errors.add("R2 access key ID must not be empty when DB backup is enabled")
            if (b.r2SecretAccessKey.isBlank()) errors.add("R2 secret access key must not be empty when DB backup is enabled")
            if (b.r2DbObjectPrefix.isBlank() || b.r2DbObjectPrefix.contains('/'))
                errors.add("R2 DB object prefix must be non-blank and must not contain '/'")
        }
        return ValidationResult(errors.isEmpty(), errors)
    }

    /**
     * Validates the wizard's optional "Restore crawler DB from R2" section
     * (section 6). It reuses section 5's R2 endpoint/keys and DB bucket/prefix,
     * so a restore can only work when those are present: the upload/bootstrap
     * checks of [validateBackup] apply, plus the [validateDbBackup] checks with
     * [BackupSettings.dbBackupEnabled] forced on (the restore reads exactly the
     * objects the DB dump writes). The restore switch itself is in-session only
     * and is not part of [BackupSettings].
     */
    fun validateDbRestore(b: BackupSettings): ValidationResult {
        val errors = validateBackup(b).errors +
                     validateDbBackup(b.copy(dbBackupEnabled = true)).errors
        return ValidationResult(errors.isEmpty(), errors)
    }
    /**
     * Validates the Cloudflare Tunnel settings.
     *
     * App-side only — nothing here reaches the crawler `config.ini`: the tunnel
     * token lives in SharedPreferences alone and [CrawlerKeys] would reject it as
     * an unknown key anyway. When the tunnel is enabled a token is mandatory.
     * [CloudflaredSettings.publicHostname] is optional (the daemon auto-detects it
     * while connected) but when present it must be a bare hostname — no scheme, no
     * port, no path.
     */
    fun validateCloudflared(c: CloudflaredSettings): ValidationResult {
        val errors = mutableListOf<String>()
        if (c.enabled && c.tunnelToken.isBlank())
            errors.add("tunnel token must not be empty when the tunnel is enabled")
        if (c.publicHostname.isNotBlank() && !validHostname(c.publicHostname))
            errors.add("public hostname must be a bare hostname (no scheme, port or path)")
        return ValidationResult(errors.isEmpty(), errors)
    }

    private fun validPort(p: Int) = p in 1..65535

    /** `https://` scheme with a non-empty host; `http://`, hostless and unparsable URLs are rejected. */
    internal fun validHttpsUrl(raw: String): Boolean {
        val uri = try { java.net.URI(raw.trim()) } catch (e: Exception) { return false }
        return uri.scheme?.equals("https", ignoreCase = true) == true && !uri.host.isNullOrBlank()
    }

    /**
     * Bare hostname — the host part [validHttpsUrl] accepts, without scheme, port
     * or path: one or more dot-separated `[A-Za-z0-9-]` labels that neither start
     * nor end with '-' (so `https://x.example.com`, `x.example.com:443`,
     * `x.example.com/path`, `x example.com` and a trailing dot are all rejected).
     */
    internal fun validHostname(raw: String): Boolean {
        val h = raw.trim()
        if (h.isEmpty() || h.length > 253) return false
        val label = Regex("[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
        return h.split('.').all { it.matches(label) }
    }

    private fun validSubpath(p: String): Boolean {
        if (p.isEmpty()) return true
        if (p.startsWith("/")) return false
        if (p.contains("..")) return false
        return p.split('/').all { it.isNotEmpty() && it.matches(Regex("[A-Za-z0-9._ -]+")) }
    }
}