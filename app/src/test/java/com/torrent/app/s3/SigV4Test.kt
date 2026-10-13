package com.torrent.app.s3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JUnit4 (no Robolectric) — [SigV4] is pure Kotlin with no Android deps.
 *
 * Vectors are from the official AWS SigV4 test suite (cases `get-vanilla` and
 * `get-unreserved`; access key `AKIDEXAMPLE`, secret
 * `wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY`, region us-east-1, service "service",
 * timestamp 20150830T123600Z) and the AWS documentation's signing-key derivation example.
 */
class SigV4Test {

    private val secret = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
    private val timestamp = "20150830T123600Z"

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun signingKeyMatchesPublishedAwsDocsVector() {
        // AWS docs "Derive a signing key" example: date 20150830, region us-east-1, service iam.
        val key = SigV4.deriveSigningKey(secret, "20150830", "us-east-1", "iam")
        assertEquals("c4afb1cc5771d871763a393e44b703571b55cc28424d1a5e86da6ed3c154a4b9", hex(key))
    }

    @Test
    fun getVanillaMatchesPublishedTestSuiteVector() {
        val signed = SigV4.sign(
            accessKeyId = "AKIDEXAMPLE",
            secretAccessKey = secret,
            method = "GET",
            host = "example.amazonaws.com",
            path = "/",
            payload = ByteArray(0),
            timestamp = timestamp,
            region = "us-east-1",
            service = "service",
            includePayloadHeader = false,
        )
        assertEquals(
            "AWS4-HMAC-SHA256\n" +
                "20150830T123600Z\n" +
                "20150830/us-east-1/service/aws4_request\n" +
                "bb579772317eb040ac9ed261061d46c1f17a8133879d6129b6e1c25292927e63",
            signed.stringToSign,
        )
        assertEquals("5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31", signed.signature)
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, " +
                "SignedHeaders=host;x-amz-date, " +
                "Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31",
            signed.headers["Authorization"],
        )
    }

    @Test
    fun getUnreservedPathMatchesPublishedTestSuiteVector() {
        val signed = SigV4.sign(
            accessKeyId = "AKIDEXAMPLE",
            secretAccessKey = secret,
            method = "GET",
            host = "example.amazonaws.com",
            path = "/-._~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz",
            payload = ByteArray(0),
            timestamp = timestamp,
            region = "us-east-1",
            service = "service",
            includePayloadHeader = false,
        )
        assertEquals(
            "AWS4-HMAC-SHA256\n" +
                "20150830T123600Z\n" +
                "20150830/us-east-1/service/aws4_request\n" +
                "6a968768eefaa713e2a6b16b589a8ea192661f098f37349f4e2c0082757446f9",
            signed.stringToSign,
        )
        assertEquals("07ef7494c76fa4850883e2b006601f940f8a34d404d0cfa977f52a65bbf5f24f", signed.signature)
    }

    @Test
    fun emptyPayloadHashMatchesPublishedSha256() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", SigV4.sha256Hex(ByteArray(0)))
        assertEquals(SigV4.EMPTY_PAYLOAD_SHA256, SigV4.sha256Hex(ByteArray(0)))
    }

    @Test
    fun canonicalUriEncodesSpecialCharsPerS3Rules() {
        assertEquals("/bucket/my%20file.pdf", SigV4.canonicalUri("/bucket/my file.pdf"))
        assertEquals("/b/a%20b%3Fc%23d%3Bx", SigV4.canonicalUri("/b/a b?c#d;x"))
        // Pre-existing %XX is NOT re-encoded (S3 rule).
        assertEquals("/bucket/100%25", SigV4.canonicalUri("/bucket/100%25"))
        // Unreserved characters pass through.
        assertEquals("/-._~0123456789abcdefghijklmnopqrstuvwxyz", SigV4.canonicalUri("/-._~0123456789abcdefghijklmnopqrstuvwxyz"))
        assertEquals("/", SigV4.canonicalUri("/"))
    }

    @Test
    fun canonicalQueryStringSortsAndEncodesComponents() {
        assertEquals("", SigV4.canonicalQueryString(emptyMap()))
        assertEquals("a=1&b=2", SigV4.canonicalQueryString(mapOf("b" to "2", "a" to "1")))
        // ASCII sort is case-sensitive: 'A' (0x41) sorts before 'b' (0x62).
        assertEquals("A=1&b=2", SigV4.canonicalQueryString(mapOf("b" to "2", "A" to "1")))
        assertEquals("a%20b=c%20d", SigV4.canonicalQueryString(mapOf("a b" to "c d")))
        // '%' in values is always encoded.
        assertEquals("k=100%25", SigV4.canonicalQueryString(mapOf("k" to "100%")))
    }

    @Test
    fun extraHeadersAreLowercasedAndSortedInSignedHeaders() {
        val signed = SigV4.sign(
            accessKeyId = "AK",
            secretAccessKey = "SK",
            method = "PUT",
            host = "h",
            path = "/b/k",
            payload = ByteArray(0),
            timestamp = timestamp,
            extraHeaders = mapOf("X-Amz-Meta-Zeta" to "1", "X-Amz-Meta-Alpha" to "2"),
        )
        assertEquals("host;x-amz-content-sha256;x-amz-date;x-amz-meta-alpha;x-amz-meta-zeta", signed.signedHeaders)
        assertEquals("1", signed.headers["x-amz-meta-zeta"])
        assertEquals("2", signed.headers["x-amz-meta-alpha"])
    }

    @Test
    fun defaultSigningIncludesPayloadHeader() {
        val signed = SigV4.sign(
            accessKeyId = "AK",
            secretAccessKey = "SK",
            method = "PUT",
            host = "h",
            path = "/b/k",
            payload = "body".toByteArray(),
            timestamp = timestamp,
        )
        // SHA-256("body"), computed independently of the production code.
        assertEquals("230d8358dc8e8890b4c58deeb62912ee2f20357ae92a5cc861b98e68fe31acb5", signed.headers["x-amz-content-sha256"])
        assertTrue(signed.signedHeaders.contains("x-amz-content-sha256"))
    }

    @Test
    fun authorizationHeaderHasCredentialScopeAndSignatureFormat() {
        val signed = SigV4.sign(
            accessKeyId = "AK",
            secretAccessKey = "SK",
            method = "PUT",
            host = "h",
            path = "/b/k",
            payload = ByteArray(0),
            timestamp = timestamp,
            region = "auto",
            service = "s3",
        )
        val auth = signed.headers.getValue("Authorization")
        assertTrue(auth, auth.startsWith("AWS4-HMAC-SHA256 Credential=AK/20150830/auto/s3/aws4_request, "))
        assertTrue(auth, auth.contains(", SignedHeaders="))
        assertTrue(auth, auth.matches(Regex(".*Signature=[0-9a-f]{64}$")))
    }
}
