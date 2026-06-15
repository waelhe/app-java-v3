alter table payment_intents drop constraint if exists payment_intents_status_check;
alter table payment_intents add constraint payment_intents_status_check
    check (status in ('CREATED','PROCESSING','SUCCEEDED','FAILED','CANCELLED','REFUNDED','PARTIALLY_REFUNDED'));

alter table payments drop constraint if exists payments_status_check;
alter table payments add constraint payments_status_check
    check (status in ('PENDING','COMPLETED','FAILED','REFUNDED','PARTIALLY_REFUNDED'));

alter table payment_intents add column if not exists refunded_amount_cents bigint not null default 0;
alter table payments add column if not exists refunded_amount_cents bigint not null default 0;
