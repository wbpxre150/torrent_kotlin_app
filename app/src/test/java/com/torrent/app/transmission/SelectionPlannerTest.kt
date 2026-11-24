package com.torrent.app.transmission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionPlannerTest {

    private val multiFileTorrent = "VideoHive - Film Color Grading LUTs Pack V2 40426237"

    private fun entry(name: String, length: Long) = TorrentFileEntry(name, length, 0L)

    private fun multiFileFixture() = listOf(
        entry("$multiFileTorrent/Help File/Help_Guide.txt", 1_000L),
        entry("$multiFileTorrent/LUTs/Pack/a.cube", 2_000L),
        entry("$multiFileTorrent/LUTs/Pack/b.cube", 3_000L),
        entry("$multiFileTorrent/preview.jpg", 500L),
    )

    @Test fun planUnwanted_multiFileTorrentPrefixesTheTorrentName() {
        val match = SelectionPlanner.planUnwanted(
            multiFileTorrent,
            multiFileFixture(),
            setOf("Help File/Help_Guide.txt"),
        )
        assertEquals(
            SelectionPlanner.SelectionMatch.Matched(listOf(0)),
            match,
        )
    }

    @Test fun planUnwanted_singleFileTorrentMatchesTheBareFileName() {
        val match = SelectionPlanner.planUnwanted(
            "solo.mkv",
            listOf(entry("solo.mkv", 7_000L)),
            setOf("solo.mkv"),
        )
        assertEquals(SelectionPlanner.SelectionMatch.Matched(listOf(0)), match)
    }

    @Test fun planUnwanted_rootRelativeFileInsideMultiFileTorrentIsStillPrefixed() {
        val match = SelectionPlanner.planUnwanted(
            multiFileTorrent,
            multiFileFixture(),
            setOf("preview.jpg"),
        )
        assertEquals(SelectionPlanner.SelectionMatch.Matched(listOf(3)), match)
    }

    @Test fun planUnwanted_multiplePathsYieldAscendingDeduplicatedIndices() {
        val match = SelectionPlanner.planUnwanted(
            multiFileTorrent,
            multiFileFixture(),
            setOf("LUTs/Pack/b.cube", "Help File/Help_Guide.txt"),
        )
        assertEquals(SelectionPlanner.SelectionMatch.Matched(listOf(0, 2)), match)
    }

    @Test fun planUnwanted_partialMismatchListsExactlyTheUnknownPaths() {
        val match = SelectionPlanner.planUnwanted(
            multiFileTorrent,
            multiFileFixture(),
            setOf("Help File/Help_Guide.txt", "Unknown/extra.txt", "LUTs/Pack/b.cube"),
        )
        assertEquals(
            SelectionPlanner.SelectionMatch.Mismatch(listOf("Unknown/extra.txt")),
            match,
        )
    }

    @Test fun planUnwanted_metadataLessSnapshotIsAMismatch() {
        val match = SelectionPlanner.planUnwanted(
            multiFileTorrent,
            emptyList(),
            setOf("Help File/Help_Guide.txt"),
        )
        assertTrue(match is SelectionPlanner.SelectionMatch.Mismatch)
        assertEquals(
            listOf("Help File/Help_Guide.txt"),
            (match as SelectionPlanner.SelectionMatch.Mismatch).missingPaths,
        )
    }

    @Test fun planUnwanted_emptyDeselectionMatchesWithNoIndices() {
        assertEquals(
            SelectionPlanner.SelectionMatch.Matched(emptyList()),
            SelectionPlanner.planUnwanted(multiFileTorrent, multiFileFixture(), emptySet()),
        )
    }

    @Test fun planUnwanted_duplicateNamesMapToTheFirstIndex() {
        val files = listOf(
            entry("dup.mkv", 1L),
            entry("dup.mkv", 2L),
        )
        assertEquals(
            SelectionPlanner.SelectionMatch.Matched(listOf(0)),
            SelectionPlanner.planUnwanted("dup.mkv", files, setOf("dup.mkv")),
        )
    }

    @Test fun selectedTotalBytes_subtractsTheDeselectedSizes() {
        val allFiles = listOf(
            "Help File/Help_Guide.txt" to 1_000L,
            "LUTs/Pack/a.cube" to 2_000L,
            "preview.jpg" to 500L,
        )
        assertEquals(
            1_500L,
            SelectionPlanner.selectedTotalBytes(
                allFiles,
                setOf("LUTs/Pack/a.cube"),
            ),
        )
        assertEquals(
            3_500L,
            SelectionPlanner.selectedTotalBytes(allFiles, emptySet()),
        )
        assertEquals(
            0L,
            SelectionPlanner.selectedTotalBytes(allFiles, setOf("Help File/Help_Guide.txt", "LUTs/Pack/a.cube", "preview.jpg")),
        )
    }

    @Test fun selectedTotalBytes_unknownDeselectedPathIsNull() {
        val allFiles = listOf("preview.jpg" to 500L)
        assertNull(SelectionPlanner.selectedTotalBytes(allFiles, setOf("nope.txt")))
        assertNull(SelectionPlanner.selectedTotalBytes(emptyList(), setOf("preview.jpg")))
    }

    @Test fun namesMatch_requiresByteIdenticalNames() {
        assertTrue(SelectionPlanner.namesMatch(multiFileTorrent, multiFileTorrent))
        assertFalse(SelectionPlanner.namesMatch(multiFileTorrent, "$multiFileTorrent "))
    }
}
