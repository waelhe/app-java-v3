package com.marketplace.delivery;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface DeliveryCourierRepository extends JpaRepository<DeliveryCourier, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from DeliveryCourier c where c.userId = :userId")
    Optional<DeliveryCourier> findByUserIdForUpdate(@Param("userId") UUID userId);

    @Query("""
            select c from DeliveryCourier c
            where c.available = true and c.locationUpdatedAt > :freshAfter
            order by case when c.lastAssignedAt is null then 0 else 1 end,
                     c.lastAssignedAt asc, c.userId asc
            """)
    Page<DeliveryCourier> findAvailableForDispatch(@Param("freshAfter") Instant freshAfter, Pageable pageable);
}
