package com.marketplace.payments;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Legacy webhook HMAC verification — debt D-009 repaid.
 *
 * <p><b>The signed envelope (D-009):</b> the MAC now covers EVERY field the
 * dispatch acts on — {@code provider}, {@code eventId}, {@code eventType},
 * {@code paymentIntentId}, {@code externalId} — plus a timestamp. The prior
 * contract signed only {@code eventId + eventType}, leaving the provider
 * (the dedup key's left half), the intent id (the {@code confirmIntent}
 * target on the internal path) and the external id unsigned: a captured
 * valid signature could be replayed against a different provider or a
 * different payment intent with no MAC to stop it.
 *
 * <p><b>The signature scheme mirrors the official Stripe scheme</b> — the
 * vendor implementation this repository already trusts on the sibling
 * channel, measured from the official artifact {@code stripe-java:33.4.1}
 * bytecode ({@code com.stripe.net.Webhook$Signature}):
 * <ul>
 *   <li>header format {@code t=<epoch-seconds>,v1=<mac>} —
 *       {@code generateSignatureHeader} formats {@code t=%d,%s=%s} with
 *       scheme constant {@code v1}; multiple {@code v1} entries are
 *       collected and ANY match verifies (key-rotation support);</li>
 *   <li>signed payload {@code <timestamp>.<payload>} —
 *       {@code String.format("%d.%s", timestamp, payload)};</li>
 *   <li>signatures are compared BEFORE the timestamp check, with a
 *       constant-time compare ({@code StringUtils.secureCompare} there;
 *       {@link MessageDigest#isEqual} here — the JDK-official constant-time
 *       comparison, also the house rule for TOTP, RFC 6238 §5.2);</li>
 *   <li>replay window: reject only when
 *       {@code timestamp < timeNow - tolerance} (bytecode offsets 191-204 —
 *       past-only rejection; a future-dated signature can only be produced
 *       by the secret holder, and a leading provider clock must not break
 *       delivery). {@code DEFAULT_TOLERANCE = 300} seconds — the same value
 *       the sibling Stripe channel verifies with. A signature whose
 *       timestamp is exactly at the window edge is still valid (the check
 *       is strict less-than on the past side).</li>
 * </ul>
 *
 * <p><b>House deviation (deliberate):</b> the MAC is Base64-encoded, not
 * hex — the encoding this class has always used; the scheme structure is
 * the official one.
 *
 * <p><b>Canonical envelope:</b> the five fields are serialized
 * length-prefixed with explicit presence ({@code <len>:<value>} per present
 * field — the empty string is {@code 0:} — and the lone marker {@code -}
 * for an absent field, fixed field order). A bare concatenation is ambiguous —
 * {@code eventId="a", eventType="bc"} and {@code eventId="ab",
 * eventType="c"} produce the identical string — so it is not a sound MAC
 * input; the length prefix with the presence marker makes the
 * serialization injective (the reader recovers each field boundary
 * deterministically: the marker {@code -} is null; otherwise digits up to
 * the first {@code :}, then exactly {@code len} characters), so two
 * different field tuples — including a null-versus-empty swap (CWE-345) —
 * can never collide on one MAC input.
 *
 * <p><b>The retired contract is rejected:</b> a bare Base64 MAC over
 * {@code eventId + eventType} (the pre-D-009 format — no timestamp, three
 * unsigned fields) no longer verifies. The channel is latent by design:
 * {@code marketplace.payments.webhook.shared-secret} is unbound in every
 * managed environment, and with a blank secret every request is rejected
 * (see {@link #warnIfSecretNotConfigured()}) — so no live caller exists to
 * break, and the first deployer to bind the secret binds it against THIS
 * documented contract.
 */
@Component
public class PaymentWebhookSecurity {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** The scheme name inside the signature header (official Stripe constant). */
    static final String SIGNATURE_SCHEME = "v1";

    /** The timestamp key inside the signature header (official Stripe constant). */
    static final String TIMESTAMP_KEY = "t";

    /** Encodes an absent (null) field in the canonical envelope — see {@link #canonicalEnvelope}. */
    static final String NULL_MARKER = "-";

    /**
     * Replay window, seconds — matches the sibling Stripe channel's
     * DEFAULT_TOLERANCE = 300 (verified from the official artifact's
     * sources). Past-only rejection, exactly like the official check.
     */
    static final long TOLERANCE_SECONDS = 300;

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookSecurity.class);

    private final String sharedSecret;
    private final Clock clock;

    public PaymentWebhookSecurity(@Value("${marketplace.payments.webhook.shared-secret:}") String sharedSecret,
                                  Clock clock) {
        this.sharedSecret = sharedSecret;
        this.clock = clock;
    }

    @PostConstruct
    void warnIfSecretNotConfigured() {
        if (sharedSecret == null || sharedSecret.isBlank()) {
            log.warn("marketplace.payments.webhook.shared-secret is not configured — " +
                    "webhook requests will be REJECTED. Set this property in production.");
        }
    }

    /**
     * Verifies the full D-009 envelope: parses the
     * {@code X-Webhook-Signature: t=<epoch-seconds>,v1=<mac>} header, checks
     * that the MAC covers every dispatch field, then enforces the replay
     * window. Throws {@link AccessDeniedException} on every failure mode
     * (secret unbound, malformed header, no v1 entry, MAC mismatch,
     * timestamp outside the window) — the caller never learns more than
     * "rejected".
     */
    public void validateSignature(String provider, String eventId, String eventType,
                                  UUID paymentIntentId, String externalId, String signatureHeader) {
        if (sharedSecret == null || sharedSecret.isBlank()) {
            throw new AccessDeniedException("Webhook shared-secret not configured");
        }
        long timestamp = extractTimestamp(signatureHeader);
        List<String> providedSignatures = extractSignatures(signatureHeader);
        String expected = computeMac(timestamp, provider, eventId, eventType, paymentIntentId, externalId);
        boolean matched = false;
        for (String provided : providedSignatures) {
            if (provided != null && MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    provided.getBytes(StandardCharsets.UTF_8))) {
                matched = true;
                break;
            }
        }
        if (!matched) {
            throw new AccessDeniedException("Invalid webhook signature");
        }
        long now = clock.instant().getEpochSecond();
        if (timestamp < now - TOLERANCE_SECONDS) {
            throw new AccessDeniedException("Webhook timestamp outside the tolerance zone");
        }
    }

    /**
     * Produces the full signature header value for the given envelope and
     * timestamp — the shape the integration tests use to drive the real
     * bean through the real dispatch path.
     */
    public String computeSignatureHeader(String provider, String eventId, String eventType,
                                         UUID paymentIntentId, String externalId, long timestampEpochSeconds) {
        String mac = computeMac(timestampEpochSeconds, provider, eventId, eventType, paymentIntentId, externalId);
        return TIMESTAMP_KEY + "=" + timestampEpochSeconds + "," + SIGNATURE_SCHEME + "=" + mac;
    }

    private String computeMac(long timestamp, String provider, String eventId, String eventType,
                              UUID paymentIntentId, String externalId) {
        String signedPayload = timestamp + "." + canonicalEnvelope(provider, eventId, eventType,
                paymentIntentId, externalId);
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] hmac = mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hmac);
        } catch (Exception e) {
            throw new AccessDeniedException("Failed to compute webhook signature", e);
        }
    }

    /**
     * Injective length-prefixed serialization of the five dispatch fields
     * with EXPLICIT presence (CWE-345, CodeRabbit j1): {@code null} encodes
     * as the lone marker {@code -}, while the empty string encodes as
     * {@code 0:} and any non-empty value as {@code <len>:<value>} — a
     * signature made for an absent field never verifies for a present-empty
     * one (the two were indistinguishable under a plain null-to-empty
     * normalization). The reader recovers each field boundary
     * deterministically: the marker {@code -} is null; otherwise digits up
     * to the first {@code :}, then exactly {@code len} characters — so two
     * different field tuples can never collide on one MAC input. See the
     * class javadoc for why bare concatenation is not a sound MAC input.
     */
    static String canonicalEnvelope(String provider, String eventId, String eventType,
                                    UUID paymentIntentId, String externalId) {
        return field(provider) + field(eventId) + field(eventType)
                + field(paymentIntentId == null ? null : paymentIntentId.toString())
                + field(externalId);
    }

    private static String field(String value) {
        if (value == null) {
            return NULL_MARKER;
        }
        return value.length() + ":" + value;
    }

    /**
     * Extracts {@code t=<epoch-seconds>} from the header — the first entry
     * wins. Missing or non-numeric ⇒ rejection (official error family:
     * "Unable to extract timestamp and signatures from header").
     */
    private static long extractTimestamp(String signatureHeader) {
        if (signatureHeader == null) {
            throw new AccessDeniedException("Unable to extract timestamp and signatures from header");
        }
        for (String part : signatureHeader.split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0 && TIMESTAMP_KEY.equals(part.substring(0, eq))) {
                try {
                    return Long.parseLong(part.substring(eq + 1));
                } catch (NumberFormatException ex) {
                    break;
                }
            }
        }
        throw new AccessDeniedException("Unable to extract timestamp and signatures from header");
    }

    /**
     * Collects every {@code v1=<mac>} entry — at least one required
     * (official error family: "No signatures found with expected scheme").
     */
    private static List<String> extractSignatures(String signatureHeader) {
        List<String> signatures = new ArrayList<>();
        for (String part : signatureHeader.split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0 && SIGNATURE_SCHEME.equals(part.substring(0, eq))) {
                signatures.add(part.substring(eq + 1));
            }
        }
        if (signatures.isEmpty()) {
            throw new AccessDeniedException("No signatures found with expected scheme");
        }
        return signatures;
    }
}
