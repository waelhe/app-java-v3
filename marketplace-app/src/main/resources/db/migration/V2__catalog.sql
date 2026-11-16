-- Provider listings table
create table if not exists provider_listings (
    id              uuid primary key,
    provider_id     uuid not null references users(id),
    title           varchar(200) not null,
    description     text,
    category        varchar(100) not null,
    price_cents     bigint not null check (price_cents >= 0),
    currency       varchar(3) not null default 'SAR',
    status          varchar(20) not null default 'DRAFT' check (status in ('DRAFT','ACTIVE','PAUSED','ARCHIVED')),
    is_deleted      boolean not null default false,
    version         bigint not null default 0,
    created_by      varchar(200),
    created_at      timestamptz not null default now(),
    updated_by      varchar(200),
    updated_at      timestamptz not null default now()
);

create index idx_listings_provider on provider_listings (provider_id) where is_deleted = false;
create index idx_listings_category on provider_listings (category) where is_deleted = false and status = 'ACTIVE';
create index idx_listings_status on provider_listings (status) where is_deleted = false;