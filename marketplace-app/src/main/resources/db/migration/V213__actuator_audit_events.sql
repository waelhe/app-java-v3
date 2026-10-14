-- The actuator audit-events store (the official Spring Boot Actuator
-- "Auditing" recipe: "You can enable auditing by providing a bean of type
-- AuditEventRepository in your application's configuration... For production
-- environments, consider creating your own AuditEventRepository
-- implementation" — this table is that implementation's persistence).
--
-- The row IS the AuditEvent triple verbatim: the timestamp, the principal,
-- the type, and the event's own data map as jsonb (the endpoint's wire
-- shape round-trips without transformation). Security audit events are
-- append-only history — no soft delete, no auditing columns, no updates:
-- every SELECT the /actuator/auditevents endpoint issues reads this table
-- as written by Spring Security's AuthenticationAuditListener and
-- AuthorizationAuditListener (the listeners Boot registers automatically
-- once the AuditEventRepository bean exists).

CREATE TABLE actuator_audit_events (
    id          uuid PRIMARY KEY,
    event_time  timestamptz NOT NULL,
    principal   varchar(255),
    audit_type  varchar(100) NOT NULL,
    data        jsonb NOT NULL
);

-- The endpoint's three documented filters (principal, after, type) — the
-- time filter is the hot path (every dashboard read), the principal filter
-- is the per-account investigation.
CREATE INDEX idx_actuator_audit_events_event_time ON actuator_audit_events (event_time DESC);
CREATE INDEX idx_actuator_audit_events_principal ON actuator_audit_events (principal);
