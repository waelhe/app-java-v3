package com.marketplace.orders;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CartRepository extends JpaRepository<Cart, UUID> {

    /**
     * A-11: the consumer's single live draft. V113's partial-unique index
     * (one ACTIVE cart per consumer) makes this at-most-one by construction;
     * the service's get-or-create is idempotent under it (a concurrent
     * double-create loses to the index, 23505 → the house 409 translation).
     */
    Optional<Cart> findByConsumerIdAndStatus(UUID consumerId, CartStatus status);
}
