package com.torrent.app.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafNamesTest {

    @Test fun sanitize_pipeBecomesUnderscore() {
        // The device case: YTS torrents ship 'Subs/Canadian | SDH.eng.HI.srt'.
        assertEquals("Canadian _ SDH.eng.HI.srt", SafNames.sanitize("Canadian | SDH.eng.HI.srt"))
    }

    @Test fun sanitize_replacesEveryFatIllegalChar() {
        val illegal = "\"*/:<>?\\|"
        for (c in illegal) {
            assertEquals("a${SafNames.REPLACEMENT}b", SafNames.sanitize("a${c}b"))
        }
    }

    @Test fun sanitize_replacesControlCharsAndDel() {
        assertEquals("a_b", SafNames.sanitize("a\u0000b"))
        assertEquals("a_b", SafNames.sanitize("a\u001fb"))
        assertEquals("a_b", SafNames.sanitize("a\u007fb"))
    }

    @Test fun sanitize_keepsLegalChars() {
        val legal = "The.Apprentice (2024) [1080p] - x265_10bit+5.1, #1 @ 'q' & \$100% é ü 中文"
        assertEquals(legal, SafNames.sanitize(legal))
    }

    @Test fun sanitize_isIdempotent() {
        val once = SafNames.sanitize("a|b:c?d")
        assertEquals(once, SafNames.sanitize(once))
    }

    @Test fun isRepresentable() {
        assertTrue(SafNames.isRepresentable("movie.mp4"))
        assertFalse(SafNames.isRepresentable("movie|1.mp4"))
        assertFalse(SafNames.isRepresentable("S01:E02.mkv"))
    }

    @Test fun isAliasFor_exactMatchIsAccepted() {
        assertTrue(SafNames.isAliasFor("movie.mp4", "movie.mp4"))
    }

    @Test fun isAliasFor_transliterationIsAccepted() {
        assertTrue(SafNames.isAliasFor("Canadian | SDH.eng.HI.srt", "Canadian _ SDH.eng.HI.srt"))
        assertTrue(SafNames.isAliasFor("a:b?c.srt", "a_b_c.srt"))
    }

    @Test fun isAliasFor_providerDedupSuffixIsRejected() {
        // A " (1)" suffix means a *different* file already owns that name: unrecoverable.
        assertFalse(SafNames.isAliasFor("Canadian | SDH.eng.HI.srt", "Canadian _ SDH.eng.HI.srt (1)"))
        assertFalse(SafNames.isAliasFor("movie.mp4", "movie.mp4 (1)"))
    }

    @Test fun isAliasFor_unrelatedNameIsRejected() {
        assertFalse(SafNames.isAliasFor("movie.mp4", "other.mp4"))
        assertFalse(SafNames.isAliasFor("a|b.srt", "a_b.mkv"))
    }

    @Test fun isAliasFor_collisionBetweenTwoIllegalNamesIsRejected() {
        // 'a|b.srt' and 'a_b.srt' both want 'a_b.srt'; the second gets " (1)" and must fail.
        assertFalse(SafNames.isAliasFor("a|b.srt", "a_b.srt (1)"))
        assertTrue(SafNames.isAliasFor("a_b.srt", "a_b.srt"))
    }
}
