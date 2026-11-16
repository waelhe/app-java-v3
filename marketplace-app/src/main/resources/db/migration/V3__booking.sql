-- Bookings table
create table if not exists bookings (
    id              uuid primary key,
    consumer_id     uuid not null references users(id),
    provider_id     uuid not null references users(id),
    listing_id     uuid not null references provider_listings(id),
    status          varchar(20) not null default 'PENDING' check (status in ('PENDING','CONFIRMED','COMPLETED','CANCELLED')),
    price_cents     bigint not null check (price_cents >= 0),
    currency       varchar(3) not null default 'SAR',
    notes           text,
    is_deleted      boolean not null default false,
    version         bigint not null default 0,
    created_by      varchar(200),
    created_at      timestamptz not null default now(),
    updated_by      varchar(200),
    updated_at      timestamptz not null default now()
);

create index idx_bookings_consumer on bookings (consumer_id) where is_deleted = false;
create index idx_bookings_provider on bookings (provider_id) where is_deleted = false;
create index idx_bookings_listing on bookings (listing_id) where is_deleted = false;
create index idx_bookings_status on bookings (status) where is_deleted = false;