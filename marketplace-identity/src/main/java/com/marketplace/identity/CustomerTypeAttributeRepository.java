package com.marketplace.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * customer type's assigned-attribute repository — derived queries per the
 * official Spring Data JPA contract.
 */
public interface CustomerTypeAttributeRepository extends JpaRepository<CustomerTypeAttribute, UUID>,
        RevisionRepository<CustomerTypeAttribute, UUID, Integer> {

    List<CustomerTypeAttribute> findAllByCustomerTypeIdOrderByPositionAscIdAsc(UUID customerTypeId);

    Optional<CustomerTypeAttribute> findByCustomerTypeIdAndAttributeId(UUID customerTypeId, UUID attributeId);

    List<CustomerTypeAttribute> findAllByCustomerTypeIdAndAttributeIdIn(UUID customerTypeId, List<UUID> attributeIds);

    long countByCustomerTypeId(UUID customerTypeId);
}
