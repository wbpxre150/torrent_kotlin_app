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
        if (!validSubpath(s.downloadSubpath))
            errors.add("download subpath must be relative, no '..', alphanumeric/./-/_/space only")
        return ValidationResult(errors.isEmpty(), errors)
    }

    fun validateFtp(s: FtpSettings): ValidationResult {
        val errors = mutableListOf<String>()
        if (s.username.isBlank()) errors.add("username must not be empty")
        if (s.password.length < ConfigDefaults.PASSWORD_MIN_LEN)
            errors.add("password must be at least ${ConfigDefaults.PASSWORD_MIN_LEN} characters")
        if (!validPort(s.port)) errors.add("port must be 1..65535")
        if (!validSubpath(s.exposedRoot))
            errors.add("exposed root must be relative, no '..', alphanumeric/./-/_/space only")
        return ValidationResult(errors.isEmpty(), errors)
    }

    private fun validPort(p: Int) = p in 1..65535

    private fun validSubpath(p: String): Boolean {
        if (p.isEmpty()) return true
        if (p.startsWith("/")) return false
        if (p.contains("..")) return false
        return p.split('/').all { it.isNotEmpty() && it.matches(Regex("[A-Za-z0-9._ -]+")) }
    }
}