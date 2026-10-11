package com.marketplace.identity;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.VerificationCredentialDecided;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The verification credential lifecycle service (ADR-0001, plan §Phase 1).
 *
 * <p><b>The state machine</b> lives on the entity
 * ({@link VerificationCredential#approve}/{@link VerificationCredential#reject}/
 * {@link VerificationCredential#revoke}); this service is the transactional
 * boundary around it: the submit gate (one ACTIVE application per type),
 * the admin decisions, the {@link VerificationCredentialDecided} fact
 * published in-transaction, and the structured audit line.
 *
 * <p><b>The ADR-0001 separation, restated where it bites:</b> NOT ONE line
 * here touches a role store — no {@link UserRepository} role write, no
 * {@code auth_authorities} row, no {@code UserRoleChanged} fact. A decision
 * is evidence; the grant of any role stays the separate administrative act
 * on the role stores. The service tests pin this boundary explicitly
 * ({@code verifyNoInteractions} on the user store would be redundant — the
 * user store is only read, never written, and roles are never read here).
 */
@Service
@Transactional
public class VerificationCredentialService {

    private static final Logger log = LoggerFactory.getLogger(VerificationCredentialService.class);

    /** The statuses that keep an application alive — the submit gate's block set. */
    private static final Set<VerificationCredentialStatus> ACTIVE_STATUSES =
            Set.of(VerificationCredentialStatus.PENDING, VerificationCredentialStatus.APPROVED);

    private final VerificationCredentialRepository repository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    public VerificationCredentialService(VerificationCredentialRepository repository,
                                         UserRepository userRepository,
                                         ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Self-service submission — the account submits evidence about ITSELF.
     * One ACTIVE (PENDING or APPROVED) application per type: a second
     * submission while one is alive is the 409 contract; after a terminal
     * decision (REJECTED/REVOKED) a NEW row opens the resubmission path and
     * the history stays complete.
     */
    @Observed(name = "verification.credential.submit")
    public VerificationCredential submit(String subject, String credentialType,
                                         String evidenceUri, String notes) {
        VerificationCredentialType type = parseType(credentialType);
        UUID userId = userRepository.findBySubject(subject)
                .orElseThrow(() -> new ResourceNotFoundException("User not found for subject: " + subject))
                .getId();
        if (repository.existsByUserIdAndCredentialTypeAndStatusIn(userId, type, ACTIVE_STATUSES)) {
            throw new ConflictException(
                    "An active " + type + " credential already exists for this account");
        }
        VerificationCredential credential = repository.save(
                VerificationCredential.submit(userId, type, evidenceUri, notes));
        log.info("Verification credential submitted: id={}, userId={}, type={}, actor={}",
                credential.getId(), userId, type, subject);
        return credential;
    }

    /** The account's own credential history — newest first. */
    @Transactional(readOnly = true)
    public List<VerificationCredential> mine(String subject) {
        UUID userId = userRepository.findBySubject(subject)
                .orElseThrow(() -> new ResourceNotFoundException("User not found for subject: " + subject))
                .getId();
        return repository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /** The admin review queue — the full set, or one status. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public Page<VerificationCredential> adminPage(VerificationCredentialStatus status, Pageable pageable) {
        return status == null
                ? repository.findAll(pageable)
                : repository.findByStatus(status, pageable);
    }

    /**
     * The admin decision: {@code APPROVED} or {@code REJECTED} from
     * {@code PENDING} (the entity's state machine answers 409 on every
     * other transition). Publishing the {@link VerificationCredentialDecided}
     * fact is the LAST write in the command — and the only one besides the
     * credential row itself.
     */
    @Observed(name = "verification.credential.decide")
    @PreAuthorize("hasRole('ADMIN')")
    public VerificationCredential decide(UUID credentialId, String decision, String notes, String actor) {
        VerificationCredential credential = repository.findById(credentialId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Verification credential not found: " + credentialId));
        switch (decision) {
            case "APPROVED" -> credential.approve(actor, notes);
            case "REJECTED" -> credential.reject(actor, notes);
            default -> throw new BadRequestException(
                    "decision must be APPROVED or REJECTED: " + decision);
        }
        VerificationCredential saved = repository.save(credential);
        eventPublisher.publishEvent(new VerificationCredentialDecided(
                saved.getId(), saved.getUserId(), saved.getCredentialType().name(), decision, actor));
        // ADR-0001: this command publishes EVIDENCE, never authority — no
        // role store is written here, by design and by test.
        log.info("Verification credential decided: id={}, userId={}, type={}, decision={}, actor={}",
                saved.getId(), saved.getUserId(), saved.getCredentialType(), decision, actor);
        return saved;
    }

    /**
     * The admin revocation: {@code APPROVED → REVOKED} — the standing
     * verification's withdrawal (the same 409 state-machine discipline).
     */
    @Observed(name = "verification.credential.revoke")
    @PreAuthorize("hasRole('ADMIN')")
    public VerificationCredential revoke(UUID credentialId, String notes, String actor) {
        VerificationCredential credential = repository.findById(credentialId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Verification credential not found: " + credentialId));
        credential.revoke(actor, notes);
        VerificationCredential saved = repository.save(credential);
        eventPublisher.publishEvent(new VerificationCredentialDecided(
                saved.getId(), saved.getUserId(), saved.getCredentialType().name(),
                saved.getStatus().name(), actor));
        log.info("Verification credential revoked: id={}, userId={}, type={}, actor={}",
                saved.getId(), saved.getUserId(), saved.getCredentialType(), actor);
        return saved;
    }

    static VerificationCredentialType parseType(String credentialType) {
        try {
            return VerificationCredentialType.valueOf(credentialType);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unknown credential type: " + credentialType);
        }
    }

    static VerificationCredentialStatus parseStatus(String status) {
        try {
            return VerificationCredentialStatus.valueOf(status);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unknown credential status: " + status);
        }
    }
}
