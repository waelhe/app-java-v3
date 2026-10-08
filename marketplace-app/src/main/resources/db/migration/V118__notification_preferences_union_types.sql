-- The union widening (2026-10-08, measured on the merged tree ee9578b0):
-- the two parallel tracks each widened notification_preferences' type CHECK
-- on its own Flyway range — Track A to its thirteen (V110 BOOKING_CONFIRMED,
-- V114 the three order types), Track B to its twelve (V151 MESSAGE_RECEIVED,
-- V158 MEMBERSHIP_VERIFIED + REPORT_RESOLVED). Flyway applies them in version
-- order, so the constraint that SURVIVES is V158's — B's twelve alone. The
-- union's NotificationType carries SIXTEEN: a stored BOOKING_CONFIRMED or
-- ORDER_* preference would violate the surviving constraint at flush time
-- (a 500 the mock-repository unit tests can never see — the exact class the
-- V110 header documented for the pre-A-03 nine).
--
-- The widened CHECK keeps the D-N7 discipline (every enumerated column
-- carries its DB-level membership guard — NotificationType stays the single
-- source of truth for the Java side, this constraint for the SQL side) in
-- the V44 locking shape: NOT VALID (metadata-only, enforced for new rows
-- immediately). The VALIDATE step rides its OWN migration (V119) so its scan
-- runs under SHARE UPDATE EXCLUSIVE alone — the V66/V75 and V110/V111
-- precedent verbatim (constraint-missing-not-valid): inside one transaction
-- the DROP/ADD statements' ACCESS EXCLUSIVE lock would still be held during
-- the validation scan, blocking ordinary reads and writes for the scan's
-- duration.
--
-- Track A's Flyway range (V110-V149, parallel execution plan §5.3): V118 is
-- its ninth consumption, and the out-of-order gate
-- (spring.flyway.out-of-order: true, the ledger §2 documented mechanism)
-- applies it even though B's V150..V161 already ran on main.

ALTER TABLE notification_preferences DROP CONSTRAINT IF EXISTS notification_preferences_type_check;

ALTER TABLE notification_preferences
    ADD CONSTRAINT notification_preferences_type_check
    CHECK (type IN ('BOOKING_CREATED', 'PAYMENT_STATE', 'LEAD_RECEIVED',
                    'SAVED_SEARCH_MATCH', 'POST_COMMENTED',
                    'NEW_LISTING_IN_NEIGHBORHOOD', 'CONTENT_MODERATED',
                    'POST_REACTED', 'FOLLOWED_PROVIDER_NEW_LISTING',
                    'BOOKING_CONFIRMED', 'ORDER_CONFIRMED',
                    'ORDER_FULFILLED', 'ORDER_CANCELLED',
                    'MESSAGE_RECEIVED', 'MEMBERSHIP_VERIFIED',
                    'REPORT_RESOLVED')) NOT VALID;
