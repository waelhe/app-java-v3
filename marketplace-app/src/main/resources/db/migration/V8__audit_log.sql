-- Audit log table for tracking entity changes
create table if not exists audit_log (
    id              bigserial primary key,
    entity_type     varchar(100) not null,
    entity_id       uuid not null,
    action          varchar(20) not null check (action in ('CREATE','UPDATE','DELETE','SOFT_DELETE','RESTORE')),
    changed_by      varchar(200),
    changed_at      timestamptz not null default now(),
    old_values      jsonb,
    new_values      jsonb
);

create index idx_audit_entity on audit_log (entity_type, entity_id);
create index idx_audit_changed_at on audit_log (changed_at);
create index idx_audit_changed_by on audit_log (changed_by);