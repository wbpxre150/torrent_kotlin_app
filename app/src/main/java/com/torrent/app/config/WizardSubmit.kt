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
    )

    fun submit(f: Form): Outcome {
        val tx = TransmissionSettings(
            f.txUsername, f.txPassword, f.peerPort,
            speedLimitDown = 0, speedLimitUp = 0, altSpeedDown = 0, altSpeedUp = 0,
        )
        val ftp = FtpSettings(f.ftpUsername, f.ftpPassword, f.ftpPort, f.ftpWifiOnly)
        val backup = BackupSettings(
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
        val cfg = AppConfig(
            wizardCompleted = true,
            transmission = tx,
            ftp = ftp,
            crawler = CrawlerSettings(),
            backup = backup,
            dataSubpath = f.dataSubpath,
        )
        val adultMode = f.crawlerAdultMode.trim().lowercase()
        val errors = ConfigValidator.validateTransmission(tx).errors +
                     ConfigValidator.validateFtp(ftp).errors +
                     ConfigValidator.validateDataDir(cfg).errors +
                     ConfigValidator.validateBackup(backup).errors +
                     ConfigValidator.validateDbBackup(backup).errors +
                     ConfigValidator.validateAdultMode(f.crawlerAdultMode).errors
        return if (errors.isEmpty())
                   Outcome(true, emptyList(), tx, ftp, backup, f.crawlerEnabled, adultMode)
               else Outcome(false, errors, null, null, null, false, adultMode)
    }
}
