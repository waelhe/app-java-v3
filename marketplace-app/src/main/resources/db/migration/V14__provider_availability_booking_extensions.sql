-- Provider profiles and availability scheduling
create table if not exists provider_profiles (
    id             uuid primary key,
    user_id        uuid not null unique references users(id),
    display_name   varchar(160) not null,
    bio            text,
    city           varchar(120),
    country        varchar(2),
    status         varchar(32) not null default 'PENDING_VERIFICATION' check (status in ('PENDING_VERIFICATION','ACTIVE','SUSPENDED','REJECTED')),
    is_deleted     boolean not null default false,
    version        bigint not null default 0,
    created_by     varchar(200),
    created_at     timestamptz not null default now(),
    updated_by     varchar(200),
    updated_at     timestamptz not null default now()
);

create index idx_provider_profiles_status on provider_profiles (status) where is_deleted = false;
create index idx_provider_profiles_city on provider_profiles (city) where is_deleted = false;

create table if not exists provider_availability_rules (
    id             uuid primary key,
    provider_id    uuid not null references provider_profiles(id),
    day_of_week    integer not null check (day_of_week between 1 and 7),
    starts_at      time not null,
    ends_at        time not null,
    slot_minutes   integer not null check (slot_minutes > 0),
    is_deleted     boolean not null default false,
    version        bigint not null default 0,
    created_by     varchar(200),
    created_at     timestamptz not null default now(),
    updated_by     varchar(200),
    updated_at     timestamptz not null default now(),
    check (starts_at < ends_at)
);

create index idx_availability_rules_provider_day on provider_availability_rules (provider_id, day_of_week) where is_deleted = false;

create table if not exists provider_time_off (
    id             uuid primary key,
    provider_id    uuid not null references provider_profiles(id),
    starts_at      timestamptz not null,
    ends_at        timestamptz not null,
    reason         varchar(500),
    is_deleted     boolean not null default false,
    version        bigint not null default 0,
    created_by     varchar(200),
    created_at     timestamptz not null default now(),
    updated_by     varchar(200),
    updated_at     timestamptz not null default now(),
    check (starts_at < ends_at)
);

create index idx_provider_time_off_provider_window on provider_time_off (provider_id, starts_at, ends_at) where is_deleted = false;

alter table bookings add column if not exists starts_at timestamptz;
alter table bookings add column if not exists ends_at timestamptz;

alter table bookings drop constraint if exists bookings_status_check;
alter table bookings add constraint bookings_status_check check (status in (
    'PENDING','PENDING_PAYMENT','CONFIRMED','RESCHEDULE_REQUESTED','COMPLETED','CANCELLED','REJECTED','NO_SHOW','DISPUTED'
));

create index if not exists idx_bookings_provider_window on bookings (provider_id, starts_at, ends_at) where is_deleted = false;
