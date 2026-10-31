package com.marketplace.identity;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.marketplace.identity.spi.RecipientView;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * staff notification recipient registry's owning use case — the Saleor
 * {@code staffNotificationRecipientCreate/Update/Delete} mutations.
 * ADMIN-enforced at this boundary (Saleor requires MANAGE_STAFF). The
 * oneof pair (user | email) is the V249 CHECK's own contract; this use
 * case validates it before any write and keeps the registry's live-row
 * uniqueness invariants.
 */
@Service
@Transactional
public class StaffNotificationRecipientService {

    private static final Logger log =
            LoggerFactory.getLogger(StaffNotificationRecipientService.class);
    private static final Pattern EMAIL_SHAPE =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final StaffNotificationRecipientRepository repository;
    private final UserRepository userRepository;

    public StaffNotificationRecipientService(StaffNotificationRecipientRepository repository,
                                             UserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }


    /** {@code staffNotificationRecipientCreate}: one row per oneof pair. */
    @Observed(name = "staff_notification_recipient.create")
    public RecipientView create(Authentication caller, UUID userId, String email,
                                Boolean active) {
        requireAdmin(caller);
        boolean isActive = active == null || active;
        StaffNotificationRecipient row;
        if (userId != null) {
            requireUser(userId);
            if (repository.findByUserId(userId).isPresent()) {
                throw new ConflictException(
                        "The user already has a notification recipient row: " + userId);
            }
            row = StaffNotificationRecipient.forUser(userId, isActive);
        } else if (email != null && !email.isBlank()) {
            String address = email.trim();
            if (!EMAIL_SHAPE.matcher(address).matches()) {
                throw new IllegalArgumentException("A valid email address is required");
            }
            if (repository.findByEmail(address).isPresent()) {
                throw new ConflictException(
                        "The email already has a notification recipient row: " + address);
            }
            row = StaffNotificationRecipient.forEmail(address, isActive);
        } else {
            throw new IllegalArgumentException(
                    "Exactly one of the user reference or the email address is required");
        }
        repository.save(row);
        log.info("Staff notification recipient created: id={}, actor={}", row.getId(),
                caller.getName());
        return toView(row);
    }

    /** {@code staffNotificationRecipientUpdate}: the oneof pair + active flip. */
    @Observed(name = "staff_notification_recipient.update")
    public RecipientView update(Authentication caller, UUID id, UUID userId, String email,
                                Boolean active) {
        requireAdmin(caller);
        StaffNotificationRecipient row = requireRow(id);
        if (userId != null && email != null && !email.isBlank()) {
            throw new IllegalArgumentException(
                    "The user reference and the email address are mutually exclusive");
        }
        if (userId != null) {
            requireUser(userId);
            repository.findByUserId(userId)
                    .filter(existing -> !existing.getId().equals(id))
                    .ifPresent(existing -> {
                        throw new ConflictException(
                                "The user already has a notification recipient row: " + userId);
                    });
        }
        if (email != null && !email.isBlank()) {
            String address = email.trim();
            if (!EMAIL_SHAPE.matcher(address).matches()) {
                throw new IllegalArgumentException("A valid email address is required");
            }
            repository.findByEmail(address)
                    .filter(existing -> !existing.getId().equals(id))
                    .ifPresent(existing -> {
                        throw new ConflictException(
                                "The email already has a notification recipient row: " + address);
                    });
        }
        row.apply(userId, email, active);
        log.info("Staff notification recipient updated: id={}, actor={}", id, caller.getName());
        return toView(row);
    }

    /** {@code staffNotificationRecipientDelete}: the registry row's death. */
    @Observed(name = "staff_notification_recipient.delete")
    public RecipientView delete(Authentication caller, UUID id) {
        requireAdmin(caller);
        StaffNotificationRecipient row = requireRow(id);
        repository.delete(row);
        log.info("Staff notification recipient deleted: id={}, actor={}", id, caller.getName());
        return toView(row);
    }

    /** The registry read the payloads' read-back uses. */
    @Transactional(readOnly = true)
    public Optional<RecipientView> find(Authentication caller, UUID id) {
        requireAdmin(caller);
        return repository.findById(id).map(com.marketplace.identity.StaffNotificationRecipientService::toView);
    }

    @Transactional(readOnly = true)
    public List<RecipientView> list(Authentication caller) {
        requireAdmin(caller);
        return repository.findAllByOrderByCreatedAtAscIdAsc()
                .stream().map(com.marketplace.identity.StaffNotificationRecipientService::toView).toList();
    }

    private static RecipientView toView(StaffNotificationRecipient row) {
        return new RecipientView(row.getId(), row.getUserId(), row.getEmail(), row.isActive());
    }

    private StaffNotificationRecipient requireRow(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No staff notification recipient " + id));
    }

    private void requireUser(UUID userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("No user " + userId);
        }
    }

    private static void requireAdmin(Authentication caller) {
        boolean admin = caller.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        if (!admin) {
            throw new AccessDeniedException(
                    "Staff notification administration requires the ADMIN capability");
        }
    }
}
