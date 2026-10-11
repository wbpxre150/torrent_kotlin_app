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
    )

    data class Outcome(
        val ok: Boolean,
        val errors: List<String>,
        val transmission: TransmissionSettings?,
        val ftp: FtpSettings?,
        val crawlerEnabled: Boolean,
    )

    fun submit(f: Form): Outcome {
        val tx = TransmissionSettings(
            f.txUsername, f.txPassword, f.peerPort,
            speedLimitDown = 0, speedLimitUp = 0, altSpeedDown = 0, altSpeedUp = 0,
        )
        val ftp = FtpSettings(f.ftpUsername, f.ftpPassword, f.ftpPort, f.ftpWifiOnly)
        val cfg = AppConfig(
            wizardCompleted = true,
            transmission = tx,
            ftp = ftp,
            crawler = CrawlerSettings(),
            dataSubpath = f.dataSubpath,
        )
        val errors = ConfigValidator.validateTransmission(tx).errors +
                     ConfigValidator.validateFtp(ftp).errors +
                     ConfigValidator.validateDataDir(cfg).errors
        return if (errors.isEmpty()) Outcome(true, emptyList(), tx, ftp, f.crawlerEnabled)
               else Outcome(false, errors, null, null, false)
    }
}
