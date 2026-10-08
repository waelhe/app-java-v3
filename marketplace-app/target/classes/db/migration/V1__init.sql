-- Users table with audit columns, optimistic locking, and soft delete
create table if not exists users (
    id              uuid primary key,
    subject         varchar(200) not null unique,
    email           varchar(320),
    display_name    varchar(200),
    role            varchar(30) not null check (role in ('CONSUMER','PROVIDER','ADMIN')),
    is_deleted      boolean not null default false,
    version         bigint not null default 0,
    created_by      varchar(200),
    created_at      timestamptz not null default now(),
    updated_by      varchar(200),
    updated_at      timestamptz not null default now()
);

create index idx_users_email on users (email) where email is not null;
create index idx_users_role on users (role) where is_deleted = false;