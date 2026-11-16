-- L36 (realestate systems plan §5 — agent/office pages): the validation
-- half of the provider persona CHECK — split out of V56 by the CodeRabbit
-- round-1 adoption (Squawk constraint-missing-not-valid): ADD CONSTRAINT
-- ... NOT VALID holds its ACCESS EXCLUSIVE lock until the V56 transaction
-- commits, so the validation scan must run in a SEPARATE transaction to
-- actually take VALIDATE's own SHARE UPDATE EXCLUSIVE lock (reads keep
-- flowing during the scan). Single statement, own transaction: re-runnable
-- after repair (validating an already-valid constraint is a no-op).

ALTER TABLE provider_profiles VALIDATE CONSTRAINT chk_provider_profiles_actor_type;
