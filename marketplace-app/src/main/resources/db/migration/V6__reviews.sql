-- Reviews table
create table if not exists reviews (
    id              uuid primary key,
    booking_id      uuid not null references bookings(id),
    reviewer_id     uuid not null references users(id),
    provider_id     uuid not null references users(id),
    rating          smallint not null check (rating >= 1 and rating <= 5),
    comment         text,
    is_deleted      boolean not null default false,
    version         bigint not null default 0,
    created_by      varchar(200),
    created_at      timestamptz not null default now(),
    updated_by      varchar(200),
    updated_at      timestamptz not null default now()
);

create unique index if not exists uq_review_booking_active
    on reviews (booking_id) where is_deleted = false;
create index idx_reviews_provider on reviews (provider_id) where is_deleted = false;
create index idx_reviews_reviewer on reviews (reviewer_id) where is_deleted = false;
create index idx_reviews_rating on reviews (rating) where is_deleted = false;
