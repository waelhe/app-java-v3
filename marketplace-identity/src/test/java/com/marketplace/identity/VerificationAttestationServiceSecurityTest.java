package com.marketplace.identity;

import com.marketplace.shared.api.TrustType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 1 (the unified plan §10) — the method-security slice of the
 * attestation surface: the review decisions (grant/reject) and the
 * revoke are administrative acts, gated at the service boundary (the
 * A-07 three-layer pattern's third layer, the
 * {@code UserServiceSecurityTest} shape); the REQUEST stays
 * self-service by contract (the caller IS the subject — the
 * controller resolves it from the authentication, never from a body).
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = { VerificationAttestationService.class })
@EnableMethodSecurity(proxyTargetClass = true)
class VerificationAttestationServiceSecurityTest {

    @Autowired
    private VerificationAttestationService attestationService;

    @MockitoBean
    private VerificationAttestationRepository attestationRepository;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private Clock clock;

    // -- The admin-only rules: negative first --

    @Test
    @WithMockUser(roles = "CONSUMER")
    void grant_whenNotAdmin_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> attestationService.grant(UUID.randomUUID(), UUID.randomUUID(), "member"));
        verifyNoInteractions(attestationRepository);
    }

    @Test
    @WithMockUser(roles = "CONSUMER")
    void reject_whenNotAdmin_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> attestationService.reject(UUID.randomUUID(), "member"));
        verifyNoInteractions(attestationRepository);
    }

    @Test
    @WithMockUser(roles = "CONSUMER")
    void revoke_whenNotAdmin_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> attestationService.revoke(UUID.randomUUID(), "member"));
        verifyNoInteractions(attestationRepository);
    }

    // -- The admin-only rules: positives (the domain executes) --

    @Test
    @WithMockUser(roles = "ADMIN")
    void grant_whenAdmin_thenReachesTheDomain() {
        VerificationAttestation pending = VerificationAttestation.request(
                UUID.randomUUID(), UUID.randomUUID(), TrustType.VERIFIED_LOCAL_MEMBER, "ev");
        when(attestationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(attestationRepository.findBySubjectUserIdAndTrustTypeAndState(
                pending.getSubjectUserId(), TrustType.VERIFIED_LOCAL_MEMBER,
                VerificationAttestationState.GRANTED))
                .thenReturn(Optional.empty());

        assertThatCode(() -> attestationService.grant(pending.getId(), UUID.randomUUID(), "admin"))
                .doesNotThrowAnyException();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void reject_whenAdmin_thenReachesTheDomain() {
        VerificationAttestation pending = VerificationAttestation.request(
                UUID.randomUUID(), UUID.randomUUID(), TrustType.VERIFIED_BUSINESS, "ev");
        when(attestationRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

        assertThatCode(() -> attestationService.reject(pending.getId(), "admin"))
                .doesNotThrowAnyException();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void revoke_whenAdmin_thenReachesTheDomain() {
        VerificationAttestation granted = VerificationAttestation.request(
                UUID.randomUUID(), UUID.randomUUID(), TrustType.VERIFIED_SOURCE, "ev");
        granted.grant(UUID.randomUUID(), java.time.Instant.EPOCH);
        when(attestationRepository.findById(granted.getId())).thenReturn(Optional.of(granted));

        assertThatCode(() -> attestationService.revoke(granted.getId(), "admin"))
                .doesNotThrowAnyException();
    }
}
