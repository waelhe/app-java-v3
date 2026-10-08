-- Add BaseEntity columns missing from V14-V20 migrations
-- Required for @SoftDelete(columnName = "is_deleted"), @CreatedBy, @LastModifiedBy
-- These columns exist in V1-V7 but were omitted from later tables

-- V14: provider_profiles
alter table if exists provider_profiles
    add column if not exists is_deleted boolean not null default false,
    add column if not exists created_by varchar(200),
    add column if not exists updated_by varchar(200),
    alter column version set not null,
    alter column version set default 0;

-- V15: availability_slots
alter table if exists availability_slots
    add column if not exists is_deleted boolean not null default false,
    add column if not exists created_by varchar(200),
    add column if not exists updated_by varchar(200),
    alter column version set not null,
    alter column version set default 0;

-- V16: provider_availability_rules
alter table if exists provider_availability_rules
    add column if not exists is_deleted boolean not null default false,
    add column if not exists created_by varchar(200),
    add column if not exists updated_by varchar(200),
    alter column version set not null,
    alter column version set default 0;

-- V16: provider_time_off
alter table if exists provider_time_off
    add column if not exists is_deleted boolean not null default false,
    add column if not exists created_by varchar(200),
    add column if not exists updated_by varchar(200),
    alter column version set not null,
    alter column version set default 0;

-- V17: payment_webhook_events
alter table if exists payment_webhook_events
    add column if not exists is_deleted boolean not null default false,
    add column if not exists created_by varchar(200),
    add column if not exists updated_by varchar(200),
    alter column version set not null,
    alter column version set default 0;

-- V18: notifications
alter table if exists notifications
    add column if not exists is_deleted boolean not null default false,
    add column if not exists created_by varchar(200),
    add column if not exists updated_by varchar(200),
    alter column version set not null,
    alter column version set default 0;

-- V19: ledger_entries
alter table if exists ledger_entries
    add column if not exists is_deleted boolean not null default false,
    add column if not exists created_by varchar(200),
    add column if not exists updated_by varchar(200),
    alter column version set not null,
    alter column version set default 0;

-- V19: provider_balances
alter table if exists provider_balances
    add column if not exists is_deleted boolean not null default false,
    add column if not exists created_by varchar(200),
    add column if not exists updated_by varchar(200),
    alter column version set not null,
    alter column version set default 0;

-- V20: disputes
alter table if exists disputes
    add column if not exists is_deleted boolean not null default false,
    add column if not exists created_by varchar(200),
    add column if not exists updated_by varchar(200),
    alter column version set not null,
    alter column version set default 0;
