package com.marketplace.android.core.session

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import okio.Buffer
import java.util.Base64

/**
 * The principal identity ("sub" claim) of a session token — the owner tag
 * of every session-recovery snapshot (row 30 of the agent findings
 * 2026-10-25, CWE-488).
 *
 * **Why the access token is the identity source:** the app's two
 * authentication flows do not share a richer identity carrier — the
 * OAuth/PKCE flow may return an ID token, but the native passkey grant
 * "issues an API access token only (no ID or refresh token)"
 * (MainActivity). The backend's authorization server issues self-contained
 * JWT access tokens (a JwkSource bean exists, and the token customizer
 * stamps the principal into every access token), so `sub` is present on
 * BOTH flows and is the single stable principal handle the client can
 * read without another network round trip.
 *
 * **Why the token is parsed without signature verification:** this is not
 * an access decision. The token object itself is the freshly delivered
 * credential from the token endpoint (over TLS, by this client); the
 * repository stores and sends it on that same trust. Here we only read
 * back which principal that credential names, to decide whether a
 * previous session's UI snapshot belongs to the same account. A mismatch
 * discards the snapshot — fail-closed, never fail-open.
 *
 * Pure JVM (java.util.Base64 + Moshi's JsonReader over an in-memory
 * buffer): no android.jar stubs, so JVM unit tests exercise the exact
 * production path. Any malformed input returns null — the caller treats
 * an unreadable principal as "cannot confirm" and restores nothing.
 */
object AccessTokenSubject {

    /**
     * Reads the `sub` claim of a JWT-shaped token, or null when the token
     * is absent, not a three-part JWT, not decodable, or carries no
     * string `sub` claim. Null always means "principal unknown", never
     * "anonymous".
     */
    fun subjectOf(token: String?): String? {
        if (token.isNullOrBlank()) {
            return null
        }
        val segments = token.split(".")
        if (segments.size != 3) {
            return null
        }
        val payloadJson = try {
            String(Base64.getUrlDecoder().decode(segments[1]), Charsets.UTF_8)
        }
        catch (_: IllegalArgumentException) {
            return null
        }
        return readSubjectClaim(payloadJson)
    }

    /**
     * A targeted JSON scan for the `sub` claim — everything else is
     * skipped, so exotic claims in the payload cannot break the read.
     */
    private fun readSubjectClaim(payloadJson: String): String? =
        try {
            JsonReader.of(Buffer().writeUtf8(payloadJson)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == "sub") {
                        return reader.nextString()
                    }
                    reader.skipValue()
                }
                null
            }
        }
        catch (_: JsonDataException) {
            null
        }
        catch (_: java.io.IOException) {
            // The buffer is in-memory; this is unreachable in practice but
            // JsonReader's contract is checked — an unreadable payload is
            // an unknown principal either way.
            null
        }
}
