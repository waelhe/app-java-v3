package com.marketplace.search;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Stage 10 (ADR-0006): the eval set's reads — the cases of one query (the
 * runner's input) and the whole set (the report's denominator).
 */
public interface OrderingEvalCaseRepository extends JpaRepository<OrderingEvalCase, UUID> {

    List<OrderingEvalCase> findByQuery(String query);
}
