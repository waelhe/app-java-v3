package com.marketplace.console;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stage 9 (ADR-0005): the planning record's reads — the current planning
 * (the highest revision), the next revision number, the rollback's
 * target lookup, and the append-only trail's own inventory (the web
 * board's rollback table, newest first).
 */
public interface ConsolePlanningRevisionRepository extends JpaRepository<ConsolePlanningRevision, UUID> {

    Optional<ConsolePlanningRevision> findTopByOrderByRevisionNoDesc();

    Optional<ConsolePlanningRevision> findByRevisionNo(int revisionNo);

    List<ConsolePlanningRevision> findAllByOrderByRevisionNoDesc();
}
