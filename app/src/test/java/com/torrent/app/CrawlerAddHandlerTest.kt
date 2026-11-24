package com.torrent.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.torrent.app.config.ConfigDefaults
import com.torrent.app.config.ConfigStore
import com.torrent.app.storage.SafStorageGateway
import com.torrent.app.transmission.TorrentAddResult
import com.torrent.app.transmission.TorrentAdder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * CrawlerAddHandler (plan 11 D2) against a real temp SQLite DB with the
 * crawler torrents schema, a Mockito fake SAF gateway and a fake
 * [TorrentAdder] (no live transmission daemon).
 */
@RunWith(RobolectricTestRunner::class)
class CrawlerAddHandlerTest {

    private companion object {
        const val HASH = "a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0"
        const val NAME = "Show S01 (720p)"
        const val SIZE = 1_500_000_000L
        const val SUBPATH = "torrent_data"

        // The torrent's file rows: two prefixed files and one root-level file,
        // summing to SIZE. FILE_A/FILE_B share the "Season 1" prefix.
        const val FILE_A = "Season 1/E01.mkv"
        const val FILE_B = "Season 1/E02.mkv"
        const val FILE_C = "sample.nfo"
        const val SIZE_A = 700_000_000L
        const val SIZE_B = 700_000_000L
        const val SIZE_C = 100_000_000L
    }

    private lateinit var context: Context
    private lateinit var store: ConfigStore
    private lateinit var dbFile: File

    @Volatile private var rpcResult = TorrentAddResult(true, false, null)
    @Volatile private var lastMagnet: String? = null
    @Volatile private var lastDownloadDir: String? = null

    private var registrarCalls = 0
    @Volatile private var registeredHash: String? = null
    @Volatile private var registeredName: String? = null
    @Volatile private var registeredPaths: Set<String>? = null
    @Volatile private var registeredTimeout = -1L

    private val recordingRegistrar = SelectionRegistrar { hash, name, paths, timeout ->
        registrarCalls++
        registeredHash = hash
        registeredName = name
        registeredPaths = paths
        registeredTimeout = timeout
    }

