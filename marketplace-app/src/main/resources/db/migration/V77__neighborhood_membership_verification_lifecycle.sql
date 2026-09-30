-- G-N2 remains closed for external providers. This migration only expands the existing membership
-- lifecycle and preserves every current member as UNVERIFIED, so established community access stays intact.
ALTER TABLE neighborhood_memberships
    DROP CONSTRAINT chk_neighborhood_memberships_verification_state;

UPDATE neighborhood_memberships
SET verification_state = 'UNVERIFIED'
WHERE verification_state = 'SELF_DECLARED';

ALTER TABLE neighborhood_memberships
    ADD CONSTRAINT chk_neighborhood_memberships_verification_state
    CHECK (verification_state IN ('UNVERIFIED', 'PENDING', 'VERIFIED', 'REJECTED')) NOT VALID;

ALTER TABLE neighborhood_memberships
    VALIDATE CONSTRAINT chk_neighborhood_memberships_verification_state;
