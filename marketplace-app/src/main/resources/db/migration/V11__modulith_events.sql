-- Spring Modulith event publication log table
create table if not exists modulith_events (
    id                  uuid primary key,
    listener_id         varchar(200) not null,
    event_type          varchar(200) not null,
    event               bytea not null,
    publication_date    timestamptz not null default now(),
    completion_date     timestamptz
);

create index idx_modulith_events_listener on modulith_events (listener_id) where completion_date is null;
create index idx_modulith_events_type on modulith_events (event_type);