-- ADR-0009 (plan D-09 closure — the late-fee rules opened): the owner's
-- declared late policy joins the offer projection, and the loan freezes
-- its own copy at request time (the ADR-0002/0004 amount-source lesson:
-- the terms are the owner's own, never caller-supplied, frozen before any
-- computation). At settlement the close edge derives the lateness from the
-- stamped returned_at against the period's end (whole days, a partial day
-- rents the whole day — the offer's own feeFor ceiling semantics) and
-- prices it with the frozen rate; the computed adjustment rides the loan
-- row (Envers-audited) and is collected through the documented follow-up
-- settlement leg (the deposit's leg — ADR-0004 decision 1), not silently
-- modeled.
--
-- The CHECK constraints are inline like V172's stock checks: the columns
-- are NEW (every existing row receives the 0 default), so the validation
-- is instant and the NOT VALID split adds nothing here.
--
-- The Envers mirrors follow the V172 pattern: the same columns, nullable,
-- no defaults.

ALTER TABLE lending_offers
    ADD COLUMN late_fee_per_day_minor BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT lending_offers_late_fee_nonnegative_chk
        CHECK (late_fee_per_day_minor >= 0);

ALTER TABLE loans
    ADD COLUMN late_fee_per_day_minor BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN late_days INT NOT NULL DEFAULT 0,
    ADD COLUMN late_fee_minor BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT loans_late_fee_per_day_nonnegative_chk
        CHECK (late_fee_per_day_minor >= 0),
    ADD CONSTRAINT loans_late_fee_nonnegative_chk
        CHECK (late_fee_minor >= 0);

ALTER TABLE lending_offers_aud
    ADD COLUMN late_fee_per_day_minor BIGINT;

ALTER TABLE loans_aud
    ADD COLUMN late_fee_per_day_minor BIGINT,
    ADD COLUMN late_days INT,
    ADD COLUMN late_fee_minor BIGINT;
