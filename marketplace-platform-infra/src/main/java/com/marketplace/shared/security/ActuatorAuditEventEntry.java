package com.marketplace.shared.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The official audit-event store's own row — the persistence half of the
 * Spring Boot Actuator "Auditing" recipe (the reference's production
 * recommendation: "For production environments, consider creating your own
 * AuditEventRepository implementation" — this entity is that implementation's
 * row; {@link JpaActuatorAuditEventRepository} is the repository).
 *
 * <p><b>What lands here (all of it automatic, zero hand-wiring):</b> once the
 * {@link JpaActuatorAuditEventRepository} bean exists, Boot's actuator
 * auto-configuration registers {@code AuthenticationAuditListener} and
 * {@code AuthorizationAuditListener}, and Spring Security's
 * {@code DefaultAuthenticationEventPublisher} (already active — the automatic
 * path verified against the spring-boot-autoconfigure 4.1.1 sources) feeds
 * them every authentication success/failure and authorization refusal. The
 * events arrive as {@code AuditEvent(timestamp, principal, type, data)} and
 * this row stores that triple verbatim: the timestamp, the principal, the
 * type, and the data map as {@code jsonb} through Hibernate's official JSON
 * mapping ({@code @JdbcTypeCode(SqlTypes.JSON)} — the same mapping
 * {@code SystemSetting} already trusts for its {@code jsonb} column).
 *
 * <p><b>Append-only by construction:</b> security audit history is never
 * updated or soft-deleted — the row is written once by
 * {@link JpaActuatorAuditEventRepository#add} and read by the
 * {@code /actuator/auditevents} endpoint's three documented filters
 * (principal, after, type). That is why this entity deliberately does not
 * extend {@code BaseEntity}: optimistic locking, Data JPA auditing columns,
 * and Hibernate {@code @SoftDelete} are row-mutation machinery, and this
 * table has no row mutations — the Flyway {@code V213} migration creates
 * exactly the columns this mapping writes.
 *
 * <p><b>PII posture (the house rule verbatim):</b> the principal is the
 * authentication's own subject string (the username), the type is Spring
 * Security's own event vocabulary ({@code AUTHENTICATION_SUCCESS},
 * {@code AUTHENTICATION_FAILURE}, {@code AUTHORIZATION_DENIED}...), and the
 * data map carries the event's own metadata — never credentials, never raw
 * tokens (the listener layer's own contract, see
 * {@code AuthenticationEvents}).
 */
@Entity
@Table(name = "actuator_audit_events")
public class ActuatorAuditEventEntry {

    @Id
    @Column(nullable = false)
    private UUID id;

    @Column(name = "event_time", nullable = false)
    private Instant eventTime;

    @Column(nullable = true)
    private String principal;

    @Column(name = "audit_type", nullable = false, length = 100)
    private String auditType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> data;

    /**
     * The JPA provider's own constructor (the specification's requirement for
     * field-access entities) — never called by the application.
     */
    protected ActuatorAuditEventEntry() {
    }

    /**
     * The single write path — the {@link AuditEvent}'s triple stored verbatim.
     */
    ActuatorAuditEventEntry(UUID id, Instant eventTime, String principal, String auditType,
                            Map<String, Object> data) {
        this.id = id;
        this.eventTime = eventTime;
        this.principal = principal;
        this.auditType = auditType;
        this.data = data;
    }

    UUID getId() {
        return id;
    }

    Instant getEventTime() {
        return eventTime;
    }

    String getPrincipal() {
        return principal;
    }

    String getAuditType() {
        return auditType;
    }

    Map<String, Object> getData() {
        return data;
    }
}
