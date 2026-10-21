package com.torrent.app.config

object WizardSubmit {
    data class Form(
        val txUsername: String,
        val txPassword: String,
        val peerPort: Int,
        val dataSubpath: String,
        val ftpUsername: String,
        val ftpPassword: String,
        val ftpPort: Int,
        val ftpWifiOnly: Boolean,
        val crawlerEnabled: Boolean = false,
        /** `adult_mode` choice: `"tag"` (default) or `"drop"`. */
        val crawlerAdultMode: String = "tag",
        /**
         * `save_categories` selection. Empty = save everything. Normalised to
         * [CrawlerTaxonomy.CATEGORIES] order by [submit].
         */
        val crawlerSaveCategories: List<String> = listOf("movie", "tv", "anime", "music"),
        val r2Endpoint: String = "",
        val r2Bucket: String = "",
        val r2ObjectKey: String = ConfigDefaults.BACKUP_OBJECT_KEY,
        val r2AccessKeyId: String = "",
        val r2SecretAccessKey: String = "",
        val uploadEnabled: Boolean = false,
        val dbBackupEnabled: Boolean = false,
        val r2DbBucket: String = ConfigDefaults.DB_BACKUP_BUCKET,
        val r2DbObjectPrefix: String = ConfigDefaults.DB_BACKUP_OBJECT_PREFIX,
        val bootstrapEnabled: Boolean = false,
        val bootstrapUrl: String = "",
    )

    data class Outcome(
        val ok: Boolean,
        val errors: List<String>,
        val transmission: TransmissionSettings?,
        val ftp: FtpSettings?,
        val backup: BackupSettings?,
        val crawlerEnabled: Boolean,
        /** The requested (normalised) adult mode; only meaningful when [ok]. */
        val crawlerAdultMode: String,
        /** The normalised `save_categories` selection; empty = save all. */
        val crawlerSaveCategories: List<String>,
    )

    fun submit(f: Form): Outcome {
        val tx = TransmissionSettings(
            f.txUsername, f.txPassword, f.peerPort,
            speedLimitDown = 0, speedLimitUp = 0, altSpeedDown = 0, altSpeedUp = 0,
        )
        val ftp = FtpSettings(f.ftpUsername, f.ftpPassword, f.ftpPort, f.ftpWifiOnly)
        val backup = backupSettings(f)
        val cfg = AppConfig(
            wizardCompleted = true,
            transmission = tx,
            ftp = ftp,
            crawler = CrawlerSettings(),
            backup = backup,
            dataSubpath = f.dataSubpath,
        )
        val adultMode = f.crawlerAdultMode.trim().lowercase()
        val (categoryErrors, saveCategories) = normalizeSaveCategories(f.crawlerSaveCategories)
        val errors = ConfigValidator.validateTransmission(tx).errors +
                     ConfigValidator.validateFtp(ftp).errors +
                     ConfigValidator.validateDataDir(cfg).errors +
                     ConfigValidator.validateBackup(backup).errors +
                     ConfigValidator.validateDbBackup(backup).errors +
                     ConfigValidator.validateAdultMode(f.crawlerAdultMode).errors +
                     categoryErrors
        return if (errors.isEmpty())
                   Outcome(
                       true, emptyList(), tx, ftp, backup, f.crawlerEnabled, adultMode, saveCategories,
                   )
               else Outcome(false, errors, null, null, null, false, adultMode, saveCategories)
    }

    /**
     * Builds the app-side [BackupSettings] from the wizard form. Shared by
     * [submit] and by the restore section's validation so both see exactly the
     * same R2 endpoint/keys/DB bucket/prefix mapping.
     */
    fun backupSettings(f: Form): BackupSettings = BackupSettings(
        r2Endpoint = f.r2Endpoint,
        r2Bucket = f.r2Bucket,
        r2ObjectKey = f.r2ObjectKey,
        r2AccessKeyId = f.r2AccessKeyId,
        r2SecretAccessKey = f.r2SecretAccessKey,
        uploadEnabled = f.uploadEnabled,
        dbBackupEnabled = f.dbBackupEnabled,
        r2DbBucket = f.r2DbBucket,
        r2DbObjectPrefix = f.r2DbObjectPrefix,
        bootstrapEnabled = f.bootstrapEnabled,
        bootstrapUrl = f.bootstrapUrl,
    )

    /**
     * Normalises a `save_categories` selection to names the crawler knows.
     *
     * Matching is trim + lowercase (the crawler compares case-insensitively) and
     * the result is re-ordered to [CrawlerTaxonomy.CATEGORIES] order — the order
     * the config writer and the settings form use. Unknown names become
     * validation errors rather than being written. An empty (or all-blank)
     * selection stays empty, which is the documented "save all categories".
     */
    internal fun normalizeSaveCategories(raw: List<String>): Pair<List<String>, List<String>> {
        val names = raw.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val errors = names.filter { it !in CrawlerTaxonomy.CATEGORIES }.distinct().map {
            "unknown category '$it' — not one of ${CrawlerTaxonomy.CATEGORIES.joinToString(", ")}"
        }
        val selected = CrawlerTaxonomy.CATEGORIES.filter { it in names }
        return errors to selected
    }
}
