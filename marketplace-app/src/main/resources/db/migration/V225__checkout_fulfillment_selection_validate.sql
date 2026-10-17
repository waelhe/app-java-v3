-- V225 — the validation half of V224's split (the V220/V221 precedent
-- verbatim): the three NOT VALID constraints from V224 validate here,
-- each under SHARE UPDATE EXCLUSIVE — ordinary reads and writes keep
-- flowing while PostgreSQL scans (the populated-table discipline: a
-- plain CHECK's ACCESS EXCLUSIVE scan belongs to nobody's deploy window).
ALTER TABLE orders VALIDATE CONSTRAINT orders_fulfillment_method_chk;
ALTER TABLE orders VALIDATE CONSTRAINT orders_fulfillment_shape_chk;
ALTER TABLE delivery_requests VALIDATE CONSTRAINT delivery_requests_origin_chk;
