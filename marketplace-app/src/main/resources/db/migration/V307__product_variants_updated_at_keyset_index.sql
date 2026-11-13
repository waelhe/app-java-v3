-- V304__product_variants_updated_at_keyset_index.sql
-- The 2026-11-07 #808 round-5 review (Minor · Performance &
-- Scalability): the variant scrolls' keyset sorts address
-- (updated_at, id) — the ADMIN population's
-- scrollAdminByUpdatedAtAsc/Desc (the parent-live gate) and the
-- public ACTIVE slice's scrollPublicByUpdatedAtAsc/Desc twins (the
-- two-row gate) — but no existing index carries that ordering (the
-- shelf's indexes address the seller's SKU namespace and the
-- combination key), so every page sorted the live population. ONE
-- partial b-tree serves all four scrolls exactly: the is_deleted =
-- FALSE predicate rides the soft-delete filter every scroll already
-- constrains, and the DESC pair walks the same tree backwards (the
-- executor's own bidirectional b-tree scan — the V306 precedent's
-- single-index-per-direction-pair law). Non-concurrent (Flyway's
-- transactional DDL — the V301/V306 precedent; the CONCURRENTLY
-- exceptions stay reserved for locks-conflicting online changes).
CREATE INDEX IF NOT EXISTS idx_product_variants_updated_at_id_keyset
    ON product_variants (updated_at, id)
    WHERE is_deleted = FALSE;
