package com.marketplace.lending;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Stage 8 (ADR-0004): the offer projection's reads. The lock query is the
 * item's serialization point (the official JPA pessimistic lock): two
 * competing borrowers FOR UPDATE on the same offer row, the loser's
 * overlap query then sees the winner (the EXCLUDE constraint is the
 * concurrency backstop behind this friendly face).
 */
public interface LendingOfferRepository extends JpaRepository<LendingOffer, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from LendingOffer o where o.productId = :productId")
    Optional<LendingOffer> lockForProduct(@Param("productId") UUID productId);

    Optional<LendingOffer> findByProductId(UUID productId);
}
