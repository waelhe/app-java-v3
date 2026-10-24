package com.marketplace.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * staff notification recipient registry's repository — derived queries per
 * the official Spring Data JPA contract (the UserRepository precedent).
 */
public interface StaffNotificationRecipientRepository extends JpaRepository<StaffNotificationRecipient, UUID>,
        RevisionRepository<StaffNotificationRecipient, UUID, Integer> {

    Optional<StaffNotificationRecipient> findByEmail(String email);

    Optional<StaffNotificationRecipient> findByUserId(UUID userId);

    List<StaffNotificationRecipient> findAllByOrderByCreatedAtAscIdAsc();

    long countByActiveTrue();
}
