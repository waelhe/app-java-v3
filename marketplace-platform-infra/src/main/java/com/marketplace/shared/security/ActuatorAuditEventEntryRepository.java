package com.marketplace.shared.security;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

/**
 * The Spring Data half of the official audit-event store
 * (Spring Data JPA's documented repository abstraction over the
 * {@code actuator_audit_events} table — the table Flyway {@code V213}
 * creates). The {@link JpaActuatorAuditEventRepository} wraps this
 * interface to implement Boot's own {@code AuditEventRepository} contract.
 *
 * <p><b>Why the Specification executor and not a fixed @Query:</b> the
 * {@code /actuator/auditevents} endpoint's three documented filters
 * (principal, after, type) are each independently optional — {@code null}
 * means "no filter". A single fixed query with nullable parameters pushes
 * the null-handling into the dialect (the classic parameter-typing trap on
 * PostgreSQL), while {@link JpaSpecificationExecutor} composes exactly the
 * non-null predicates — the documented dynamic-query approach, one
 * specification per present filter, nothing bound for the absent ones.
 */
interface ActuatorAuditEventEntryRepository
        extends JpaRepository<ActuatorAuditEventEntry, UUID>, JpaSpecificationExecutor<ActuatorAuditEventEntry> {
}
