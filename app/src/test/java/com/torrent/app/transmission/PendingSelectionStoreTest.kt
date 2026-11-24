package com.torrent.app.transmission

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric tests for [SharedPrefsPendingSelectionStore]: the store touches
 * [android.content.SharedPreferences] and `org.json`, so it needs a real (Robolectric)
 * prefs implementation. Nothing here sleeps or talks to transmission.
 */
@RunWith(RobolectricTestRunner::class)
class PendingSelectionStoreTest {

    private fun prefs() =
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("pending_selections", Context.MODE_PRIVATE)

    private fun store() = SharedPrefsPendingSelectionStore(prefs())

    private fun entry(
        hash: String = "307e4f912af34aa46e1b76ec9a2a23b94345e2fe",
        name: String = "VideoHive - Slideshow 40426237",
        paths: List<String> = listOf("Help File/Help_Guide.txt", "Preview/Preview.mp4"),
        addedAt: Long = 1_700_000_000_000L,
        timeoutMs: Long = 600_000L,
    ) = PendingSelection(hash, name, paths, addedAt, timeoutMs)

    @Test fun all_isEmptyWhenNothingWasStored() {
        assertEquals(emptyList<PendingSelection>(), store().all())
    }

    @Test fun put_thenAll_roundTripsEveryField() {
        val e = entry()
        store().put(e)
        assertEquals(listOf(e), store().all())
    }

    @Test fun put_isReadableByAFreshStoreInstance() {
        store().put(entry())
        assertEquals(listOf(entry()), SharedPrefsPendingSelectionStore(prefs()).all())
    }

    @Test fun put_replacesTheEntryForTheSameHash() {
        val first = entry(paths = listOf("a.txt"), timeoutMs = 60_000L)
        val second = entry(paths = listOf("b.txt", "c.txt"), timeoutMs = 120_000L)
        store().put(first)
        store().put(second)
        assertEquals(listOf(second), store().all())
    }

    @Test fun put_keepsOtherHashes() {
        val a = entry(hash = "aa", name = "A")
        val b = entry(hash = "bb", name = "B")
        store().put(a)
        store().put(b)
        assertEquals(listOf(a, b), store().all())
    }

    @Test fun remove_dropsOnlyThatHash() {
        val a = entry(hash = "aa", name = "A")
        val b = entry(hash = "bb", name = "B")
        store().put(a)
        store().put(b)
        store().remove("aa")
        assertEquals(listOf(b), store().all())
    }

    @Test fun remove_unknownHashIsANoOp() {
        val a = entry(hash = "aa", name = "A")
        store().put(a)
        store().remove("cc")
        assertEquals(listOf(a), store().all())
    }

    @Test fun store_writesOneJsonArrayUnderThePinnedKey() {
        store().put(entry(hash = "aa", name = "A", paths = listOf("x/y.txt")))
        val raw = prefs().getString(SharedPrefsPendingSelectionStore.KEY, null)
        val array = JSONArray(raw!!)
        assertEquals(1, array.length())
        val obj = array.getJSONObject(0)
        assertEquals("aa", obj.getString("hash"))
        assertEquals("A", obj.getString("name"))
        assertEquals("x/y.txt", obj.getJSONArray("unselected").getString(0))
        assertEquals(1_700_000_000_000L, obj.getLong("addedAt"))
        assertEquals(600_000L, obj.getLong("timeoutMs"))
    }

    @Test fun all_readsAValueWrittenDirectlyIntoThePrefs() {
        prefs().edit()
            .putString(
                SharedPrefsPendingSelectionStore.KEY,
                """[{"hash":"bb","name":"B","unselected":["p/q.mkv"],""" +
                    """"addedAt":12,"timeoutMs":34}]""",
            )
            .commit()
        assertEquals(
            listOf(PendingSelection("bb", "B", listOf("p/q.mkv"), 12L, 34L)),
            store().all(),
        )
    }

    @Test fun all_isEmptyForAMalformedValue() {
        prefs().edit().putString(SharedPrefsPendingSelectionStore.KEY, "{not an array").commit()
        assertEquals(emptyList<PendingSelection>(), store().all())
    }

    @Test fun decode_skipsEntriesWithoutAHash() {
        val raw = """[{"name":"no hash","unselected":[],"addedAt":1,"timeoutMs":2},""" +
            """{"hash":"cc","name":"C","unselected":[],"addedAt":3,"timeoutMs":4}]"""
        assertEquals(
            listOf(PendingSelection("cc", "C", emptyList(), 3L, 4L)),
            SharedPrefsPendingSelectionStore.decode(raw),
        )
    }

    @Test fun encodeDecode_roundTripsAnEmptyArray() {
        assertEquals(emptyList<PendingSelection>(), SharedPrefsPendingSelectionStore.decode("[]"))
        assertEquals("[]", SharedPrefsPendingSelectionStore.encode(emptyList()))
    }

    @Test fun encodeDecode_roundTripsPathsWithSpecialCharacters() {
        val e = entry(paths = listOf("""Dir "quoted"/fïle:name.txt""", "a/b c.mkv"))
        store().put(e)
        val decoded = store().all()
        assertEquals(listOf(e), decoded)
        assertTrue(decoded.single().unselectedPaths[0].contains("\"quoted\""))
    }
}
