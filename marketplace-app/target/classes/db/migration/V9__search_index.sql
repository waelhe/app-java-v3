-- Search materialized view for fast listing searches
create materialized view if not exists mv_listing_search as
select
    pl.id,
    pl.provider_id,
    pl.title,
    pl.description,
    pl.category,
    pl.price_cents,
    pl.currency,
    pl.status,
    pl.created_at
from provider_listings pl
where pl.is_deleted = false and pl.status = 'ACTIVE';

create unique index idx_mv_listing_search_id on mv_listing_search (id);
create index idx_mv_listing_search_category on mv_listing_search (category);
create index idx_listing_search_title on provider_listings using gin (to_tsvector('simple', coalesce(title,'') || ' ' || coalesce(description,'')))
    where is_deleted = false and status = 'ACTIVE';