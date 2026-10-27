package com.marketplace.android.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/**
 * Row 30 (agent findings 2026-10-25, CWE-488): the principal read that
 * owns every session-recovery snapshot decision. Pure JVM — this IS the
 * production parse path (java.util.Base64 + Moshi over an in-memory
 * buffer; no android.jar stubs anywhere in it).
 */
class AccessTokenSubjectTest {

    private fun jwt(payloadJson: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{\"alg\":\"RS256\"}".toByteArray(Charsets.UTF_8))
        val payload = encoder.encodeToString(payloadJson.toByteArray(Charsets.UTF_8))
        return "$header.$payload.c2lnbmF0dXJl"
    }

    @Test
    fun readsTheSubjectClaimOfARealisticAccessToken() {
        val token = jwt(
            """
            {
              "sub": "0e0b1e6d-6b1e-4f44-b3a3-c4f00d31d220",
              "iss": "https://auth.dayf.marketplace",
              "aud": ["dayf-api"],
              "exp": 1790000000,
              "iat": 1789999000,
              "email": "member@dayf.example",
              "roles": ["CONSUMER"]
            }
            """.trimIndent()
        )

        assertEquals("0e0b1e6d-6b1e-4f44-b3a3-c4f00d31d220", AccessTokenSubject.subjectOf(token))
    }

    @Test
    fun readsTheSubjectWhenItAppearsAfterOtherClaims() {
        val token = jwt("{\"iss\":\"a\",\"aud\":[\"b\"],\"exp\":123,\"sub\":\"the-owner\",\"roles\":[]}")

        assertEquals("the-owner", AccessTokenSubject.subjectOf(token))
    }

    @Test
    fun decodesUrlSafeBase64AlphabetCharacters() {
        // '-' and '_' are the base64url alphabet's replacements for '+' and '/'
        // — payloads containing them (long UUID-heavy JSON does) must decode.
        val token = jwt("{\"sub\":\"owner-with-_urlsafe_\"}")

        assertEquals("owner-with-_urlsafe_", AccessTokenSubject.subjectOf(token))
    }

    @Test
    fun nullTokenMeansUnknownPrincipal() {
        assertNull(AccessTokenSubject.subjectOf(null))
        assertNull(AccessTokenSubject.subjectOf(""))
        assertNull(AccessTokenSubject.subjectOf("   "))
    }

    @Test
    fun anOpaqueTokenIsNotAJwtAndYieldsNoPrincipal() {
        assertNull(AccessTokenSubject.subjectOf("opaque-reference-token"))
        assertNull(AccessTokenSubject.subjectOf("a.b"))
        assertNull(AccessTokenSubject.subjectOf("a.b.c.d"))
    }

    @Test
    fun anUndecodablePayloadYieldsNoPrincipal() {
        // Valid shape, garbage middle segment (not base64url).
        assertNull(AccessTokenSubject.subjectOf("header.!!!not-base64!!!.sig"))
    }

    @Test
    fun aPayloadWithoutASubjectYieldsNoPrincipal() {
        assertNull(AccessTokenSubject.subjectOf(jwt("{\"iss\":\"a\",\"exp\":123}")))
    }

    @Test
    fun aNonObjectPayloadYieldsNoPrincipal() {
        assertNull(AccessTokenSubject.subjectOf(jwt("[1,2,3]")))
        assertNull(AccessTokenSubject.subjectOf(jwt("\"just a string\"")))
    }

    @Test
    fun aMalformedJsonPayloadYieldsNoPrincipal() {
        assertNull(AccessTokenSubject.subjectOf(jwt("{\"sub\": \"unterminated")))
    }
}
