package com.torrent.app.config

object WizardSubmit {
    data class Form(
        val txUsername: String,
        val txPassword: String,
        val peerPort: Int,
        val downloadSubpath: String,
        val ftpUsername: String,
        val ftpPassword: String,
        val ftpPort: Int,
        val ftpWifiOnly: Boolean,
        val ftpExposedRoot: String,
    )

    data class Outcome(
        val ok: Boolean,
        val errors: List<String>,
        val transmission: TransmissionSettings?,
        val ftp: FtpSettings?,
    )

    fun submit(f: Form): Outcome {
        val tx = TransmissionSettings(
            f.txUsername, f.txPassword, f.peerPort,
            speedLimitDown = 0, speedLimitUp = 0, altSpeedDown = 0, altSpeedUp = 0,
            downloadSubpath = f.downloadSubpath,
        )
        val ftp = FtpSettings(f.ftpUsername, f.ftpPassword, f.ftpPort, f.ftpWifiOnly, f.ftpExposedRoot)
        val errors = ConfigValidator.validateTransmission(tx).errors +
                     ConfigValidator.validateFtp(ftp).errors
        return if (errors.isEmpty()) Outcome(true, emptyList(), tx, ftp)
               else Outcome(false, errors, null, null)
    }
}
