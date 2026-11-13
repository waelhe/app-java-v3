-- G3 (the 2026-11-10 adversarial review's owner-gate batch 6a — the
-- documented recommendation's media purge, executed by the owner's
-- standing order adopting it): the erasure tombstone state for the
-- media module's two status vocabularies. The account-erasure flow's
-- media adapter (MediaPurgeAdapter — the AuthoredContentPurgePort
-- contract the eleven free-text adapters already carry) writes
-- status = 'PURGED' on the erased subject's rows: a state no read rail
-- presigns from (every rail filters status = 'UPLOADED'), closing the
-- forever-renewed signed links for erased subjects (the ledger's own
-- finding G3: «الوسائط بلا ساق محو أصلاً» — the pseudonymization plan
-- treated media as export-only data with no purge gate anywhere).
--
-- The two inline CHECKs the V32/V87 waves pinned widen with the member:
--   * media_assets_status_check (V32's CREATE TABLE inline CHECK),
--   * review_media_status_check (V87's, the same vocabulary).
-- The enum stays the authority (MediaAssetStatus.PURGED — terminal,
-- written only by the purge adapter's native SQL); the CHECKs are the
-- backstop (the discipline that governed V44, V56, V103, V296, V298).
--
-- The route is the house precedent verbatim (V187's own header
-- prescribed it; V290/V296/V298 executed it): DROP + ADD NOT VALID
-- here, the VALIDATE in V301 under its own SHARE UPDATE EXCLUSIVE scan
-- (the V68/V69 + V87 measured lesson — never share a transaction with
-- the DROP/ADD: that scan would run under ACCESS EXCLUSIVE and block
-- live traffic).
--
-- The numbering rides ABOVE the in-flight waves' unmerged pairs
-- (A2's V296/V297, A1's V298/V299) per the B1 shared-number-space
-- discipline: gaps are benign, renumbering is not; the waves merge in
-- creation order.
--
-- Both CHECKs validate clean by construction: the live rows carry only
-- PENDING_UPLOAD/UPLOADED — PURGED enters with this wave's purge
-- adapter alone.

ALTER TABLE media_assets DROP CONSTRAINT media_assets_status_check;
ALTER TABLE media_assets
    ADD CONSTRAINT media_assets_status_check
        CHECK (status IN ('PENDING_UPLOAD', 'UPLOADED', 'PURGED')) NOT VALID;

ALTER TABLE review_media DROP CONSTRAINT review_media_status_check;
ALTER TABLE review_media
    ADD CONSTRAINT review_media_status_check
        CHECK (status IN ('PENDING_UPLOAD', 'UPLOADED', 'PURGED')) NOT VALID;
