-- V227 — the quoted-path partial unique index, built CONCURRENTLY in its
-- own non-transactional migration (the populated-table discipline — the
-- V212 precedent verbatim): one delivery request per QUOTE (the #604
-- direct/manual path's idempotency law), while checkout-created legs
-- carry no quote (V224 dropped the inline NOT NULL unique; this index
-- re-establishes the law exactly where a quote exists).
CREATE UNIQUE INDEX CONCURRENTLY uq_delivery_requests_quote
    ON delivery_requests(quote_id) WHERE quote_id IS NOT NULL;
