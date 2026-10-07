package com.torrent.app.config

object WizardSubmit {
    data class Form(
        val txUsername: String,
        val txPassword: String,
        val peerPort: Int,
        val downloadSubpath: String,
    )

    data class Outcome(
        val ok: Boolean,
        val errors: List<String>,
        val transmission: TransmissionSettings?,
    )

    fun submit(f: Form): Outcome {
        val tx = TransmissionSettings(
            f.txUsername, f.txPassword, f.peerPort,
            speedLimitDown = 0, speedLimitUp = 0, altSpeedDown = 0, altSpeedUp = 0,
            downloadSubpath = f.downloadSubpath,
        )
        val errors = ConfigValidator.validateTransmission(tx).errors
        return if (errors.isEmpty()) Outcome(true, emptyList(), tx)
               else Outcome(false, errors, null)
    }
}
