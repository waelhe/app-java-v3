package com.marketplace.shared.security;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link JWKSource} that supports key rotation by maintaining two RSA keys:
 * an <em>active</em> key (used for signing new tokens) and a <em>previous</em>
 * key (kept for validating tokens issued before the last rotation).
 *
 * <p><b>Rotation semantics</b>:
 * <ul>
 *   <li>On the first call, a single active key is generated.</li>
 *   <li>When {@link #rotate()} is called, the current active key becomes the
 *       previous key, and a new active key is generated. Both are returned in
 *       the {@link JWKSet}, so resource servers can validate tokens signed by
 *       either key during the overlap window.</li>
 *   <li>The previous key is dropped on the next rotation (only 2 keys at a time).</li>
 * </ul>
 *
 * <p><b>Why not ImmutableJWKSet?</b> The prior implementation used
 * {@code ImmutableJWKSet} with a single key — rotating the key required
 * replacing the keystore file and restarting all instances. During the restart
 * window, previously-issued tokens could not be validated. This implementation
 * supports hot rotation without restart.
 *
 * <p><b>References</b>
 * <ul>
 *   <li><a href="https://datatracker.ietf.org/doc/html/rfc7517#section-4.5">RFC 7517 §4.5 — Key Rotation</a></li>
 *   <li><a href="https://nvd.nist.gov/800-57">NIST SP 800-57 §8 — Key Rotation</a></li>
 *   <li><a href="https://connect2id.com/products/nimbus-jose-jwt/examples/jwk-generation">Nimbus JOSE+JWT — JWK Generation</a></li>
 * </ul>
 */
public class RotatingJWKSource implements JWKSource<SecurityContext> {

    private static final Logger log = LoggerFactory.getLogger(RotatingJWKSource.class);

    /** Holds the current JWKSet (active + optional previous key). Thread-safe via AtomicReference. */
    private final AtomicReference<JWKSet> currentSet = new AtomicReference<>();

    /**
     * Creates a new RotatingJWKSource with the given initial key as the active key.
     *
     * @param initialKey the RSA key to use initially (from keystore in prod, or
     *                   randomly generated in dev)
     */
    public RotatingJWKSource(RSAKey initialKey) {
        this.currentSet.set(new JWKSet(initialKey));
        log.info("RotatingJWKSource initialized with active key: kid={}", initialKey.getKeyID());
    }

    @Override
    public List<JWK> get(JWKSelector jwkSelector, SecurityContext context) throws KeySourceException {
        return jwkSelector.select(currentSet.get());
    }

    /**
     * Returns the current JWKSet (active + optional previous key).
     * Exposed for /oauth2/jwks endpoint and JwtDecoder.
     */
    public JWKSet getJWKSet() {
        return currentSet.get();
    }

    /**
     * Rotates the signing key: the current active key becomes the previous key,
     * and a new active key is generated. Both keys are returned in the JWKSet
     * so resource servers can validate tokens signed by either key during the
     * overlap window.
     *
     * <p>Thread-safe: uses {@link AtomicReference#compareAndSet} to ensure only
     * one rotation succeeds if called concurrently.
     */
    public void rotate() {
        JWKSet old = currentSet.get();
        RSAKey oldActive = (RSAKey) old.getKeys().getFirst();

        RSAKey newActive = generateRsaKey();
        // Keep the old key for validation overlap; drop any older "previous" key.
        // JWKSet takes a List<JWK> — we provide [newActive, oldActive].
        JWKSet newSet = new JWKSet(List.of(newActive, oldActive));
        currentSet.compareAndSet(old, newSet);

        log.info("JWK rotation completed: new active kid={}, previous kid={}",
                newActive.getKeyID(), oldActive.getKeyID());
    }

    /** Generates a fresh 2048-bit RSA key pair for JWT signing. */
    private static RSAKey generateRsaKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate RSA key pair", e);
        }
    }
}
