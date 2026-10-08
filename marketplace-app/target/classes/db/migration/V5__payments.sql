-- Payment intents and payments tables
create table if not exists payment_intents (
    id                  uuid primary key,
    booking_id          uuid not null references bookings(id),
    consumer_id         uuid not null references users(id),
    amount_cents        bigint not null check (amount_cents > 0),
    currency           varchar(3) not null default 'SAR',
    status              varchar(20) not null default 'CREATED' check (status in ('CREATED','PROCESSING','SUCCEEDED','FAILED','CANCELLED')),
    idempotency_key     varchar(64) unique,
    is_deleted          boolean not null default false,
    version             bigint not null default 0,
    created_by          varchar(200),
    created_at          timestamptz not null default now(),
    updated_by          varchar(200),
    updated_at          timestamptz not null default now()
);

create table if not exists payments (
    id                  uuid primary key,
    payment_intent_id   uuid not null references payment_intents(id),
    amount_cents        bigint not null check (amount_cents > 0),
    status              varchar(20) not null default 'PENDING' check (status in ('PENDING','COMPLETED','FAILED','REFUNDED')),
    external_id         varchar(200),
    is_deleted          boolean not null default false,
    version             bigint not null default 0,
    created_by          varchar(200),
    created_at          timestamptz not null default now(),
    updated_by          varchar(200),
    updated_at          timestamptz not null default now()
);

create index idx_payment_intents_booking on payment_intents (booking_id) where is_deleted = false;
create index idx_payment_intents_consumer on payment_intents (consumer_id) where is_deleted = false;
create index idx_payment_intents_idempotency on payment_intents (idempotency_key) where idempotency_key is not null;
create index idx_payments_intent on payments (payment_intent_id) where is_deleted = false;