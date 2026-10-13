package com.torrent.app.backup

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Result of validating a dht_crawler BEP51 bootstrap cache file
 * (`bep51_cache.dat`).
 */
sealed class Bep51CacheCheck {
    /**
     * A valid version-2 cache. [nodeCount] may be 0 — an empty-but-valid
     * file is acceptable and means "nothing to upload".
     */
    data class Valid(val nodeCount: Int, val hasTrailer: Boolean) : Bep51CacheCheck()

    /** The bytes do not form a valid v2 cache; [reason] is human-readable. */
    data class Invalid(val reason: String) : Bep51CacheCheck()
}

/**
 * Pure validator for the crawler's `bep51_cache.dat` (Stage 4; Stage 5 reuses
 * it for the bootstrap-download side).
 *
 * File layout: 12-byte header = 4-byte magic `"BEP1"` + 4-byte big-endian
 * uint32 version + 4-byte big-endian uint32 node_count; then node_count
 * records of 39 bytes each (version 2); then an OPTIONAL 32-byte SHA-256
 * trailer computed over header+records. A file is valid iff the magic
 * matches, the version is 2, and the size is exactly `12 + n*39` (no
 * trailer) or `12 + n*39 + 32` with a matching trailer. Version 1
 * (26-byte records) is legacy and rejected.
 */
object Bep51CacheValidator {

    const val MAGIC = "BEP1"
    const val SUPPORTED_VERSION = 2
    private const val HEADER_SIZE = 12
    private const val RECORD_SIZE_V2 = 39
    private const val TRAILER_SIZE = 32

    fun validate(bytes: ByteArray): Bep51CacheCheck {
        if (bytes.size < HEADER_SIZE) {
            return Bep51CacheCheck.Invalid(
                "file is too small: ${bytes.size} bytes (need a $HEADER_SIZE-byte header)")
        }
        val magic = "BEP1".toByteArray(Charsets.US_ASCII)
        for (i in 0 until magic.size) {
            if (bytes[i] != magic[i]) {
                return Bep51CacheCheck.Invalid("bad magic (expected \"$MAGIC\")")
            }
        }
        val version = intBE(bytes, 4)
        if (version != SUPPORTED_VERSION) {
            return Bep51CacheCheck.Invalid(
                "unsupported version $version (only v$SUPPORTED_VERSION is accepted)")
        }
        val nodeCount = intBE(bytes, 8)
        if (nodeCount < 0) {
            return Bep51CacheCheck.Invalid("bad node_count $nodeCount")
        }
        val bodySize = HEADER_SIZE.toLong() + nodeCount.toLong() * RECORD_SIZE_V2
        return when (bytes.size) {
            bodySize.toInt() -> Bep51CacheCheck.Valid(nodeCount, hasTrailer = false)
            (bodySize + TRAILER_SIZE).toInt() -> {
                val digest = sha256(bytes, 0, bodySize.toInt())
                if (java.util.Arrays.equals(digest, 0, digest.size, bytes, bodySize.toInt(), bytes.size)) {
                    Bep51CacheCheck.Valid(nodeCount, hasTrailer = true)
                } else {
                    Bep51CacheCheck.Invalid("trailer checksum mismatch")
                }
            }
            else -> Bep51CacheCheck.Invalid(
                "bad size: ${bytes.size} bytes (expected $bodySize with no trailer " +
                    "or ${bodySize + TRAILER_SIZE} with trailer)")
        }
    }

    private fun intBE(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun sha256(bytes: ByteArray, from: Int, to: Int): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes, from, to - from)
        return digest.digest()
    }
}

/** Test/inspection helper: builds a synthetic v2 cache file. */
internal fun buildBep51Cache(
    nodeCount: Int,
    version: Int = Bep51CacheValidator.SUPPORTED_VERSION,
    magic: String = Bep51CacheValidator.MAGIC,
    recordSize: Int = 39,
    withTrailer: Boolean = false,
    corruptTrailer: Boolean = false,
): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(magic.toByteArray(Charsets.US_ASCII))
    out.write(intBEArray(version))
    out.write(intBEArray(nodeCount))
    repeat(nodeCount) { out.write(ByteArray(recordSize)) }
    if (withTrailer) {
        val body = out.toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(body)
        if (corruptTrailer) digest[0] = (digest[0].toInt() xor 0xFF).toByte()
        out.write(digest)
    }
    return out.toByteArray()
}

private fun intBEArray(v: Int): ByteArray =
    byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
