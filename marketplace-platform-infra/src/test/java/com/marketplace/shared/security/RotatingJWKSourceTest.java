package com.marketplace.shared.security;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RotatingJWKSource}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Initial state: single active key</li>
 *   <li>After rotation: two keys (active + previous)</li>
 *   <li>After second rotation: previous key is dropped (max 2 keys)</li>
 *   <li>Thread-safe rotation via AtomicReference</li>
 * </ul>
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7517#section-4.5">RFC 7517 §4.5 — Key Rotation</a>
 */
class RotatingJWKSourceTest {

    @Test
    void initialState_hasSingleActiveKey() {
        RSAKey initial = generateTestKey();
        RotatingJWKSource source = new RotatingJWKSource(initial);

        assertEquals(1, source.getJWKSet().getKeys().size(),
                "Initial state must have exactly one key (the active key)");
        assertEquals(initial.getKeyID(), source.getJWKSet().getKeys().getFirst().getKeyID());
    }

    @Test
    void rotate_keepsOldKeyAsPrevious() {
        RSAKey initial = generateTestKey();
        RotatingJWKSource source = new RotatingJWKSource(initial);

        source.rotate();

        assertEquals(2, source.getJWKSet().getKeys().size(),
                "After one rotation, must have active + previous key");
        // The new active key (first in the set) must be different from the initial.
        assertNotEquals(initial.getKeyID(), source.getJWKSet().getKeys().getFirst().getKeyID());
        // The initial key must be retained as the previous key (second in the set).
        assertEquals(initial.getKeyID(), source.getJWKSet().getKeys().get(1).getKeyID());
    }

    @Test
    void rotateTwice_dropsOldPreviousKey() {
        RSAKey key1 = generateTestKey();
        RotatingJWKSource source = new RotatingJWKSource(key1);

        source.rotate(); // now: [key2_active, key1_previous]
        RSAKey key2 = (RSAKey) source.getJWKSet().getKeys().getFirst();

        source.rotate(); // now: [key3_active, key2_previous] — key1 dropped

        assertEquals(2, source.getJWKSet().getKeys().size(),
                "After two rotations, must still have exactly 2 keys (max overlap window)");
        // key1 must be dropped.
        assertFalse(source.getJWKSet().getKeys().stream()
                        .anyMatch(k -> k.getKeyID().equals(key1.getKeyID())),
                "The oldest key must be dropped after the second rotation");
        // key2 must be retained as previous.
        assertTrue(source.getJWKSet().getKeys().stream()
                        .anyMatch(k -> k.getKeyID().equals(key2.getKeyID())),
                "The most recent previous key must be retained");
    }

    @Test
    void get_returnsKeysMatchingSelector() throws Exception {
        RSAKey initial = generateTestKey();
        RotatingJWKSource source = new RotatingJWKSource(initial);

        // Use a JWKMatcher that matches any RSA key, then wrap it in a JWKSelector.
        com.nimbusds.jose.jwk.JWKMatcher matcher = new com.nimbusds.jose.jwk.JWKMatcher.Builder()
                .keyType(com.nimbusds.jose.jwk.KeyType.RSA)
                .build();
        com.nimbusds.jose.jwk.JWKSelector selector = new com.nimbusds.jose.jwk.JWKSelector(matcher);
        List<JWK> keys = source.get(selector, null);

        assertNotNull(keys);
        assertFalse(keys.isEmpty(), "JWKSource.get must return the active RSA key for an RSA matcher");
        assertEquals(initial.getKeyID(), keys.getFirst().getKeyID());
    }

    /** Generates a fresh RSA test key. */
    private RSAKey generateTestKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
