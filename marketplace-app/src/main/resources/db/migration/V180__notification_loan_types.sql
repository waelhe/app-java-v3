-- ADR-0009 (plan D-09 closure): the loan's dispute-cycle and settlement
-- notification types join the DB-side membership guard (the V53/V55/V62/
-- V63/V65/V74/V93/V110/V114/V151/V158/V164 family, the D-N7 discipline:
-- NotificationType stays the single source of truth for the Java side,
-- this constraint for the SQL side).
--
-- LOAN_DISPUTED — both parties' freeze receipt (the dispute opened on the
--                 loan; the freeze is real since ADR-0009).
-- LOAN_DISPUTE_RESOLVED — both parties' release receipt (the loan resumed;
--                 a REFUND_CONSUMER resolution terminates the loan instead,
--                 so the existing LOAN_CANCELLED carries that outcome).
-- LOAN_CLOSED — the settlement's terminal receipt for both parties
--                 (including the computed late-fee adjustment); the
--                 LoanClosedEvent listener was the A-03 gap this closure
--                 repairs (the event had no listener since ADR-0004).
--
-- This widening ALSO closes the measured stage-8 gap: the three
-- decision-gate types ADR-0004 added to the Java enum (LOAN_REQUESTED /
-- LOAN_APPROVED / LOAN_CANCELLED) never joined the surviving DB-side
-- guard (V174 widened no type CHECK — V164's sixteen remained the
-- constraint), so a preference row for any of them violated the CHECK at
-- flush time: the exact defect class V110's header documents. The union
-- below carries all twenty-two values the enum now names.
--
-- NOT VALID here; VALIDATE in V181 — the V93/V94/V151/V152 and V164/V165
-- split verbatim (inside one transaction the DROP/ADD statements' ACCESS
-- EXCLUSIVE lock would still be held during the validation scan, blocking
-- the ordinary reads and writes of the delivery gate every notification
-- consults).

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
                    'REPORT_RESOLVED',
                    'LOAN_REQUESTED', 'LOAN_APPROVED', 'LOAN_CANCELLED',
                    'LOAN_DISPUTED', 'LOAN_DISPUTE_RESOLVED', 'LOAN_CLOSED')) NOT VALID;
