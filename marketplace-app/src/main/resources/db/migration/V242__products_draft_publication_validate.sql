-- Validate V240's widened publication vocabulary in its own migration: the
-- ACCESS EXCLUSIVE lock the DROP/ADD pair takes is never held through the
-- validation scan (the review round on the C1 wave; V167's discipline
-- verbatim). Existing rows already satisfy the widened CHECK — the legacy
-- vocabulary is a subset.
ALTER TABLE products VALIDATE CONSTRAINT products_status_chk;
