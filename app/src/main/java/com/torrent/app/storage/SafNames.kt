package com.torrent.app.storage

/**
 * Pure helpers for FAT-illegal filename handling. No Android types.
 *
 * The USB volume is served by `ExternalStorageProvider` over exFAT. On create it
 * silently rewrites characters that are illegal on FAT (`" * / : < > ? \ |`, C0
 * controls, DEL) to `_`, so a document requested as `Canadian | SDH.eng.HI.srt`
 * comes back with display name `Canadian _ SDH.eng.HI.srt`. Torrent metadata can
 * contain those characters (the YTS `Subs/Canadian | SDH.eng.HI.srt` case), so the
 * gateway must treat the rewrite as an *alias*, not as a name collision: the
 * requested path maps to the transliterated document for the whole lifetime of the
 * download.
 *
 * [sanitize] mirrors AOSP `android.os.FileUtils.isValidFatFilenameChar` so the
 * predicted on-disk name matches what the provider actually creates. It is
 * idempotent, which is what makes it usable as a resolution fallback.
 *
 * Deliberately NOT mirrored: AOSP's 255-char truncation and its `(invalid)` value
 * for `.`/`..`/empty. Both would turn a detectable failure into a silent alias to a
 * different name; over-long leaves must keep failing loudly.
 *
 * Known limit: two torrent files whose names differ only by FAT-illegal characters
 * (`a|b.srt` and `a_b.srt`) cannot coexist on the volume. The create path is safe —
 * the provider dedups the second and [isAliasFor] rejects the `" (1)"` suffix — but
 * the resolve-walk fallback (needed so an existing transliterated file is found again
 * after a cache reset) would match the sibling. Such a torrent cannot be stored
 * faithfully on this volume either way; it fails at create instead of corrupting.
 */
object SafNames {
    /** Characters illegal on FAT (and therefore rewritten by ExternalStorageProvider). */
    private const val ILLEGAL = "\"*/:<>?\\|"

    /** Replacement the provider substitutes for each illegal character. */
    const val REPLACEMENT = '_'

    /**
     * The name the provider will actually store for [name]: every FAT-illegal
     * character replaced by [REPLACEMENT]. Idempotent.
     */
    fun sanitize(name: String): String {
        if (isRepresentable(name)) return name
        val out = StringBuilder(name.length)
        for (c in name) out.append(if (isLegal(c)) c else REPLACEMENT)
        return out.toString()
    }

    /** True when [name] can be stored verbatim on the volume. */
    fun isRepresentable(name: String): Boolean = name.all(::isLegal)

    /**
     * True when [actualName] (the display name a provider reported) is an acceptable
     * stand-in for the [requestedLeaf] the daemon asked for: either an exact match,
     * or exactly the provider's transliteration of it.
     *
     * Anything else — notably the `" (1)"` suffix a provider appends to a duplicate —
     * is a genuine collision and must not be accepted.
     */
    fun isAliasFor(requestedLeaf: String, actualName: String): Boolean =
        actualName == requestedLeaf || actualName == sanitize(requestedLeaf)

    private fun isLegal(c: Char): Boolean {
        if (c.code <= 0x1F || c.code == 0x7F) return false
        return ILLEGAL.indexOf(c) < 0
    }
}
