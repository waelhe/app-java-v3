package com.marketplace.identity;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.TrustType;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Phase 1 (the unified plan §10) — the verification attestation's
 * lifecycle: the self-service request with its evidence, the
 * administrative review decision (grant/reject), and the withdrawal of
 * a granted fact. The four §6.5 trust types stay SEPARATE facts with
 * separate evidence — one attestation per (subject, type) GRANTED at
 * most (V184's partial unique index backs the service's read), never a
 * conflated «موثق» flag.
 *
 * <p><b>The gates, by transition:</b> the REQUEST is self-service (the
 * account asks for its own verification — the same shape as the /me
 * writes; the caller's identity IS the subject, resolved by the
 * controller from the authentication). The REVIEW (grant/reject) and
 * the REVOKE are administrative acts — the A-07 three-layer pattern's
 * service gate ({@code @PreAuthorize("hasRole('ADMIN')")}, the same
 * third layer {@code UserService.updateUserRole} carries, measured by
 * its own security test). There is no admin HTTP surface in this wave.
 *
 * <p><b>The write path's order (the {@code RoleAssignmentService}
 * shape):</b> the subject's existence first (the clean 404), the
 * transition's own state gate (the honest 409 with the machine's words
 * — a re-review of a decided attestation, a revoke of a non-granted
 * one), the GRANTED-duplicate read on the grant path, then the write —
 * V184's {@code uq_verification_attestations_one_active} partial unique
 * index is the concurrent backstop (the two-layer model verbatim).
 *
 * <p><b>What a revoke does NOT do:</b> nothing here touches the login
 * chain — the attestation vocabulary is the trust/provenance dimension
 * (§4.5), not the role/authority one; supervision privileges are never
 * gained or lost through a badge or an endorsement (§6.6's own test
 * rule). The row and its audit history stay through every transition
 * (the V178 withdraw philosophy).
 *
 * <p><b>The audit record</b> (the house convention): one structured
 * line per command — actor, subject, type, transition.
 */
@Service
@Transactional
public class VerificationAttestationService {

    private static final Logger log = LoggerFactory.getLogger(VerificationAttestationService.class);

    private final VerificationAttestationRepository repository;
    private final UserRepository userRepository;
    private final Clock clock;

    public VerificationAttestationService(VerificationAttestationRepository repository,
                                          UserRepository userRepository,
                                          Clock clock) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    /**
     * Record the subject's own verification request for one §6.5 trust
     * type, with its evidence reference (required — the evidence is the
     * essence of «أدلة ارتباط بالحي/المنطقة»). A standing GRANTED
     * attestation of the same (subject, type) pair answers the honest
     * 409 — the fact is already granted; fresh PENDING requests queue
     * freely (V184's unique index constrains only the GRANTED state).
     */
    @Observed(name = "identity.attestation.request")
    public VerificationAttestationView request(UUID subjectUserId, TrustType trustType,
                                               String evidenceRef, String actor) {
        userRepository.findById(subjectUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + subjectUserId));
        repository.findBySubjectUserIdAndTrustTypeAndState(
                        subjectUserId, trustType, VerificationAttestationState.GRANTED)
                .ifPresent(existing -> {
                    throw new ConflictException(
                            "A " + trustType.name() + " attestation is already GRANTED for this account");
                });
        VerificationAttestation saved = repository.save(
                VerificationAttestation.request(UUID.randomUUID(), subjectUserId, trustType, evidenceRef));
        log.info("Verification attestation audit: subjectUserId={}, trustType={}, action=REQUEST, actor={}",
                subjectUserId, trustType, actor);
        return VerificationAttestationView.of(saved);
    }

    /**
     * The reviewer's positive decision: PENDING → GRANTED (granted_at
     * and the reviewer recorded). The GRANTED-duplicate read comes first
     * (the clean 409 — the pair already holds its granted fact); a
     * non-PENDING attestation refuses the transition (the machine's own
     * words).
     */
    @Observed(name = "identity.attestation.grant")
    @PreAuthorize("hasRole('ADMIN')")
    public VerificationAttestationView grant(UUID attestationId, UUID reviewerId, String actor) {
        VerificationAttestation attestation = repository.findById(attestationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Verification attestation not found: " + attestationId));
        repository.findBySubjectUserIdAndTrustTypeAndState(
                        attestation.getSubjectUserId(), attestation.getTrustType(),
                        VerificationAttestationState.GRANTED)
                .ifPresent(existing -> {
                    throw new ConflictException(
                            "A " + attestation.getTrustType().name()
                                    + " attestation is already GRANTED for this account");
                });
        attestation.grant(reviewerId, clock.instant());
        log.info("Verification attestation audit: subjectUserId={}, trustType={}, "
                        + "action=GRANT, reviewerId={}, actor={}",
                attestation.getSubjectUserId(), attestation.getTrustType(), reviewerId, actor);
        return VerificationAttestationView.of(attestation);
    }

    /**
     * The reviewer's negative decision: PENDING → REJECTED. The pair is
     * freed by the transition itself (V184's unique index constrains
     * only the GRANTED state), so a fresh request with new evidence is
     * legal by construction.
     */
    @Observed(name = "identity.attestation.reject")
    @PreAuthorize("hasRole('ADMIN')")
    public VerificationAttestationView reject(UUID attestationId, String actor) {
        VerificationAttestation attestation = repository.findById(attestationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Verification attestation not found: " + attestationId));
        attestation.reject();
        log.info("Verification attestation audit: subjectUserId={}, trustType={}, "
                        + "action=REJECT, actor={}",
                attestation.getSubjectUserId(), attestation.getTrustType(), actor);
        return VerificationAttestationView.of(attestation);
    }

    /**
     * Withdraw a GRANTED fact: GRANTED → REVOKED (the stamp recorded,
     * the row and its history kept). A non-granted attestation refuses
     * the transition (the machine's own words).
     */
    @Observed(name = "identity.attestation.revoke")
    @PreAuthorize("hasRole('ADMIN')")
    public VerificationAttestationView revoke(UUID attestationId, String actor) {
        VerificationAttestation attestation = repository.findById(attestationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Verification attestation not found: " + attestationId));
        attestation.revoke(clock.instant());
        log.info("Verification attestation audit: subjectUserId={}, trustType={}, "
                        + "action=REVOKE, actor={}",
                attestation.getSubjectUserId(), attestation.getTrustType(), actor);
        return VerificationAttestationView.of(attestation);
    }

    /**
     * The subject's own attestation history, newest first (the L32
     * deterministic order) — the general read outlet
     * ({@code /me/trust-attestations}); every state included, the pair's
     * own record.
     */
    @Transactional(readOnly = true)
    public List<VerificationAttestationView> listOwn(UUID subjectUserId) {
        return repository.findBySubjectUserIdOrderByCreatedAtDescIdDesc(subjectUserId)
                .stream()
                .map(VerificationAttestationView::of)
                .toList();
    }
}
