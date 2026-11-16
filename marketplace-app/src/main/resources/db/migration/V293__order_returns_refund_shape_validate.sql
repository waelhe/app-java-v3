-- The paired VALIDATE for V287's NOT VALID constraint (the repository's
-- V57/V66 precedent: the ACCESS EXCLUSIVE of the ADD is kept brief by
-- NOT VALID, and the actual scan runs here under SHARE UPDATE EXCLUSIVE
-- — reads and writes continue; only concurrent DDL on the same table
-- waits. PostgreSQL's documented two-step for CHECK constraints on
-- existing tables).

ALTER TABLE order_returns VALIDATE CONSTRAINT order_returns_refund_shape_chk;
