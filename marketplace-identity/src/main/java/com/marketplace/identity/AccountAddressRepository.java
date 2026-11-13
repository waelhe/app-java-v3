package com.marketplace.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * user-account address book's repository — the derived-query contract per
 * the official Spring Data JPA repository docs (the UserRepository
 * precedent: derived finders, no hand-written SQL, soft-deleted rows
 * absent by the entity's own {@code @SoftDelete} filter).
 */
public interface AccountAddressRepository extends JpaRepository<AccountAddress, UUID>,
        RevisionRepository<AccountAddress, UUID, Integer> {

    Optional<AccountAddress> findByIdAndUserId(UUID id, UUID userId);

    List<AccountAddress> findAllByUserIdOrderByCreatedAtAscIdAsc(UUID userId);

    List<AccountAddress> findByUserIdAndDefaultShippingTrue(UUID userId);

    List<AccountAddress> findByUserIdAndDefaultBillingTrue(UUID userId);

    long countByUserId(UUID userId);
}
