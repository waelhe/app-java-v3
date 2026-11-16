-- ADR-0003 (the review round on the cascade): the push channel
-- widening's validation — the V93/V94/V111/V115/V164/V165 split
-- verbatim (the validation scan rides its OWN migration under SHARE
-- UPDATE EXCLUSIVE alone).
--
-- V185 adds the channel guard's PUSH leg NOT VALID: the ADD inside
-- V185's transaction would otherwise hold the ACCESS EXCLUSIVE lock
-- through the validation scan and block the ordinary reads and writes
-- of the delivery gate every notification consults (the V180 header's
-- own blue/green deploy-window rationale for this table). Every stored
-- row satisfies the widened membership by construction: DB/EMAIL/WS
-- are all still members of the four-value guard.

ALTER TABLE notification_preferences VALIDATE CONSTRAINT notification_preferences_channel_check;
