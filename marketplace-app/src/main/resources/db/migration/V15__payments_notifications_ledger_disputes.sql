-- Payment webhooks
create table if not exists payment_webhook_events (
    id                 uuid primary key,
    provider           varchar(40) not null,
    external_event_id  varchar(120) not null,
    payment_intent_id  uuid not null references payment_intents(id),
    event_type         varchar(80) not null,
    payload            text not null,
    is_deleted         boolean not null default false,
    version            bigint not null default 0,
    created_by         varchar(200),
    created_at         timestamptz not null default now(),
    updated_by         varchar(200),
    updated_at         timestamptz not null default now(),
    unique (provider, external_event_id)
);

-- Notifications
create table if not exists notifications (
    id             uuid primary key,
    recipient_id   uuid not null,
    channel        varchar(20) not null check (channel in ('EMAIL','SMS','PUSH','IN_APP')),
    subject        varchar(200) not null,
    body           text not null,
    read           boolean not null default false,
    is_deleted     boolean not null default false,
    version        bigint not null default 0,
    created_by     varchar(200),
    created_at     timestamptz not null default now(),
    updated_by     varchar(200),
    updated_at     timestamptz not null default now()
);
create index idx_notifications_recipient_read on notifications (recipient_id, read) where is_deleted = false;

-- Ledger and payouts
create table if not exists ledger_entries (
    id             uuid primary key,
    provider_id    uuid,
    source_id      uuid not null,
    type           varchar(32) not null check (type in ('BOOKING_PAYMENT','PLATFORM_COMMISSION','PROVIDER_EARNING','REFUND','PAYOUT')),
    amount_cents   bigint not null,
    currency       varchar(3) not null default 'SAR',
    is_deleted     boolean not null default false,
    version        bigint not null default 0,
    created_by     varchar(200),
    created_at     timestamptz not null default now(),
    updated_by     varchar(200),
    updated_at     timestamptz not null default now()
);
create index idx_ledger_entries_provider on ledger_entries (provider_id) where is_deleted = false;
create index idx_ledger_entries_source_type on ledger_entries (source_id, type) where is_deleted = false;

create table if not exists provider_balances (
    id               uuid primary key,
    provider_id      uuid not null unique,
    available_cents  bigint not null default 0,
    currency         varchar(3) not null default 'SAR',
    is_deleted       boolean not null default false,
    version          bigint not null default 0,
    created_by       varchar(200),
    created_at       timestamptz not null default now(),
    updated_by       varchar(200),
    updated_at       timestamptz not null default now()
);

create table if not exists settlements (
    id             uuid primary key,
    provider_id    uuid not null,
    amount_cents   bigint not null,
    status         varchar(20) not null default 'OPEN',
    is_deleted     boolean not null default false,
    version        bigint not null default 0,
    created_by     varchar(200),
    created_at     timestamptz not null default now(),
    updated_by     varchar(200),
    updated_at     timestamptz not null default now()
);

create table if not exists payouts (
    id             uuid primary key,
    provider_id    uuid not null,
    amount_cents   bigint not null check (amount_cents > 0),
    currency       varchar(3) not null default 'SAR',
    status         varchar(20) not null default 'REQUESTED',
    is_deleted     boolean not null default false,
    version        bigint not null default 0,
    created_by     varchar(200),
    created_at     timestamptz not null default now(),
    updated_by     varchar(200),
    updated_at     timestamptz not null default now()
);

-- Disputes and reports
create table if not exists disputes (
    id             uuid primary key,
    booking_id     uuid not null references bookings(id),
    opened_by      uuid not null references users(id),
    reason         varchar(500) not null,
    resolution     varchar(1000),
    status         varchar(32) not null default 'OPEN' check (status in ('OPEN','UNDER_REVIEW','RESOLVED','REJECTED')),
    is_deleted     boolean not null default false,
    version        bigint not null default 0,
    created_by     varchar(200),
    created_at     timestamptz not null default now(),
    updated_by     varchar(200),
    updated_at     timestamptz not null default now()
);
create index idx_disputes_booking on disputes (booking_id) where is_deleted = false;
create index idx_disputes_opened_by on disputes (opened_by) where is_deleted = false;

create table if not exists dispute_messages (
    id             uuid primary key,
    dispute_id     uuid not null references disputes(id),
    sender_id      uuid not null references users(id),
    message        text not null,
    is_deleted     boolean not null default false,
    version        bigint not null default 0,
    created_by     varchar(200),
    created_at     timestamptz not null default now(),
    updated_by     varchar(200),
    updated_at     timestamptz not null default now()
);
create index idx_dispute_messages_dispute on dispute_messages (dispute_id) where is_deleted = false;

create table if not exists reports (
    id             uuid primary key,
    reported_by    uuid not null references users(id),
    target_id      uuid not null,
    reason         varchar(500) not null,
    is_deleted     boolean not null default false,
    version        bigint not null default 0,
    created_by     varchar(200),
    created_at     timestamptz not null default now(),
    updated_by     varchar(200),
    updated_at     timestamptz not null default now()
);
