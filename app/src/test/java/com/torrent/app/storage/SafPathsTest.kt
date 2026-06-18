package com.torrent.app.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SafPathsTest {

    @Test fun normalize_empty_isRoot() {
        assertEquals("", SafPaths.normalize(""))
    }

    @Test fun normalize_stripsAndCollapsesSlashes() {
        assertEquals("a/b/c", SafPaths.normalize("/a//b///c/"))
        assertEquals("a/b/c", SafPaths.normalize("a/b/c"))
    }

    @Test fun normalize_rejectsDotDot() {
        assertThrows(IllegalArgumentException::class.java) {
            SafPaths.normalize("a/../b")
        }
    }

    @Test fun normalize_rejectsDotSegment() {
        assertThrows(IllegalArgumentException::class.java) {
            SafPaths.normalize("a/./b")
        }
    }

    @Test fun normalize_rejectsBackslash() {
        assertThrows(IllegalArgumentException::class.java) {
            SafPaths.normalize("a\\b")
        }
    }

    @Test fun splitParent_root_isNull() {
        assertEquals(null, SafPaths.splitParent(""))
    }

    @Test fun splitParent_single_isEmptyParent() {
        assertEquals("" to "file.bin", SafPaths.splitParent("file.bin"))
    }

    @Test fun splitParent_nested() {
        assertEquals("a/b" to "c.bin", SafPaths.splitParent("a/b/c.bin"))
    }

    @Test fun mapMode_known() {
        assertEquals("r", SafPaths.mapMode("r"))
        assertEquals("r", SafPaths.mapMode("ro"))
        assertEquals("rw", SafPaths.mapMode("rw"))
        assertEquals("rwt", SafPaths.mapMode("rwt"))
    }

    @Test fun mapMode_unknown_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            SafPaths.mapMode("w")
        }
    }
}