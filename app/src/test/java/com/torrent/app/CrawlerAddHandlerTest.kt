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
    }

    private lateinit var context: Context
    private lateinit var store: ConfigStore
    private lateinit var dbFile: File

    @Volatile private var rpcResult = TorrentAddResult(true, false, null)
    @Volatile private var lastMagnet: String? = null
    @Volatile private var lastDownloadDir: String? = null

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
        }
    }

    private fun handler(
        gatewayValue: SafStorageGateway? = Mockito.mock(SafStorageGateway::class.java),
        db: File? = dbFile,
        freeBytes: Long = 10L * 1024 * 1024 * 1024,
    ): CrawlerAddHandler {
        gatewayValue?.let { Mockito.`when`(it.availableBytes()).thenReturn(freeBytes) }
        return CrawlerAddHandler(
            store = store,
            gateway = { gatewayValue },
            crawlerDbFile = { db },
            rpcFactory = { _, _ -> fakeRpc },
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

    private fun bytes40(hex: String): ByteArray = ByteArray(20) { i ->
        hex.substring(2 * i, 2 * i + 2).toInt(16).toByte()
    }
}
