/*
 * Backfill user_id for existing provider_profiles.
 *
 * Matches on display_name between provider_profiles and users WHERE users.role = 'PROVIDER'.
 * Only updates rows where exactly one matching PROVIDER user exists.
 * Rows with no match or multiple matches remain NULL and must be linked manually.
 */
UPDATE provider_profiles pp
SET user_id = u.id
FROM users u
WHERE pp.user_id IS NULL
  AND u.display_name = pp.display_name
  AND u.role = 'PROVIDER'
  AND (SELECT COUNT(*) FROM users u2 WHERE u2.display_name = pp.display_name AND u2.role = 'PROVIDER') = 1;