    private val fakeRpc = object : TorrentAdder {
        override fun torrentAdd(filename: String, downloadDir: String): TorrentAddResult {
            lastMagnet = filename
            lastDownloadDir = downloadDir
            return rpcResult
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = ConfigStore(context)
        store.save(ConfigDefaults.INITIAL.copy(dataSubpath = SUBPATH))
        rpcResult = TorrentAddResult(true, false, null)
        lastMagnet = null
        lastDownloadDir = null
        registrarCalls = 0
        registeredHash = null
        registeredName = null
        registeredPaths = null
        registeredTimeout = -1L
        dbFile = File(context.cacheDir, "torrents-test.db")
        dbFile.delete()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { sdb ->
            sdb.execSQL(
                "CREATE TABLE torrents (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "info_hash BLOB(20) NOT NULL UNIQUE, name TEXT NOT NULL, " +
                    "size_bytes INTEGER NOT NULL, total_peers INTEGER DEFAULT 0, " +
                    "added_timestamp INTEGER NOT NULL)")
            sdb.insert("torrents", null, ContentValues().apply {
                put("info_hash", bytes40(HASH))
                put("name", NAME)
                put("size_bytes", SIZE)
                put("added_timestamp", 0)
            })
            sdb.execSQL(
                "CREATE TABLE path_prefixes (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "prefix TEXT NOT NULL UNIQUE)")
            sdb.execSQL(
                "CREATE TABLE torrent_files (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "torrent_id INTEGER NOT NULL, prefix_id INTEGER, " +
                    "filename TEXT NOT NULL, size_bytes INTEGER NOT NULL, " +
                    "file_index SMALLINT NOT NULL)")
            val torrentId = sdb.rawQuery("SELECT id FROM torrents", null).use { c ->
                c.moveToFirst()
                c.getLong(0)
            }
            val seasonId = sdb.insert("path_prefixes", null, ContentValues().apply {
                put("prefix", "Season 1")
            })
            fun insertFile(prefixId: Long?, filename: String, size: Long, index: Int) {
                sdb.insert("torrent_files", null, ContentValues().apply {
                    put("torrent_id", torrentId)
                    if (prefixId != null) put("prefix_id", prefixId)
                    put("filename", filename)
                    put("size_bytes", size)
                    put("file_index", index)
                })
            }
            insertFile(seasonId, "E01.mkv", SIZE_A, 0)
            insertFile(seasonId, "E02.mkv", SIZE_B, 1)
            insertFile(null, "sample.nfo", SIZE_C, 2)
        }
    }

    private fun handler(
        gatewayValue: SafStorageGateway? = Mockito.mock(SafStorageGateway::class.java),
        db: File? = dbFile,
        freeBytes: Long = 10L * 1024 * 1024 * 1024,
        registrar: SelectionRegistrar? = null,
    ): CrawlerAddHandler {
        gatewayValue?.let { Mockito.`when`(it.availableBytes()).thenReturn(freeBytes) }
        return CrawlerAddHandler(
            store = store,
            gateway = { gatewayValue },
            crawlerDbFile = { db },
            rpcFactory = { _, _ -> fakeRpc },
            selectionRegistrar = registrar,
        )
    }

    @Test
    fun found_returnsAddedWithDbNameCheckedAndMagnetIntoDataDirRoot() {
        val outcome = handler().add(HASH)
        assertEquals(200, outcome.httpStatus)
        assertEquals(
            """{"ok":true,"added":true,"duplicate":false,"name":"Show S01 (720p)","checked":true}""",
            outcome.toJson())
        assertEquals("magnet:?xt=urn:btih:$HASH&dn=Show%20S01%20%28720p%29", lastMagnet)
        assertEquals("/saf/usb/$SUBPATH", lastDownloadDir)
    }

    @Test
    fun found_blankDataSubpath_downloadsToUsbRoot() {
        store.save(ConfigDefaults.INITIAL.copy(dataSubpath = "")) // blank subpath downloads to USB root
        handler().add(HASH)
        assertEquals("/saf/usb", lastDownloadDir)
    }

    @Test
    fun duplicateRpcResult_setsDuplicateTrue() {
        rpcResult = TorrentAddResult(true, true, null)
        val outcome = handler().add(HASH)
        assertEquals(
            """{"ok":true,"added":true,"duplicate":true,"name":"Show S01 (720p)","checked":true}""",
            outcome.toJson())
    }

    @Test
    fun unknownFreeSpace_skipsCheckAndReportsCheckedFalse() {
        val outcome = handler(freeBytes = Long.MAX_VALUE).add(HASH)
        assertEquals(
            """{"ok":true,"added":true,"duplicate":false,"name":"Show S01 (720p)","checked":false}""",
            outcome.toJson())
    }

    @Test
    fun notFound_returns404() {
        val outcome = handler().add("f".repeat(40))
        assertEquals(404, outcome.httpStatus)
        assertEquals("""{"ok":false,"error":"not_found"}""", outcome.toJson())
    }

    @Test
    fun badHash_39CharsUppercaseOrNonHex_returns400() {
        for (bad in listOf(HASH.dropLast(1), "A" + HASH.drop(1), "g" + HASH.drop(1))) {
            val outcome = handler().add(bad)
            assertEquals(400, outcome.httpStatus)
            assertEquals("""{"ok":false,"error":"bad_hash"}""", outcome.toJson())
        }
    }

    @Test
    fun dbUnavailable_nullFileOrMissingFile_returns503() {
        for (db in listOf<File?>(null, File(context.cacheDir, "no-such-db.db"))) {
            val outcome = handler(db = db).add(HASH)
            assertEquals(503, outcome.httpStatus)
            assertEquals("""{"ok":false,"error":"db_unavailable"}""", outcome.toJson())
        }
    }

    @Test
    fun noStorage_returns503() {
        val outcome = handler(gatewayValue = null).add(HASH)
        assertEquals(503, outcome.httpStatus)
        assertEquals("""{"ok":false,"error":"no_storage"}""", outcome.toJson())
    }

    @Test
    fun insufficientSpace_returns507WithSizeAndFreeBytes() {
        val free = SIZE - 1
        val outcome = handler(freeBytes = free).add(HASH)
        assertEquals(507, outcome.httpStatus)
        assertEquals(
            """{"ok":false,"error":"insufficient_space","sizeBytes":$SIZE,"freeBytes":$free}""",
            outcome.toJson())
    }

    @Test
    fun rpcFailure_returns502() {
        rpcResult = TorrentAddResult(false, false, null)
        val outcome = handler().add(HASH)
        assertEquals(502, outcome.httpStatus)
        assertEquals("""{"ok":false,"error":"rpc_failed"}""", outcome.toJson())
    }

    // ---- addSelected (plan 12 stage 3) ----

    @Test
    fun addSelected_success_pinsJsonSelectedBytesAndMagnetParityWithAdd() {
        val outcome = handler(registrar = recordingRegistrar).addSelected(HASH, listOf(FILE_C), 600_000)
        assertEquals(200, outcome.httpStatus)
        assertEquals(
            """{"ok":true,"added":true,"duplicate":false,"name":"Show S01 (720p)","checked":true,"selectedBytes":${SIZE - SIZE_C},"unselectedCount":1,"selection":"pending"}""",
            outcome.toJson())
        assertEquals("/saf/usb/$SUBPATH", lastDownloadDir)

        val selectedMagnet = lastMagnet
        assertEquals("magnet:?xt=urn:btih:$HASH&dn=Show%20S01%20%28720p%29", selectedMagnet)
        lastMagnet = null
        handler().add(HASH) // the whole-torrent path builds a byte-identical magnet
        assertEquals(selectedMagnet, lastMagnet)
    }

    @Test
    fun addSelected_registersTheSelectionWithTheExactArguments() {
        val outcome = handler(registrar = recordingRegistrar)
            .addSelected(HASH, listOf(FILE_C, FILE_A), 600_000)
        assertEquals(200, outcome.httpStatus)
        assertEquals(1, registrarCalls)
        assertEquals(HASH, registeredHash)
        assertEquals(NAME, registeredName)
        assertEquals(setOf(FILE_A, FILE_C), registeredPaths)
        assertEquals(600_000L, registeredTimeout)
    }

    @Test
    fun addSelected_clampsTheSelectionTimeoutToTheHandlerBounds() {
        handler(registrar = recordingRegistrar).addSelected(HASH, listOf(FILE_C), 50)
        assertEquals(1_000L, registeredTimeout)
        handler(registrar = recordingRegistrar).addSelected(HASH, listOf(FILE_C), 10_000_000)
        assertEquals(900_000L, registeredTimeout)
    }

    @Test
    fun addSelected_duplicateAdd_stillSchedulesTheWatch() {
        rpcResult = TorrentAddResult(true, true, null)
        val outcome = handler(registrar = recordingRegistrar).addSelected(HASH, listOf(FILE_C), 600_000)
        assertEquals(
            """{"ok":true,"added":true,"duplicate":true,"name":"Show S01 (720p)","checked":true,"selectedBytes":${SIZE - SIZE_C},"unselectedCount":1,"selection":"pending"}""",
            outcome.toJson())
        assertEquals(1, registrarCalls)
    }

    @Test
    fun addSelected_registrarThrows_stillReportsAdded() {
        val throwing = SelectionRegistrar { _, _, _, _ -> throw IllegalStateException("boom") }
        val outcome = handler(registrar = throwing).addSelected(HASH, listOf(FILE_C), 600_000)
        assertEquals(200, outcome.httpStatus)
        assertTrue(outcome is SelectedAddOutcome.Added)
    }

    @Test
    fun addSelected_unknownPath_returns400WithTheFirstUnknownPath() {
        val outcome = handler(registrar = recordingRegistrar)
            .addSelected(HASH, listOf("nope.mkv", FILE_C), 600_000)
        assertEquals(400, outcome.httpStatus)
        assertEquals(
            """{"ok":false,"error":"bad_selection","detail":"unknown_path:nope.mkv"}""",
            outcome.toJson())
        assertNull(lastMagnet)
        assertEquals(0, registrarCalls)
    }

    @Test
    fun addSelected_emptyOrBlankList_returns400BadSelectionEmpty() {
        for (paths in listOf(emptyList<String>(), listOf("", "  "))) {
            val outcome = handler().addSelected(HASH, paths, 600_000)
            assertEquals(400, outcome.httpStatus)
            assertEquals(
                """{"ok":false,"error":"bad_selection","detail":"empty"}""", outcome.toJson())
        }
    }

    @Test
    fun addSelected_wholeTorrentDeselected_returns400BadSelectionNoFilesSelected() {
        val outcome = handler().addSelected(HASH, listOf(FILE_A, FILE_B, FILE_C), 600_000)
        assertEquals(400, outcome.httpStatus)
        assertEquals(
            """{"ok":false,"error":"bad_selection","detail":"no_files_selected"}""",
            outcome.toJson())
        assertNull(lastMagnet)
    }

    @Test
    fun addSelected_moreThan200kPaths_returns400BadSelectionTooMany() {
        val outcome = handler().addSelected(HASH, (0..200_000).map { "f$it" }, 600_000)
        assertEquals(400, outcome.httpStatus)
        assertEquals(
            """{"ok":false,"error":"bad_selection","detail":"too_many"}""", outcome.toJson())
    }

    @Test
    fun addSelected_spaceGateUsesTheSelectedBytesSoASubsetFitsWhereAddRefuses() {
        val free = SIZE - 1 // the whole 1.5 GB torrent does not fit, the 1.4 GB subset does
        assertEquals(507, handler(freeBytes = free).add(HASH).httpStatus)

        val outcome = handler(freeBytes = free, registrar = recordingRegistrar)
            .addSelected(HASH, listOf(FILE_C), 600_000)
        assertEquals(200, outcome.httpStatus)
        assertEquals(SIZE - SIZE_C, (outcome as SelectedAddOutcome.Added).selectedBytes)
        assertEquals(1, registrarCalls)
    }

    @Test
    fun addSelected_selectedBytesOverFreeSpace_returns507WithTheSelectedBytes() {
        val free = 1_000_000_000L
        val outcome = handler(freeBytes = free).addSelected(HASH, listOf(FILE_C), 600_000)
        assertEquals(507, outcome.httpStatus)
        assertEquals(
            """{"ok":false,"error":"insufficient_space","sizeBytes":${SIZE - SIZE_C},"freeBytes":$free}""",
            outcome.toJson())
        assertNull(lastMagnet)
    }

    @Test
    fun addSelected_unknownFreeSpace_skipsTheGateAndReportsCheckedFalse() {
        val outcome = handler(freeBytes = Long.MAX_VALUE)
            .addSelected(HASH, listOf(FILE_C), 600_000)
        assertEquals(
            """{"ok":true,"added":true,"duplicate":false,"name":"Show S01 (720p)","checked":false,"selectedBytes":${SIZE - SIZE_C},"unselectedCount":1,"selection":"pending"}""",
            outcome.toJson())
    }

    @Test
    fun addSelected_errorOutcomesReuseTheExistingShapes() {
        val bad = handler().addSelected("f".repeat(39), listOf(FILE_C), 600_000)
        assertEquals(400, bad.httpStatus)
        assertEquals("""{"ok":false,"error":"bad_hash"}""", bad.toJson())

        for (db in listOf<File?>(null, File(context.cacheDir, "no-such-db.db"))) {
            val o = handler(db = db).addSelected(HASH, listOf(FILE_C), 600_000)
            assertEquals(503, o.httpStatus)
            assertEquals("""{"ok":false,"error":"db_unavailable"}""", o.toJson())
        }

        val absent = handler().addSelected("f".repeat(40), listOf(FILE_C), 600_000)
        assertEquals(404, absent.httpStatus)
        assertEquals("""{"ok":false,"error":"not_found"}""", absent.toJson())

        val noStorage = handler(gatewayValue = null).addSelected(HASH, listOf(FILE_C), 600_000)
        assertEquals(503, noStorage.httpStatus)
        assertEquals("""{"ok":false,"error":"no_storage"}""", noStorage.toJson())

        rpcResult = TorrentAddResult(false, false, null)
        val rpcFailed = handler(registrar = recordingRegistrar)
            .addSelected(HASH, listOf(FILE_C), 600_000)
        assertEquals(502, rpcFailed.httpStatus)
        assertEquals("""{"ok":false,"error":"rpc_failed"}""", rpcFailed.toJson())
        assertEquals(0, registrarCalls)
    }

    // ---- addSelected: the `selected` inclusion field (mode NONE) ----

    @Test
    fun addSelected_selectedFieldIsComplementedAgainstAllFiles() {
        // The body names the CHECKED paths; the handler derives the deselection as
        // their complement in the DB's file list, so a "select none + tick 3" add
        // costs 3 paths instead of 27 595.
        val outcome = handler(registrar = recordingRegistrar)
            .addSelected(HASH, emptyList(), 600_000, selectedPaths = listOf(FILE_A, FILE_C))
        assertEquals(200, outcome.httpStatus)
        val added = outcome as SelectedAddOutcome.Added
        assertEquals(SIZE - SIZE_B, added.selectedBytes) // FILE_A + FILE_C
        assertEquals(1, added.unselectedCount)
        assertEquals(1, registrarCalls)
        assertEquals(setOf(FILE_B), registeredPaths)
        assertEquals("magnet:?xt=urn:btih:$HASH&dn=Show%20S01%20%28720p%29", lastMagnet)
    }

    @Test
    fun addSelected_selectedFieldUnknownPathIsRejected() {
        val outcome = handler(registrar = recordingRegistrar)
            .addSelected(HASH, emptyList(), 600_000, selectedPaths = listOf("nope.mkv", FILE_C))
        assertEquals(400, outcome.httpStatus)
        assertEquals(
            """{"ok":false,"error":"bad_selection","detail":"unknown_path:nope.mkv"}""",
            outcome.toJson())
        assertNull(lastMagnet)
        assertEquals(0, registrarCalls)
    }

    @Test
    fun addSelected_selectedFieldEmptyIsRejected() {
        for (paths in listOf(emptyList<String>(), listOf("", "  "))) {
            val outcome = handler().addSelected(HASH, emptyList(), 600_000, selectedPaths = paths)
            assertEquals(400, outcome.httpStatus)
            assertEquals(
                """{"ok":false,"error":"bad_selection","detail":"empty"}""",
                outcome.toJson())
        }
    }

    @Test
    fun addSelected_selectedFieldCoveringEveryFileSkipsTheWatch() {
        // Selecting every file is a valid selection, not an error: the complement
        // is empty, so the magnet goes out and nothing is registered for the watch
        // (the page's `selection-status` poll then reports `state:"none"`).
        val outcome = handler(registrar = recordingRegistrar).addSelected(
            HASH, emptyList(), 600_000, selectedPaths = listOf(FILE_A, FILE_B, FILE_C))
        assertEquals(200, outcome.httpStatus)
        val added = outcome as SelectedAddOutcome.Added
        assertEquals(0, added.unselectedCount)
        assertEquals(SIZE, added.selectedBytes)
        assertEquals(0, registrarCalls)
        assertEquals("magnet:?xt=urn:btih:$HASH&dn=Show%20S01%20%28720p%29", lastMagnet)
    }

    @Test
    fun addSelected_selectedFieldIsCapped() {
        val outcome = handler().addSelected(
            HASH, emptyList(), 600_000, selectedPaths = (0..200_000).map { "f$it" })
        assertEquals(400, outcome.httpStatus)
        assertEquals(
            """{"ok":false,"error":"bad_selection","detail":"too_many"}""", outcome.toJson())
    }

    private fun bytes40(hex: String): ByteArray = ByteArray(20) { i ->
        hex.substring(2 * i, 2 * i + 2).toInt(16).toByte()
    }
}
