package com.marketplace.shared.security;

import com.marketplace.shared.api.ServiceUnavailableException;
import com.marketplace.shared.config.MarketplaceProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * I7 (account-pseudonymization-plan §5-أ / gate b-2(b)): the deterministic
 * subject-replacement transformation for account pseudonymization —
 * {@code "anon-" + hex(HMAC-SHA256(K_env, subject))}.
 *
 * <p><b>The official classification:</b> this is the "cryptographic
 * algorithms" class of replacement procedures that EDPB Guidelines 01/2025
 * §87 names ("Two classes of replacement procedures are commonly applied as
 * pseudonymising transformations: cryptographic algorithms and lookup
 * tables."), and the key is the "additional information" GDPR Art. 4(5)
 * requires to be "kept separately" — delivered through the environment-only
 * channel (secrets-policy §1, {@code PSEUDONYMIZATION_HMAC_KEY}), never in
 * the database, never in Git, never logged.
 *
 * <p><b>The exact contract (pinned by the plan, guarded by
 * {@code SubjectPseudonymizerTest}):</b></p>
 * <ul>
 *   <li>Input — the UTF-8 bytes of the stored subject string, as-is, with no
 *       case normalization or trimming: subjects are opaque identifiers
 *       matched literally ({@code IdentityUserProvider} /
 *       {@code UserRepository.findBySubject}).</li>
 *   <li>Output — the FULL 256-bit HMAC-SHA256 fingerprint, hex-encoded (64
 *       characters; total length 5 + 1 + 64 = 70, within the 200-character
 *       {@code users.subject} column limit). No truncation, so no collision
 *       policy is needed (a birthday bound of 2^128 with no practical
 *       significance; the column's {@code unique} constraint enforces
 *       distinctness regardless).</li>
 *   <li>Deterministic — the same key and the same subject always derive the
 *       same replacement, which makes the administrative operation
 *       idempotent and makes the pre-provisioning tombstone probe
 *       verifiable: a re-issued raw subject derives the same replacement and
 *       is caught before a new account is created.</li>
 *   <li>Key rotation — a different key derives a different replacement. The
 *       <em>write</em> path ({@link #derive}) always uses the active key; the
 *       <em>probe</em> path ({@link #deriveAll}) derives under the active key
 *       plus every retained previous key (the keyring, plan §9 rotation row
 *       resolved option 1 — secrets-policy §3 "dual key / overlap window"),
 *       so tombstones written before a rotation keep matching the
 *       re-registration guard for as long as their key stays in the ring.</li>
 * </ul>
 *
 * <p>Lives in {@code shared :: shared-security} so the identity module
 * (which already declares this named interface as an allowed dependency)
 * consumes it without any module-boundary change — the plan §5-د constraint:
 * zero new imports across module boundaries, no {@code package-info} edits.
 */
@Component
public class SubjectPseudonymizer {

    static final String ANON_PREFIX = "anon-";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final MarketplaceProperties properties;

    public SubjectPseudonymizer(MarketplaceProperties properties) {
        this.properties = properties;
    }

    /**
     * Whether the HMAC secret channel is bound. When it is not, the
     * pseudonymize capability is OFF (not broken) and the re-registration
     * probe is inert — no tombstone can exist while the key never existed.
     */
    public boolean isConfigured() {
        return !properties.security().pseudonymization().subjectHmacKey().isBlank();
    }

    /**
     * Derives the replacement subject for a raw subject string — the <b>write
     * path</b>, always under the active key (new tombstones are written in
     * today's key; a rotation changes the replacements going forward).
     *
     * @throws ServiceUnavailableException when the secret channel is not
     *         bound — the capability answers 503 SU-001, the house pattern
     *         for an optional channel that has not been configured
     *         (the PSP/MAIL gates' semantics: OFF, not broken)
     */
    public String derive(String rawSubject) {
        String key = properties.security().pseudonymization().subjectHmacKey();
        if (key.isBlank()) {
            throw new ServiceUnavailableException(
                    "Account pseudonymization is not configured. "
                            + "Bind PSEUDONYMIZATION_HMAC_KEY (secrets-policy §1: environment-only) "
                            + "to enable the administrative surface.");
        }
        return ANON_PREFIX + hmacHex(key, rawSubject);
    }

    /**
     * Derives <b>every</b> replacement subject the system could ever have
     * written for this raw subject — the <b>probe path</b> (I7 §9 rotation
     * row, resolved option 1: the keyring). The active key's derivation
     * covers fresh tombstones; every retained previous key's derivation
     * covers the tombstones written before a rotation — so the
     * re-registration guard in {@code syncFromOidc} keeps matching the
     * historical stock exactly as it matches new rows (the overlap window
     * secrets-policy §3 requires, with no service stop).
     *
     * <p>Contract details, pinned by {@code SubjectPseudonymizerTest}:</p>
     * <ul>
     *   <li>Order — the active key's derivation first, then the retained
     *       keys in their configured order (deterministic, readable in
     *       diagnostics).</li>
     *   <li>Blank keys are filtered and duplicate keys collapse to one
     *       derivation (re-listing the active key in the previous-keys ring
     *       is a harmless operator slip, not a doubled probe).</li>
     *   <li>Empty when no key is bound at all — the probe's inert state
     *       (no tombstone can exist while no key ever existed). Unlike
     *       {@link #derive} this never throws: a probe is a read, not a
     *       capability — a ring of previous keys with a blank active key
     *       still returns the historical derivations (those tombstones
     *       exist even though new pseudonymizations are OFF).</li>
     *   <li>Dropping a key from the ring while tombstones derived under it
     *       still exist re-opens the re-registration hole for exactly that
     *       stock — the operational rule the plan's §9 row states: never
     *       remove the last copy of a key that ever wrote a tombstone.</li>
     * </ul>
     */
    public List<String> deriveAll(String rawSubject) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        MarketplaceProperties.Security.Pseudonymization pseudonymization =
                properties.security().pseudonymization();
        if (!pseudonymization.subjectHmacKey().isBlank()) {
            keys.add(pseudonymization.subjectHmacKey());
        }
        List<String> previousKeys = pseudonymization.subjectHmacPreviousKeys();
        if (previousKeys != null) {
            for (String key : previousKeys) {
                if (key != null && !key.isBlank()) {
                    keys.add(key);
                }
            }
        }
        List<String> derivations = new ArrayList<>(keys.size());
        for (String key : keys) {
            derivations.add(ANON_PREFIX + hmacHex(key, rawSubject));
        }
        return List.copyOf(derivations);
    }

    /**
     * The shared transformation core: the full 256-bit HMAC-SHA256
     * fingerprint of the raw subject's UTF-8 bytes, hex-encoded — the exact
     * byte-level contract {@code SubjectPseudonymizerTest} recomputes
     * independently with the JDK's own {@code javax.crypto}.
     */
    private static String hmacHex(String key, String rawSubject) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] fingerprint = mac.doFinal(rawSubject.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(fingerprint);
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            // HmacSHA256 is mandated by every JDK vendor; reaching here means
            // a broken runtime, not a configuration problem.
            throw new IllegalStateException("HMAC-SHA256 unavailable: " + ex.getMessage(), ex);
        }
    }
}
