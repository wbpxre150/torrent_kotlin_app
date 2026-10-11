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
        errors.addAll(CrawlerKeys.parseAdvanced(s.advancedOverrides).errors)
        return errors
    }

    private fun validPort(p: Int) = p in 1..65535

    private fun validSubpath(p: String): Boolean {
        if (p.isEmpty()) return true
        if (p.startsWith("/")) return false
        if (p.contains("..")) return false
        return p.split('/').all { it.isNotEmpty() && it.matches(Regex("[A-Za-z0-9._ -]+")) }
    }
}