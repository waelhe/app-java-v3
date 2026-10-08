-- The union widening (2026-10-08, measured on the merged tree 7bbed50a):
-- the two parallel tracks each widened notification_preferences' type CHECK
-- on its own Flyway range — Track A to its thirteen (V110 BOOKING_CONFIRMED,
-- V114 the three order types), Track B to its twelve (V151 MESSAGE_RECEIVED,
-- V158 MEMBERSHIP_VERIFIED + REPORT_RESOLVED). Flyway applies migrations in
-- version order, so the constraint that SURVIVES on a fresh database — and
-- on the production chain, which has not applied V150+ yet — is V158's:
-- B's twelve alone. The union's NotificationType carries SIXTEEN: a stored
-- BOOKING_CONFIRMED or ORDER_* preference would violate the surviving
-- constraint at flush time (a 500 the mock-repository unit tests can never
-- see — the exact class the V110 header documented for the pre-A-03 nine).
--
-- This pair sits ABOVE every current type-CHECK migration (V158/V159 are
-- the last), so it survives EVERY application order: a fresh CI database
-- (V1..V161 then V162/V163 last), the production chain's ordered catch-up,
-- and any out-of-order late arrival — the version number itself is the
-- correctness mechanism, no ordering assumption rides on it. An earlier
-- same-purpose pair at V118/V119 (commit 7bbed50a) was measured INSUFFICIENT
-- for the ordered paths — V158 superseded it at its own version position —
-- and is retired by this commit in favor of the position-correct fix.
--
-- The widened CHECK keeps the D-N7 discipline (every enumerated column
-- carries its DB-level membership guard — NotificationType stays the single
-- source of truth for the Java side, this constraint for the SQL side) in
-- the V44 locking shape: NOT VALID (metadata-only, enforced for new rows
-- immediately). The VALIDATE step rides its OWN migration (V163) so its scan
-- runs under SHARE UPDATE EXCLUSIVE alone — the V66/V75 and V110/V111
-- precedent verbatim (constraint-missing-not-valid): inside one transaction
-- the DROP/ADD statements' ACCESS EXCLUSIVE lock would still be held during
-- the validation scan, blocking ordinary reads and writes for the scan's
-- duration.
--
-- Range registration (the frozen ledger §2, additive-only): V162/V163 are
-- the union's cross-track fix — consumed from Track B's V150-V189 allocation
-- by a documented ledger row, because the surviving-constraint contract is
-- exactly the ledger's cross-track domain and the fix must sit above
-- V158/V159 (Track A's V110-V149 allocation cannot serve that position).

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
