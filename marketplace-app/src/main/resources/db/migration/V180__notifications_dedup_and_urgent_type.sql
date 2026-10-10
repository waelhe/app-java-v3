-- Task 5-f (the discovery waves' measured repairs — the
-- events-without-consumers closure + the delivery-dedup ledger), three
-- independent facts in one migration:
--
-- (a) notifications.source_event_id — the delivery-dedup ledger column
--     (nullable UUID): the originating event's id once a delivery rides a
--     re-deliverable Modulith publication. The pre-ledger delivery paths
--     keep writing NULL (their events are structurally exactly-once per
--     recipient — the fan-out is done at the publisher, the
--     NewListingInNeighborhoodEvent bridge pattern) — the column is the
--     LEDGER for the paths whose registry entry can legitimately be
--     re-submitted. Its guard is the partial unique index
--     uq_notifications_source_event_once on (recipient_id, source_event_id)
--     WHERE source_event_id IS NOT NULL (the V93 provider_follow_alerts
--     ledger discipline: one row per (recipient, source event) EVER
--     delivered; a re-delivered publication can never duplicate). The
--     WHERE clause keeps the fifteen legacy delivery paths (and every
--     future NULL-birth path) outside the constraint entirely — NULLs never
--     collide in a unique index, the predicate only makes the intent
--     explicit and keeps the index as small as the ledger rows.
--     The Envers mirror gains the same nullable column (the V150
--     live-table-and-_aud-mirror-together discipline: a DEL revision row
--     carries only (id, rev, revtype), every mirror column stays nullable).
-- (b) the notification_preferences type CHECK widening to NINETEEN — the
--     V164 union's sixteen carried verbatim + the three new types
--     (URGENT_ALERT, DISPUTE_OPENED, DISPUTE_RESOLVED — the V93/V158
--     widening family, one header later): NotificationType stays the single
--     source of truth for the Java side, this constraint for the SQL side
--     (the D-N7 discipline). DISPUTE_OPENED/DISPUTE_RESOLVED serve the two
--     listeners this wave lands on the relocated shared/api dispute events;
--     URGENT_ALERT is registered STRUCTURALLY (the CMP-46/JT-10 delegated
--     alert's live surface is the discovery row through UrgentAlertsPort —
--     the per-neighborhood fan-out awaits the neighborhood-members port,
--     the next wave's send-volume decision; the type and this guard are its
--     ready seat, see NotificationType's javadoc).
--     The widened CHECK keeps the V44 locking shape: DROP IF EXISTS + ADD
--     NOT VALID (metadata-only, enforced for new rows immediately — the
--     V66/V75/V110/V111/V151/V158/V164 family verbatim). The VALIDATE step
--     rides its OWN migration (V181) so its scan runs under SHARE UPDATE
--     EXCLUSIVE alone: inside one transaction the DROP/ADD statements'
--     ACCESS EXCLUSIVE lock would still be held during the validation scan,
--     blocking ordinary reads and writes of the delivery gate every
--     notification consults (the V164 header's own explanation, adopted
--     verbatim).
-- (c) no checksum registration in this commit (the measured instruction:
--     migration-checksums.properties is the contested union file of the
--     parallel checksum task) — V180/V181 ride the out-of-order Flyway
--     configuration and are registered by the checksum-union task.

ALTER TABLE notifications ADD COLUMN source_event_id UUID NULL;

ALTER TABLE notifications_aud ADD COLUMN source_event_id UUID NULL;

CREATE UNIQUE INDEX uq_notifications_source_event_once
    ON notifications (recipient_id, source_event_id) WHERE source_event_id IS NOT NULL;

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
                    'URGENT_ALERT', 'DISPUTE_OPENED', 'DISPUTE_RESOLVED')) NOT VALID;
