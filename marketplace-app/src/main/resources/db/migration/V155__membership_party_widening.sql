-- B-13 (compliance plan C.3 — the owner's 2026-10-07 ruling, recorded
-- verbatim: «المؤسسات فيها جزء من المجتمع»): the membership machine's
-- party widening — the COMMUNITY-side generalization's DB half. An
-- institution joins the SAME machine a user joins: ONE anchor per row
-- (user OR institution), the SAME verification lifecycle
-- (MembershipVerificationState untouched), the SAME one-active-
-- membership-per-party discipline (the V60/V47 partial-unique
-- mechanism, scoped per party). No membership machinery is duplicated
-- anywhere (the ruling's own «بلا تكرار أي آلية عضوية خارج community»).
--
-- The CODE half (NeighborhoodMembership's anchor field + the
-- institution join/switch/leave commands + the repository's
-- institution-scoped reads + the L46 bridge's member resolution) rides
-- CR-6: the existing files are Track A's garden. This migration is
-- designed so the CURRENT app boots UNCHANGED against it — the new
-- column is nullable and unmapped (the entity simply never writes it
-- until CR-6 lands), the old indexes keep their exact semantics, and
-- every guard is additive:
--   * user_id becomes nullable — the existing rows and the current
--     writer are unaffected (the entity always sets it; the NOT NULL
--     was the single-party assumption, which is exactly what widens).
--   * The one-anchor CHECK (the V44 NOT VALID + VALIDATE locking shape)
--     admits every existing row by construction (each carries user_id).
--   * The user's V60 partial unique stays byte-identical: institution-
--     anchored rows carry NULL user_id, and PostgreSQL unique indexes
--     treat NULLs as distinct — no interaction, ever.
--   * The institution's own partial unique is the V60 twin per party.
--   * The _aud mirror widens in place (the V24/V33 discipline: a base
--     column without its _aud twin breaks audit INSERTs silently —
--     nullable in the mirror, exactly like V150's messages twin).
--
-- Numbering: V155 — Track B's range (V150-V189); V153/V154 are this
-- line's jobs/institutions migrations.

ALTER TABLE neighborhood_memberships
    ADD COLUMN IF NOT EXISTS institution_id UUID;

-- The single-party assumption widens: an institution-anchored row
-- carries NULL user_id (exactly-one-anchor below holds the shape).
ALTER TABLE neighborhood_memberships
    ALTER COLUMN user_id DROP NOT NULL;

-- Exactly ONE anchor per row — the party discipline (the CHECK admits
-- every existing row by construction: each carries user_id).
ALTER TABLE neighborhood_memberships
    ADD CONSTRAINT chk_neighborhood_memberships_one_anchor
    CHECK (
        (user_id IS NOT NULL AND institution_id IS NULL)
        OR
        (user_id IS NULL AND institution_id IS NOT NULL)
    ) NOT VALID;
ALTER TABLE neighborhood_memberships
    VALIDATE CONSTRAINT chk_neighborhood_memberships_one_anchor;

-- The institution's one-active-membership index (the V60 twin per
-- party): a left membership releases the slot; the switch's
-- delete-then-insert rides the service's flush-ordering discipline.
CREATE UNIQUE INDEX uq_neighborhood_memberships_institution
    ON neighborhood_memberships (institution_id)
    WHERE is_deleted = FALSE AND institution_id IS NOT NULL;

-- The Envers mirror widens together (the V24/V33 discipline).
ALTER TABLE neighborhood_memberships_aud
    ADD COLUMN IF NOT EXISTS institution_id UUID;
