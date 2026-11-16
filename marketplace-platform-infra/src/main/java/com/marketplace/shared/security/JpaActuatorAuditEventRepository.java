package com.marketplace.shared.security;

import org.springframework.boot.actuate.audit.AuditEvent;
import org.springframework.boot.actuate.audit.AuditEventRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The official audit-event repository — the production implementation the
 * Spring Boot Actuator reference calls for: "You can enable auditing by
 * providing a bean of type AuditEventRepository in your application's
 * configuration. For convenience, Spring Boot offers an
 * InMemoryAuditEventRepository. InMemoryAuditEventRepository has limited
 * capabilities, and we recommend using it only for development
 * environments. For production environments, consider creating your own
 * AuditEventRepository implementation." This is that implementation:
 * JPA-backed, on the {@code actuator_audit_events} table (Flyway {@code V213}),
 * surviving restarts and replicas.
 *
 * <p><b>What turns on automatically when this bean exists (the official
 * wiring, zero hand-configuration):</b> Boot's actuator auto-configuration
 * registers its {@code AuthenticationAuditListener} and
 * {@code AuthorizationAuditListener}; the events Spring Security's
 * {@code DefaultAuthenticationEventPublisher} already publishes on the
 * application event bus (every authentication success and failure, every
 * authorization refusal — the same bus {@code AuthenticationEvents}
 * subscribes to for its log markers) flow into {@link #add}; the
 * {@code /actuator/auditevents} endpoint then answers from
 * {@link #find}. Exposing that endpoint is the
 * {@code auditevents} id in {@code management.endpoints.web.exposure.include}
 * — the operator-surface configuration this change ships with it.
 *
 * <p><b>The contract honored verbatim — the reference's own
 * InMemoryAuditEventRepository semantics:</b></p>
 * <ul>
 *   <li>{@code add} persists the event's own triple (timestamp, principal,
 *   type, data) with a fresh identifier.</li>
 *   <li>{@code find} applies exactly the non-null filters — the endpoint's
 *   three documented query parameters, {@code null} meaning "no filter".</li>
 *   <li>The {@code after} filter is EXCLUSIVE — an event whose timestamp
 *   EQUALS {@code after} is not returned, matching the reference
 *   implementation's {@code event.getTimestamp().isAfter(after)} (the
 *   CodeRabbit #564 round caught the inclusive deviation).</li>
 * </ul>
 *
 * <p><b>The bounded read (the CodeRabbit #564 round):</b> the table is
 * append-only and grows forever; an unbounded {@code find} would load the
 * entire history into the endpoint's response. Every read is the newest
 * {@value #FIND_LIMIT} matching events — the incident-time window the
 * operator actually investigates (older history stays in the table,
 * reachable through the database itself when an investigation needs it).
 */
@Component
public class JpaActuatorAuditEventRepository implements AuditEventRepository {

    /**
     * The newest-events window every find serves — generous for an
     * incident-time read ("what happened in the last stretch"), bounded for
     * an append-only table that only grows.
     */
    static final int FIND_LIMIT = 1000;

    /**
     * The read's own shape: newest first, capped at {@value #FIND_LIMIT}.
     */
    private static final Pageable NEWEST_FIRST =
            PageRequest.of(0, FIND_LIMIT, Sort.by(Sort.Direction.DESC, "eventTime"));

    private final ActuatorAuditEventEntryRepository entries;

    public JpaActuatorAuditEventRepository(ActuatorAuditEventEntryRepository entries) {
        this.entries = entries;
    }

    @Override
    public void add(AuditEvent event) {
        // The event's data map is never null by construction — Boot's own
        // AuditEvent constructors wrap it in an unmodifiable map (the
        // varargs convenience form builds an empty one).
        entries.save(new ActuatorAuditEventEntry(
                UUID.randomUUID(),
                event.getTimestamp(),
                event.getPrincipal(),
                event.getType(),
                event.getData()));
    }

    @Override
    public List<AuditEvent> find(String principal, Instant after, String type) {
        List<Specification<ActuatorAuditEventEntry>> filters = new ArrayList<>();
        if (principal != null) {
            filters.add((root, query, builder) -> builder.equal(root.get("principal"), principal));
        }
        if (after != null) {
            // EXCLUSIVE — the reference implementation's own boundary:
            // an event AT the after instant is not part of the answer.
            filters.add((root, query, builder) ->
                    builder.greaterThan(root.get("eventTime"), after));
        }
        if (type != null) {
            filters.add((root, query, builder) -> builder.equal(root.get("auditType"), type));
        }

        Specification<ActuatorAuditEventEntry> combined = null;
        for (Specification<ActuatorAuditEventEntry> filter : filters) {
            combined = combined == null ? filter : combined.and(filter);
        }

        List<ActuatorAuditEventEntry> found = combined == null
                ? entries.findAll(NEWEST_FIRST).getContent()
                : entries.findAll(combined, NEWEST_FIRST).getContent();

        return found.stream()
                .map(entry -> new AuditEvent(
                        entry.getEventTime(),
                        entry.getPrincipal(),
                        entry.getAuditType(),
                        entry.getData()))
                .toList();
    }
}
