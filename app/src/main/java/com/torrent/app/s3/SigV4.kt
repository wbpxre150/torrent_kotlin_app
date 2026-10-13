package com.torrent.app.s3

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4 signer (pure Kotlin, no I/O), tuned for S3-compatible
 * object stores (Cloudflare R2: path-style addressing, region "auto", service "s3").
 *
 * Reference: the AWS "Signature Version 4" documentation
 * (https://docs.aws.amazon.com/IAM/latest/UserGuide/create-signed-request.html) and the
 * official aws-sig-v4-test-suite vectors (see [com.torrent.app.s3.SigV4Test]).
 *
 * All functions are pure so the signer is unit-testable against fixed published vectors.
 */
object SigV4 {

    const val ALGORITHM = "AWS4-HMAC-SHA256"

    /** SHA-256 of the empty payload (`x-amz-content-sha256` value for a zero-byte body). */
    const val EMPTY_PAYLOAD_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    /**
     * Headers to set on the request plus the intermediate signing values
     * ([stringToSign], [signedHeaders], [signature] are exposed for tests/debugging).
     */
    data class SignedRequest(
        /**
         * Headers to set on the HTTP request: `host`, `x-amz-date`, `x-amz-content-sha256`
         * (when included), `Authorization`, plus any signed extra headers.
         */
        val headers: Map<String, String>,
        val stringToSign: String,
        val signedHeaders: String,
        val signature: String,
    )

    /**
     * Signs a single request and returns the headers to set on it.
     *
     * @param timestamp ISO-8601 basic UTC time `yyyyMMdd'T'HHmmss'Z'`; sent as `x-amz-date`
     *   and used for the credential scope date.
     * @param payload Request body. Its SHA-256 is the hashed payload line of the canonical
     *   request and (when [includePayloadHeader] is true) the `x-amz-content-sha256` header.
     * @param extraHeaders Additional headers to include in signing; names are lowercased.
     * @param includePayloadHeader When false, `x-amz-content-sha256` is not a signed header —
     *   needed to reproduce the published test-suite vectors, which carry no such header.
     */
    fun sign(
        accessKeyId: String,
        secretAccessKey: String,
        method: String,
        host: String,
        path: String,
        payload: ByteArray,
        timestamp: String,
        region: String = "auto",
        service: String = "s3",
        query: Map<String, String> = emptyMap(),
        extraHeaders: Map<String, String> = emptyMap(),
        includePayloadHeader: Boolean = true,
    ): SignedRequest {
        require(path.startsWith("/")) { "path must be absolute: $path" }
        val payloadHash = sha256Hex(payload)
        val toSign = HashMap<String, String>()
        for ((name, value) in extraHeaders) toSign[name.lowercase()] = value.trim()
        toSign["host"] = host.trim()
        toSign["x-amz-date"] = timestamp
        if (includePayloadHeader) toSign["x-amz-content-sha256"] = payloadHash

        val (canonicalHeaders, signedHeaderNames) = canonicalHeaders(toSign)
        val canonicalRequest = buildString {
            append(method.uppercase()).append('\n')
            append(canonicalUri(path)).append('\n')
            append(canonicalQueryString(query)).append('\n')
            append(canonicalHeaders).append('\n')
            append(signedHeaderNames).append('\n')
            append(payloadHash)
        }
        val scope = listOf(timestamp.take(8), region, service, "aws4_request").joinToString("/")
        val stringToSign = buildString {
            append(ALGORITHM).append('\n')
            append(timestamp).append('\n')
            append(scope).append('\n')
            append(sha256Hex(canonicalRequest.toByteArray(StandardCharsets.UTF_8)))
        }
        val signature = hex(
            hmacSha256(
                deriveSigningKey(secretAccessKey, timestamp.take(8), region, service),
                stringToSign.toByteArray(StandardCharsets.UTF_8),
            ),
        )
        val authorization =
            "$ALGORITHM Credential=$accessKeyId/$scope, SignedHeaders=$signedHeaderNames, Signature=$signature"
        val headers = HashMap(toSign)
        headers["Authorization"] = authorization
        return SignedRequest(headers, stringToSign, signedHeaderNames, signature)
    }

    /**
     * Builds the (canonical header block, signed-headers list) from lowercased header names.
     * Names are sorted; each header is one `name:value` line terminated by a newline.
     */
    fun canonicalHeaders(headers: Map<String, String>): Pair<String, String> {
        val sorted = headers.toSortedMap()
        val block = sorted.entries.joinToString("\n") { (name, value) -> "$name:$value" } + "\n"
        return block to sorted.keys.joinToString(";")
    }

    /**
     * Percent-encodes one S3 path segment: RFC 3986 unreserved characters pass through, and
     * pre-existing valid `%XX` sequences are kept as-is (S3 does not re-encode them, so a key
     * containing a literal "%20" stays "%20" rather than "%2520").
     */
    fun encodeS3PathSegment(segment: String): String {
        val bytes = segment.toByteArray(StandardCharsets.UTF_8)
        val out = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val v = bytes[i].toInt() and 0xFF
            when {
                v == 0x25 && i + 2 < bytes.size &&
                    isHexDigit(bytes[i + 1]) && isHexDigit(bytes[i + 2]) -> {
                    out.append(segment, i, i + 3)
                    i += 3
                }
                v < 0x80 && v.toChar() in UNRESERVED -> {
                    out.append(bytes[i].toChar())
                    i += 1
                }
                else -> {
                    out.append('%').append(HEX_UPPER[v shr 4]).append(HEX_UPPER[v and 0xF])
                    i += 1
                }
            }
        }
        return out.toString()
    }

    /** Percent-encodes a query key or value per SigV4 (strict RFC 3986; `%` is always encoded). */
    fun encodeQueryComponent(component: String): String {
        val out = StringBuilder()
        for (b in component.toByteArray(StandardCharsets.UTF_8)) {
            val v = b.toInt() and 0xFF
            if (v < 0x80 && v.toChar() in UNRESERVED) out.append(b.toChar())
            else out.append('%').append(HEX_UPPER[v shr 4]).append(HEX_UPPER[v and 0xF])
        }
        return out.toString()
    }

    /** The canonical (absolute) URI: every path segment S3-encoded, `/` separators preserved. */
    fun canonicalUri(path: String): String {
        val out = StringBuilder()
        for ((i, segment) in path.split('/').withIndex()) {
            if (i > 0) out.append('/')
            out.append(encodeS3PathSegment(segment))
        }
        return out.toString()
    }

    /**
     * The canonical query string: keys and values percent-encoded, sorted by encoded key then
     * encoded value (ASCII/case-sensitive, per the SigV4 spec), `key=value` pairs joined by `&`.
     * An empty map produces an empty string. Single value per key (S3 PUTs use an empty query).
     */
    fun canonicalQueryString(query: Map<String, String>): String {
        if (query.isEmpty()) return ""
        return query.entries
            .sortedWith(compareBy({ encodeQueryComponent(it.key) }, { encodeQueryComponent(it.value) }))
            .joinToString("&") { (key, value) -> "${encodeQueryComponent(key)}=${encodeQueryComponent(value)}" }
    }

    /** SHA-256 of [data] as a lowercase hex string. */
    fun sha256Hex(data: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(data))

    /** One HMAC-SHA256 block. */
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /**
     * Derives the SigV4 signing key:
     * `HMAC(HMAC(HMAC(HMAC("AWS4" + secret, date), region), service), "aws4_request")`.
     */
    fun deriveSigningKey(secretAccessKey: String, date: String, region: String, service: String): ByteArray {
        val kDate = hmacSha256(
            ("AWS4" + secretAccessKey).toByteArray(StandardCharsets.UTF_8),
            date.toByteArray(StandardCharsets.UTF_8),
        )
        val kRegion = hmacSha256(kDate, region.toByteArray(StandardCharsets.UTF_8))
        val kService = hmacSha256(kRegion, service.toByteArray(StandardCharsets.UTF_8))
        return hmacSha256(kService, "aws4_request".toByteArray(StandardCharsets.UTF_8))
    }

    private val UNRESERVED: Set<Char> =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~".toSet()

    /** Uppercase hex for percent-encoding (S3/SigV4 canonical form, e.g. `%3F`). */
    private val HEX_UPPER = "0123456789ABCDEF".toCharArray()

    /**
     * Lowercase hex for digests and signatures: they are conventionally lowercase, and the
     * canonical request embeds the payload hash, so case matters for the digest chain.
     */
    private val HEX_LOWER = "0123456789abcdef".toCharArray()

    private fun isHexDigit(b: Byte): Boolean {
        val c = b.toInt() and 0xFF
        return c in '0'.code..'9'.code || c in 'a'.code..'f'.code || c in 'A'.code..'F'.code
    }

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append(HEX_LOWER[v shr 4])
            append(HEX_LOWER[v and 0xF])
        }
    }
}
