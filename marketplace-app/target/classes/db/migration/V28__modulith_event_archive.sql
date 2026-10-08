-- Spring Modulith event publication archive (PostgreSQL).
--
-- Required by spring.modulith.events.completion-mode: archive
-- (marketplace-app/src/main/resources/application.yml): on completion the
-- registry moves each (event, listener) publication row from
-- event_publication into event_publication_archive and stamps its
-- completion date (official semantics: "For that archive entry, the
-- completion date is set and the original entry is removed").
--
-- Root cause this closes: V11 created event_publication only. Production
-- runs ddl-auto: none (Flyway owns the schema — SYSTEM.md §7), so the
-- archive relation never existed and every publication completion failed
-- with:
--   ERROR: relation "event_publication_archive" does not exist
-- (live evidence: deployment 51b5496d runtime logs, 2026-09-04 — two hits
-- at Moments' boot-time DayHasPassed catch-up). The test profile masked
-- this gap: application-test.yml disables Flyway and runs
-- ddl-auto: create-drop, so tests build the table from the
-- ArchivedJpaEventPublication entity mapping, not from migrations.
-- Guard: EventPublicationArchiveIntegrationTest boots the full context
-- against the real Flyway schema (flyway.enabled=true, ddl-auto=none).
--
-- Official source — Spring Modulith 2.1.1 Reference, Appendix "Schema
-- Overview" → PostgreSQL → "Archive-enabled schema"
-- (https://docs.spring.io/spring-modulith/reference/appendix.html): identical column
-- set to event_publication (V11) — the JPA archiving entity
-- (ArchivedJpaEventPublication extends the @MappedSuperclass
-- JpaEventPublication, table EVENT_PUBLICATION_ARCHIVE per shipped
-- 2.1.1 bytecode) inherits the same field mappings as the live table.
-- Type conventions follow V11 (timestamptz, text, integer not null
-- default 0) — semantically identical to the official DDL.

create table if not exists event_publication_archive (
    id                     uuid not null primary key,
    listener_id            text not null,
    event_type             text not null,
    serialized_event       text not null,
    publication_date       timestamptz not null,
    completion_date        timestamptz,
    status                 text,
    completion_attempts    integer not null default 0,
    last_resubmission_date timestamptz
);

create index if not exists event_publication_archive_serialized_event_hash_idx
    on event_publication_archive using hash (serialized_event);
create index if not exists event_publication_archive_by_completion_date_idx
    on event_publication_archive (completion_date);
