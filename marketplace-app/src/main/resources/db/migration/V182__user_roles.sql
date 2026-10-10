-- D-03 (community platform execution plan Stage 1): the multi-role account
-- model. One account may hold SEVERAL roles at once (a neighbor who is both
-- a consumer and a shop owner). The pre-existing `users.role` column stays
-- the account's PRIMARY role — the compatibility mirror every read surface
-- and pre-existing test speaks — while `user_roles` is the authoritative
-- SET the operative authorization is minted from (the login-side
-- auth_authorities projection, jwtTokenCustomizer's roles claim).
--
-- Migration safety (the Stage-1 gate: "existing accounts keep their access,
-- no privilege escalation"):
--   * every account gains exactly the roles it ALREADY holds — the union of
--     its users.role mirror and its ROLE_* authority rows; nothing more;
--   * the grant metadata records the backfill source, so the provenance of
--     every pre-existing role stays auditable;
--   * the migration is idempotent (IF NOT EXISTS + ON CONFLICT DO NOTHING).
--
-- Envers audit mirror included (the V24 pattern — ddl-auto=none): every
-- grant/revoke/replace lands in user_roles_aud with its actor attribution.

create table if not exists user_roles (
    id          uuid primary key,
    user_id     uuid not null references users (id) on delete cascade,
    role        varchar(30) not null check (role in ('CONSUMER','PROVIDER','ADMIN')),
    granted_at  timestamptz not null default now(),
    granted_by  varchar(200) not null default 'SYSTEM',
    source      varchar(40) not null default 'ADMIN_GRANT',
    is_deleted  boolean not null default false,
    version     bigint not null default 0,
    created_by  varchar(200),
    created_at  timestamptz not null default now(),
    updated_by  varchar(200),
    updated_at  timestamptz not null default now()
);

-- The partial unique index (the house pattern — idx_users_role): only LIVE
-- rows participate, so a soft-deleted grant never blocks a legitimate
-- re-grant, and the backfill's ON CONFLICT targets this index.
create unique index if not exists uq_user_roles_user_role
    on user_roles (user_id, role) where is_deleted = false;
create index if not exists ix_user_roles_user_id on user_roles (user_id);

-- Envers audit mirror (REVTYPE: 0 = ADD, 1 = MOD, 2 = DEL — the V24 shape).
create table if not exists user_roles_aud (
    id          uuid not null,
    rev         integer not null,
    revtype     smallint,
    user_id     uuid,
    role        varchar(30),
    granted_at  timestamptz,
    granted_by  varchar(200),
    source      varchar(40),
    version     bigint,
    created_by  varchar(200),
    created_at  timestamptz,
    updated_by  varchar(200),
    updated_at  timestamptz,
    is_deleted  boolean,
    primary key (id, rev)
);

-- Backfill 1 — the domain mirror: every account keeps exactly the role it
-- already has on users.role. gen_random_uuid() is PostgreSQL-core since 13
-- (no extension needed; the platform runs PostgreSQL 18).
insert into user_roles (id, user_id, role, granted_at, granted_by, source)
select gen_random_uuid(), u.id, u.role::text, now(), 'SYSTEM', 'LEGACY_MIRROR'
from users u
on conflict (user_id, role) where is_deleted = false do nothing;

-- Backfill 2 — the login-side projection: a drifted ROLE_* authority row
-- (the pre-fix stock the S2/N4/N6 javadoc documents) must not vanish from
-- the account's set — the union preserves every held authority. Unknown
-- ROLE_ names are skipped (not domain roles).
insert into user_roles (id, user_id, role, granted_at, granted_by, source)
select gen_random_uuid(), u.id, substring(a.authority from 6), now(), 'SYSTEM', 'LEGACY_AUTHORITIES'
from auth_authorities a
join users u on u.subject = a.username
where a.authority like 'ROLE\_%'
  and substring(a.authority from 6) in ('CONSUMER','PROVIDER','ADMIN')
on conflict (user_id, role) where is_deleted = false do nothing;
