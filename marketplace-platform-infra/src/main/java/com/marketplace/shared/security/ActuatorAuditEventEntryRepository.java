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
 *
 * <p><b>Why PUBLIC — the measured startup failure:</b> Spring Data builds
 * every repository as a JDK dynamic proxy, and {@code java.lang.reflect.Proxy}
 * refuses a NON-public interface that is not defined by the caller's loader
 * ("non-public interface is not defined by the given loader"). Single-loader
 * test contexts boot either way, but the devtools two-loader run (base
 * loader for the installed infra jar, restart loader for the application)
 * hits exactly that boundary and the whole application fails to start.
 * A public repository interface is proxiable across loaders — the shape
 * every other repository interface in the platform already carries.
 */
public interface ActuatorAuditEventEntryRepository
        extends JpaRepository<ActuatorAuditEventEntry, UUID>, JpaSpecificationExecutor<ActuatorAuditEventEntry> {
}
