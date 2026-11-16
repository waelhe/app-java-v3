-- Spring Modulith event publication registry (PostgreSQL)
create table if not exists event_publication (
    id               uuid not null primary key,
    listener_id      text not null,
    event_type       text not null,
    serialized_event text not null,
    publication_date timestamptz not null,
    completion_date  timestamptz
);

alter table event_publication
    add column if not exists completion_attempts integer not null default 0;
alter table event_publication
    add column if not exists last_resubmission_date timestamptz;
alter table event_publication
    add column if not exists status varchar(255);

create index if not exists event_publication_serialized_event_hash_idx
    on event_publication using hash (serialized_event);
create index if not exists event_publication_by_completion_date_idx
    on event_publication (completion_date);
