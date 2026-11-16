-- B-17 (compliance plan C.9 — the trust & verification sidecar): the
-- eleventh and twelfth notification types join the DB-side membership
-- guard (the V53/V55/V62/V63/V65/V74/V93/V151 family, one type later
-- each): NotificationType stays the single source of truth for the Java
-- side, this constraint for the SQL side (the D-N7 discipline).
--
-- MEMBERSHIP_VERIFIED — the verified member's own grant notification
-- (the trust verdict's journey arrival; the recipient arrives resolved
-- at the source by the community publisher).
-- REPORT_RESOLVED — the reporter's adjudication notification (every
-- resolve outcome fires it; distinct from the author's
-- CONTENT_MODERATED alert, which is the hide fact alone).
--
-- NOT VALID here; VALIDATE in V159 — the V93/V94/V151/V152 split
-- verbatim (inside one transaction the DROP/ADD statements' ACCESS
-- EXCLUSIVE lock would still be held during the validation scan,
-- blocking the ordinary reads and writes of the delivery gate every
-- notification consults).

ALTER TABLE notification_preferences DROP CONSTRAINT IF EXISTS notification_preferences_type_check;

ALTER TABLE notification_preferences
    ADD CONSTRAINT notification_preferences_type_check
    CHECK (type IN ('BOOKING_CREATED', 'PAYMENT_STATE', 'LEAD_RECEIVED',
                    'SAVED_SEARCH_MATCH', 'POST_COMMENTED',
                    'NEW_LISTING_IN_NEIGHBORHOOD', 'CONTENT_MODERATED',
                    'POST_REACTED', 'FOLLOWED_PROVIDER_NEW_LISTING',
                    'MESSAGE_RECEIVED', 'MEMBERSHIP_VERIFIED',
                    'REPORT_RESOLVED')) NOT VALID;
