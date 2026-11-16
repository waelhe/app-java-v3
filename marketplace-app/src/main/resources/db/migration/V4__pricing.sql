-- Pricing rules table
create table if not exists pricing_rules (
    id              uuid primary key,
    name            varchar(200) not null,
    category        varchar(100),
    tax_rate        numeric(5,4) not null default 0.1500 check (tax_rate >= 0 and tax_rate <= 1),
    discount_pct    numeric(5,4) not null default 0 check (discount_pct >= 0 and discount_pct <= 1),
    active          boolean not null default true,
    is_deleted      boolean not null default false,
    version         bigint not null default 0,
    created_by      varchar(200),
    created_at      timestamptz not null default now(),
    updated_by      varchar(200),
    updated_at      timestamptz not null default now()
);

create index idx_pricing_category on pricing_rules (category) where is_deleted = false and active = true;