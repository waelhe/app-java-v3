alter table bookings
    add column starts_at timestamptz,
    add column ends_at timestamptz;

create index idx_bookings_starts_at on bookings (starts_at) where starts_at is not null and is_deleted = false;
