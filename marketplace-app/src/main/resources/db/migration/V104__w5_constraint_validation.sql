-- W5 (yelp-level plan §5 — the ads & billing wave): the VALIDATE half of
-- the pair. Every CHECK in V100 landed NOT VALID (the V44 shape); each
-- VALIDATE below runs under its OWN statement's SHARE UPDATE EXCLUSIVE
-- alone — the V68/V69 + V87 measured lesson (never share a transaction
-- with the ADD that preceded it: that scan would run under ACCESS
-- EXCLUSIVE and block live traffic).
--
-- Every constraint here validates clean by construction:
--   * the three new tables are empty at migration time (V100 created
--     them this same deploy);
--   * payment_intents' origin checks answer for every live row through
--     the DEFAULT 'BOOKING' + NOT NULL booking_id + NULL ad_campaign_id
--     V100 just materialized (the V85 exact precedent: "no backfill
--     statement, no existing reader changes behavior");
--   * ledger_entries' closed set passes because the live values are the
--     three members of LedgerEntryType's pre-W5 vocabulary — the fourth
--     (AD_DEBIT) only enters with this wave's runtime.

ALTER TABLE ad_campaigns VALIDATE CONSTRAINT chk_ad_campaigns_budget;
ALTER TABLE ad_campaigns VALIDATE CONSTRAINT chk_ad_campaigns_prices;
ALTER TABLE ad_campaigns VALIDATE CONSTRAINT chk_ad_campaigns_consumed;
ALTER TABLE ad_campaigns VALIDATE CONSTRAINT chk_ad_campaigns_status;
ALTER TABLE ad_campaigns VALIDATE CONSTRAINT chk_ad_campaigns_duration;

ALTER TABLE ad_billing_charges VALIDATE CONSTRAINT chk_ad_billing_charges_counts;
ALTER TABLE ad_billing_charges VALIDATE CONSTRAINT chk_ad_billing_charges_amount;
ALTER TABLE ad_billing_charges VALIDATE CONSTRAINT chk_ad_billing_charges_window;

ALTER TABLE ad_clicks_daily VALIDATE CONSTRAINT chk_ad_clicks_daily_count;

ALTER TABLE payment_intents VALIDATE CONSTRAINT chk_payment_intents_origin;
ALTER TABLE payment_intents VALIDATE CONSTRAINT ck_payment_intents_origin_pairing;

ALTER TABLE ledger_entries VALIDATE CONSTRAINT chk_ledger_entries_entry_type;
