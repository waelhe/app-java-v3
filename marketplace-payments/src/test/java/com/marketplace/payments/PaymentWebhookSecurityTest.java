package com.marketplace.payments;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * D-009 repayment guards: every dispatch-relevant field must be inside the
 * MAC, the retired format must be rejected, and the official replay window
 * (past-only, 300s, edge-inclusive) must hold. The signature scheme itself
 * is the official Stripe scheme measured from the artifact's bytecode — see
 * {@link PaymentWebhookSecurity} javadoc.
 */
class PaymentWebhookSecurityTest {

    private static final String SECRET = "test-sig-secret";
    private static final long NOW = 1_800_000_000L; // a fixed epoch second
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);
    private static final String PROVIDER = "stripe";
    private static final String EVENT_ID = "evt_123";
    private static final String EVENT_TYPE = "payment_intent.succeeded";
    private static final UUID INTENT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String EXTERNAL_ID = "pi_external_1";

    private final PaymentWebhookSecurity security = new PaymentWebhookSecurity(SECRET, CLOCK);

    private String sign(long timestamp, String provider, String eventId, String eventType,
                        UUID intentId, String externalId) {
        return security.computeSignatureHeader(provider, eventId, eventType, intentId, externalId, timestamp);
    }

    @Test
    void validateSignature_whenSecretIsBlank_throwsAccessDenied() {
        PaymentWebhookSecurity blank = new PaymentWebhookSecurity("", CLOCK);
        assertThatThrownBy(() -> blank.validateSignature(PROVIDER, EVENT_ID, EVENT_TYPE, null, null, "t=1,v1=x"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void validateSignature_whenSignatureMatches_passes() {
        String header = sign(NOW, PROVIDER, EVENT_ID, EVENT_TYPE, INTENT_ID, EXTERNAL_ID);
        assertDoesNotThrow(() ->
                security.validateSignature(PROVIDER, EVENT_ID, EVENT_TYPE, INTENT_ID, EXTERNAL_ID, header));
    }

    @Test
    void validateSignature_whenSignatureIsNull_throwsAccessDenied() {
        assertThatThrownBy(() ->
                security.validateSignature(PROVIDER, EVENT_ID, EVENT_TYPE, null, null, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void validateSignature_whenSignatureIsWrong_throwsAccessDenied() {
        assertThatThrownBy(() ->
                security.validateSignature(PROVIDER, EVENT_ID, EVENT_TYPE, null, null, "t=" + NOW + ",v1=wrong"))
                .isInstanceOf(AccessDeniedException.class);
    }

    // --- the D-009 core: every field is tamper-protected -------------------

    @Test
    void validateSignature_whenProviderIsTampered_throwsAccessDenied() {
        String header = sign(NOW, PROVIDER, EVENT_ID, EVENT_TYPE, INTENT_ID, EXTERNAL_ID);
        // the dedup key's left half — the cross-provider replay D-009 measured
        assertThatThrownBy(() ->
                security.validateSignature("adyen", EVENT_ID, EVENT_TYPE, INTENT_ID, EXTERNAL_ID, header))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void validateSignature_whenEventIdIsTampered_throwsAccessDenied() {
        String header = sign(NOW, PROVIDER, EVENT_ID, EVENT_TYPE, INTENT_ID, EXTERNAL_ID);
        assertThatThrownBy(() ->
                security.validateSignature(PROVIDER, "evt_999", EVENT_TYPE, INTENT_ID, EXTERNAL_ID, header))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void validateSignature_whenEventTypeIsTampered_throwsAccessDenied() {
        String header = sign(NOW, PROVIDER, EVENT_ID, EVENT_TYPE, INTENT_ID, EXTERNAL_ID);
        assertThatThrownBy(() ->
                security.validateSignature(PROVIDER, EVENT_ID, "payment_intent.captured", INTENT_ID, EXTERNAL_ID, header))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void validateSignature_whenPaymentIntentIdIsTampered_throwsAccessDenied() {
        String header = sign(NOW, PROVIDER, EVENT_ID, EVENT_TYPE, INTENT_ID, EXTERNAL_ID);
        // the confirmIntent re-pointing D-009 measured
        UUID otherIntent = UUID.fromString("99999999-8888-7777-6666-555555555555");
        assertThatThrownBy(() ->
                security.validateSignature(PROVIDER, EVENT_ID, EVENT_TYPE, otherIntent, EXTERNAL_ID, header))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void validateSignature_whenExternalIdIsTampered_throwsAccessDenied() {
        String header = sign(NOW, PROVIDER, EVENT_ID, EVENT_TYPE, INTENT_ID, EXTERNAL_ID);
        assertThatThrownBy(() ->
                security.validateSignature(PROVIDER, EVENT_ID, EVENT_TYPE, INTENT_ID, "pi_evil_2", header))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void validateSignature_whenIntentIdIsAddedToSignedNull_throwsAccessDenied() {
        // the null-field variant: a signature made for the minimal envelope
        // must not verify once an intent id is injected into the dispatch
        String header = sign(NOW, PROVIDER, EVENT_ID, EVENT_TYPE, null, null);
        assertThatThrownBy(() ->
                security.validateSignature(PROVIDER, EVENT_ID, EVENT_TYPE, INTENT_ID, null, header))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void validateSignature_whenIntentIdIsRemovedFromSignedEnvelope_throwsAccessDenied() {
        String header = sign(NOW, PROVIDER, EVENT_ID, EVENT_TYPE, INTENT_ID, EXTERNAL_ID);
        assertThatThrownBy(() ->
                security.validateSignature(PROVIDER, EVENT_ID, EVENT_TYPE, null, null, header))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void validateSignature_whenExternalIdIsNullSwappedWithEmpty_throwsAccessDenied() {
        // CWE-345 (CodeRabbit j1): field PRESENCE rides inside the MAC — a
        // signature made for an absent externalId must not verify for a
        // present-empty one (null encodes as the lone marker, "" as "0:")
        String header = sign(NOW, PROVIDER, EVENT_ID, EVENT_TYPE, null, null);
        assertThatThrownBy(() ->
                security.validateSignature(PROVIDER, EVENT_ID, EVENT_TYPE, null, "", header))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void validateSignature_whenExternalIdIsEmptySwappedWithNull_throwsAccessDenied() {
        String header = sign(NOW, PROVIDER, EVENT_ID, EVENT_TYPE, null, "");
        assertThatThrownBy(() ->
                security.validateSignature(PROVIDER, EVENT_ID, EVENT_TYPE, null, null, header))
                .isInstanceOf(AccessDeniedException.class);
    }

    // --- the replay window (official semantics: past-only, edge-inclusive) --

    @Test
    void validateSignature_whenTimestampIsExactlyAtWindowEdge_passes() {
        String header = sign(NOW - PaymentWebhookSecurity.TOLERANCE_SECONDS,
                PROVIDER, EVENT_ID, EVENT_TYPE, null, null);
        assertDoesNotThrow(() ->
                security.validateSignature(PROVIDER, EVENT_ID, EVENT_TYPE, null, null, header));
    }

    @Test
    void validateSignature_whenTimestampIsPastBeyondWindow_throwsAccessDenied() {
        String header = sign(NOW - PaymentWebhookSecurity.TOLERANCE_SECONDS - 1,
                PROVIDER, EVENT_ID, EVENT_TYPE, null, null);
        assertThatThrownBy(() ->
                security.validateSignature(PROVIDER, EVENT_ID, EVENT_TYPE, null, null, header))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("tolerance");
    }

    @Test
    void validateSignature_whenTimestampIsInFutureBeyondWindow_passes() {
        // official bytecode offsets 191-204: the check is
        // timestamp < now - tolerance — future timestamps are NOT rejected
        // (a leading provider clock must not break delivery)
        String header = sign(NOW + PaymentWebhookSecurity.TOLERANCE_SECONDS + 60,
                PROVIDER, EVENT_ID, EVENT_TYPE, null, null);
        assertDoesNotThrow(() ->
                security.validateSignature(PROVIDER, EVENT_ID, EVENT_TYPE, null, null, header));
    }

    // --- the retired pre-D-009 format is rejected --------------------------

    @Test
    void validateSignature_whenRetiredLegacyFormatProvided_throwsAccessDenied() {
        // the old contract: a bare Base64 MAC over eventId + eventType, no
        // timestamp, three unsigned fields — retired by D-009
        String retiredMac = rawBase64Mac(EVENT_ID + EVENT_TYPE);
        assertThatThrownBy(() ->
                security.validateSignature(PROVIDER, EVENT_ID, EVENT_TYPE, null, null, retiredMac))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void validateSignature_whenHeaderIsMalformed_throwsAccessDenied() {
        for (String header : new String[]{
                "garbage",
                "v1=abc",                         // no t=
                "t=abc,v1=def",                   // non-numeric timestamp
                "t=" + NOW,                       // no v1=
                "t=" + NOW + ",v1=",              // empty signature value
                "",
        }) {
            assertThatThrownBy(() ->
                    security.validateSignature(PROVIDER, EVENT_ID, EVENT_TYPE, null, null, header))
                    .as("header [%s] must be rejected", header)
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    // --- key-rotation support (official: any v1 entry may match) -----------

    @Test
    void validateSignature_whenRotationEntriesPresent_anyMatchVerifies() {
        String header = "t=" + NOW + ",v1=stale-mac-from-old-key,v1="
                + macOf(sign(NOW, PROVIDER, EVENT_ID, EVENT_TYPE, null, null));
        assertDoesNotThrow(() ->
                security.validateSignature(PROVIDER, EVENT_ID, EVENT_TYPE, null, null, header));
    }

    // --- canonicalization: no boundary ambiguity --------------------------

    @Test
    void canonicalEnvelope_isInjectiveAcrossFieldBoundaries() {
        // eventId/eventType pairs whose BARE concatenation is identical
        // ("abc" both ways) must produce DIFFERENT envelopes
        String left = PaymentWebhookSecurity.canonicalEnvelope("p", "a", "bc", null, null);
        String right = PaymentWebhookSecurity.canonicalEnvelope("p", "ab", "c", null, null);
        assertThat(left).isNotEqualTo(right);
    }

    @Test
    void validateSignature_whenBoundaryShiftedFieldsProvided_throwsAccessDenied() {
        // a signature over (eventId="a", eventType="bc") must not verify for
        // (eventId="ab", eventType="c") — identical under the retired
        // concatenation, distinct under the length-prefixed envelope
        String header = sign(NOW, PROVIDER, "a", "bc", null, null);
        assertThatThrownBy(() ->
                security.validateSignature(PROVIDER, "ab", "c", null, null, header))
                .isInstanceOf(AccessDeniedException.class);
    }

    // --- helpers -------------------------------------------------------------

    /** Extracts the v1 value out of a header this class produced. */
    private String macOf(String header) {
        for (String part : header.split(",")) {
            if (part.startsWith("v1=")) {
                return part.substring(3);
            }
        }
        throw new IllegalStateException("no v1= in " + header);
    }

    /**
     * The retired format's MAC: plain Base64 HMAC over the given string —
     * reproduced here (not via the production code, which no longer offers
     * it) to prove the old wire value is REJECTED by the new verifier.
     */
    private String rawBase64Mac(String payload) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.Base64.getEncoder().encodeToString(
                    mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
