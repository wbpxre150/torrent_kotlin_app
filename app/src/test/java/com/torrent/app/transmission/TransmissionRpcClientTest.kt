package com.torrent.app.transmission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TransmissionRpcClientTest {
    @Test fun buildRequestBody_containsMethodAndArguments() {
        val args = JSONObject().put("speed-limit-down", 100)
        val body = TransmissionRpcClient.buildRequestBody("session-set", args)
        assertTrue(body.contains("\"method\":\"session-set\""))
        assertTrue(body.contains("\"speed-limit-down\":100"))
    }
    @Test fun buildRequestBody_omitsArgumentsWhenNull() {
        val body = TransmissionRpcClient.buildRequestBody("session-get", null)
        assertTrue(body.contains("\"method\":\"session-get\""))
        assertTrue(!body.contains("arguments"))
    }

    @Test fun buildTorrentAddArgs_hasMagnetFilenameDataDirAndNormalPriority() {
        val args = TransmissionRpcClient.buildTorrentAddArgs(
            "magnet:?xt=urn:btih:abc", "/saf/usb/torrent_data")
        val o = JSONObject(TransmissionRpcClient.buildRequestBody("torrent-add", args))
        assertEquals("torrent-add", o.getString("method"))
        val a = o.getJSONObject("arguments")
        assertEquals("magnet:?xt=urn:btih:abc", a.getString("filename"))
        assertEquals("/saf/usb/torrent_data", a.getString("download-dir"))
        assertEquals(0, a.getInt("bandwidthPriority"))
    }

    @Test fun buildSessionSetSpeedArgs_includesDerivedEnabledFlags() {
        val args = TransmissionRpcClient.buildSessionSetSpeedArgs(666, 555, 0, 0)
        assertEquals(666, args.getInt("speed-limit-down"))
        assertEquals(555, args.getInt("speed-limit-up"))
        assertTrue(args.getBoolean("speed-limit-down-enabled"))
        assertTrue(args.getBoolean("speed-limit-up-enabled"))
    }

    @Test fun buildSessionSetSpeedArgs_zeroLimitsDisableFlags() {
        val args = TransmissionRpcClient.buildSessionSetSpeedArgs(0, 0, 0, 0)
        assertFalse(args.getBoolean("speed-limit-down-enabled"))
        assertFalse(args.getBoolean("speed-limit-up-enabled"))
    }

    @Test fun buildSessionSetSpeedArgs_omitsAltSpeedKeysWhenZero() {
        // Turtle limits are absolute KB/s caps, so 0 must never be pushed via session-set.
        val args = TransmissionRpcClient.buildSessionSetSpeedArgs(0, 0, 0, 0)
        assertFalse(args.has("alt-speed-down"))
        assertFalse(args.has("alt-speed-up"))
        assertFalse(args.getBoolean("alt-speed-enabled"))
        assertFalse(args.getBoolean("alt-speed-time-enabled"))
        assertTrue(args.has("speed-limit-down"))
        assertTrue(args.has("speed-limit-up"))
    }

    @Test fun buildSessionSetSpeedArgs_includesAltSpeedKeysWhenNonZero() {
        val args = TransmissionRpcClient.buildSessionSetSpeedArgs(0, 0, 50, 20)
        assertEquals(50, args.getInt("alt-speed-down"))
        assertEquals(20, args.getInt("alt-speed-up"))
        assertFalse(args.getBoolean("alt-speed-enabled"))
        assertFalse(args.getBoolean("alt-speed-time-enabled"))
    }

    @Test fun buildSessionSetSpeedArgs_omitsOnlyZeroAltSpeedKey() {
        val args = TransmissionRpcClient.buildSessionSetSpeedArgs(0, 0, 0, 20)
        assertFalse(args.has("alt-speed-down"))
        assertEquals(20, args.getInt("alt-speed-up"))
    }

    @Test fun buildAltSpeedDisableArgs_forcesBothFlagsFalse() {
        val args = TransmissionRpcClient.buildAltSpeedDisableArgs()
        assertFalse(args.getBoolean("alt-speed-enabled"))
        assertFalse(args.getBoolean("alt-speed-time-enabled"))
    }

    @Test fun buildTorrentGetFilesArgs_hasExactlyFieldsNameFilesAndIds() {
        val a = TransmissionRpcClient.buildTorrentGetFilesArgs("abc123")
        assertEquals(2, a.length())
        assertTrue(a.has("fields"))
        assertTrue(a.has("ids"))
        val fields = a.getJSONArray("fields")
        assertEquals(2, fields.length())
        assertEquals("name", fields.getString(0))
        assertEquals("files", fields.getString(1))
        val ids = a.getJSONArray("ids")
        assertEquals(1, ids.length())
        assertEquals("abc123", ids.getString(0))
    }

    @Test fun buildTorrentSetFilesUnwantedArgs_hasExactlyIdsAndFilesUnwanted() {
        val a = TransmissionRpcClient.buildTorrentSetFilesUnwantedArgs("abc123", listOf(0, 4, 7))
        assertEquals(2, a.length())
        assertTrue(a.has("ids"))
        assertTrue(a.has("files-unwanted"))
        assertEquals("abc123", a.getJSONArray("ids").getString(0))
        val unwanted = a.getJSONArray("files-unwanted")
        assertEquals(3, unwanted.length())
        assertEquals(0, unwanted.getInt(0))
        assertEquals(4, unwanted.getInt(1))
        assertEquals(7, unwanted.getInt(2))
    }

    @Test fun buildTorrentSetFilesUnwantedArgs_emptyListIsSerialisedAsEmptyArray() {
        // The key must be PRESENT with an empty array; omitting it would be a different request.
        val a = TransmissionRpcClient.buildTorrentSetFilesUnwantedArgs("abc123", emptyList())
        assertTrue(a.has("files-unwanted"))
        assertEquals(0, a.getJSONArray("files-unwanted").length())
        assertTrue(a.toString().contains("\"files-unwanted\":[]"))
    }

    @Test fun buildTorrentStopArgs_hasExactlyTheIdsArray() {
        // torrent-stop takes no other argument: the whole request is `{"ids":["abc123"]}`.
        val a = TransmissionRpcClient.buildTorrentStopArgs("abc123")
        assertEquals(1, a.length())
        assertTrue(a.has("ids"))
        assertEquals(1, a.getJSONArray("ids").length())
        assertEquals("abc123", a.getJSONArray("ids").getString(0))
        val o = JSONObject(TransmissionRpcClient.buildRequestBody("torrent-stop", a))
        assertEquals("torrent-stop", o.getString("method"))
        assertEquals("{\"ids\":[\"abc123\"]}", o.getJSONObject("arguments").toString())
    }

    @Test fun buildTorrentGetStatusArgs_hasExactlyFieldsStatusAndIds() {
        val a = TransmissionRpcClient.buildTorrentGetStatusArgs("abc123")
        assertEquals(2, a.length())
        val fields = a.getJSONArray("fields")
        assertEquals(1, fields.length())
        assertEquals("status", fields.getString(0))
        assertEquals(1, a.getJSONArray("ids").length())
        assertEquals("abc123", a.getJSONArray("ids").getString(0))
        assertEquals("{\"fields\":[\"status\"],\"ids\":[\"abc123\"]}", a.toString())
    }

    @Test fun parseTorrentStatus_returnsTheStatusValue() {
        val stopped = TransmissionRpcClient.parseTorrentStatus(
            JSONObject("{\"result\":\"success\",\"arguments\":{\"torrents\":[{\"status\":0}]}}"))
        assertNotNull(stopped)
        assertEquals(0, stopped!!.toInt())
        val downloading = TransmissionRpcClient.parseTorrentStatus(
            JSONObject("{\"result\":\"success\",\"arguments\":{\"torrents\":[{\"status\":4}]}}"))
        assertNotNull(downloading)
        assertEquals(4, downloading!!.toInt())
    }

    @Test fun parseTorrentStatus_emptyTorrentsAndFailedCallAreNull() {
        // An absent torrent is "no answer", never status 0 — 0 is TR_STATUS_STOPPED, a real answer.
        assertNull(TransmissionRpcClient.parseTorrentStatus(
            JSONObject("{\"result\":\"success\",\"arguments\":{\"torrents\":[]}}")))
        assertNull(TransmissionRpcClient.parseTorrentStatus(
            JSONObject("{\"result\":\"success\",\"arguments\":{\"torrents\":[{\"name\":\"x\"}]}}")))
        assertNull(TransmissionRpcClient.parseTorrentStatus(null))
    }

    @Test fun parseTorrentFilesSnapshot_mapsNameAndFiles() {
        val resp = JSONObject(
            "{\"result\":\"success\",\"arguments\":{\"torrents\":[{" +
                "\"name\":\"My Torrent\",\"files\":[" +
                "{\"name\":\"My Torrent/a.mkv\",\"length\":100,\"bytesCompleted\":40}," +
                "{\"name\":\"My Torrent/sub/b.txt\",\"length\":20,\"bytesCompleted\":0}]}]}}")
        val snap = TransmissionRpcClient.parseTorrentFilesSnapshot(resp)
        assertNotNull(snap)
        assertEquals("My Torrent", snap!!.name)
        assertEquals(2, snap.files.size)
        assertEquals("My Torrent/a.mkv", snap.files[0].name)
        assertEquals(100L, snap.files[0].length)
        assertEquals(40L, snap.files[0].bytesCompleted)
        assertEquals("My Torrent/sub/b.txt", snap.files[1].name)
        assertEquals(20L, snap.files[1].length)
        assertEquals(0L, snap.files[1].bytesCompleted)
    }

    @Test fun parseTorrentFilesSnapshot_emptyFilesIsALegalPreMetadataSnapshot() {
        val resp = JSONObject(
            "{\"result\":\"success\",\"arguments\":{\"torrents\":[{\"name\":\"My Torrent\",\"files\":[]}]}}")
        val snap = TransmissionRpcClient.parseTorrentFilesSnapshot(resp)
        assertNotNull(snap)
        assertEquals("My Torrent", snap!!.name)
        assertTrue(snap.files.isEmpty())
    }

    @Test fun parseTorrentFilesSnapshot_emptyTorrentsAndFailedCallAreNull() {
        assertNull(TransmissionRpcClient.parseTorrentFilesSnapshot(
            JSONObject("{\"result\":\"success\",\"arguments\":{\"torrents\":[]}}")))
        assertNull(TransmissionRpcClient.parseTorrentFilesSnapshot(null))
    }
}