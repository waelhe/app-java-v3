-- ADR-0009 (plan D-09 closure — the ADR-0004 dispute deferral): the
-- disputes module's subject generalization — the booking (the V20
-- original) gains the loan as a second subject, through the dedicated
-- module-contract seam the ADR named (the shared events + the
-- LoanPartyProvider contract — the BookingParticipantProvider twin).
--
-- The row-level twin of the event contract: exactly one of booking_id /
-- loan_id is set, matching the subject_type. The booking_id NOT NULL drop
-- is the additive widening the subject generalization requires (the V172
-- origin-rewrite pattern: the pairing CHECK is the guard now).
--
-- The loans table's EXCLUDE constraint gains DISPUTED in its live set —
-- the freeze must hold the PERIOD too: the item is with the borrower, so
-- an overlapping request during the dispute is refused exactly as it is
-- for the other live states (the constraint rebuild is a same-migration
-- DROP/ADD pair — no VALIDATE semantics apply to exclusion constraints;
-- the scan is the index rebuild itself).
--
-- NOT VALID here; VALIDATE in V178 — the V166/V167 and V74/V75 precedent
-- verbatim (inside one transaction the ADD's ACCESS EXCLUSIVE lock would
-- still be held during the validation scan, blocking the ordinary reads
-- and writes of the dispute surfaces).
--
-- The Envers mirrors follow the V38/V172 pattern: the audit table gains
-- the same columns, nullable, no defaults (historical revisions keep
-- their shape).

ALTER TABLE disputes
    ADD COLUMN loan_id UUID,
    ADD COLUMN subject_type VARCHAR(20) NOT NULL DEFAULT 'BOOKING';

ALTER TABLE disputes
    ALTER COLUMN booking_id DROP NOT NULL;

ALTER TABLE disputes
    ADD CONSTRAINT disputes_subject_type_check
    CHECK (subject_type IN ('BOOKING', 'LOAN')) NOT VALID;

ALTER TABLE disputes
    ADD CONSTRAINT disputes_subject_pairing_chk CHECK (
        (subject_type = 'BOOKING' AND booking_id IS NOT NULL AND loan_id IS NULL)
     OR (subject_type = 'LOAN'    AND booking_id IS NULL     AND loan_id IS NOT NULL)
    ) NOT VALID;

CREATE INDEX idx_disputes_loan ON disputes(loan_id);

-- The freeze holds the period: DISPUTED joins the live set (ADR-0009).
ALTER TABLE loans DROP CONSTRAINT loans_live_period_exclusive;

ALTER TABLE loans ADD CONSTRAINT loans_live_period_exclusive
    EXCLUDE USING gist (
        product_id WITH =,
        tstzrange(start_at, end_at, '[)') WITH &&
    ) WHERE (status IN ('APPROVED', 'ACTIVE', 'RETURN_REQUESTED', 'DISPUTED'));

-- The Envers mirrors (the V38/V172 pattern).
ALTER TABLE disputes_aud
    ADD COLUMN loan_id UUID,
    ADD COLUMN subject_type VARCHAR(20);
