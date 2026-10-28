-- CodeRabbit agent findings (2026-10-26T11:43 export), row 1 — the
-- zero-value return path: the frozen refund share may legitimately be ZERO
-- (a fully-discounted goods basket with no applicable tax — the goods gave
-- back nothing), and a completed zero-value return refunds NOTHING instead
-- of minting one minor unit out of the collected, nonrefundable shipping
-- charge. The shape law follows the entity's own freeze contract: APPROVED /
-- RECEIVED / COMPLETED carry a NON-NEGATIVE share; REQUESTED / REJECTED /
-- CANCELLED still carry none (V230's original law, the equality relaxed from
-- strictly-positive to non-negative). The Envers mirror carries no CHECK
-- (V230 built it plain — only the live table owns the shape law).

ALTER TABLE order_returns DROP CONSTRAINT order_returns_refund_shape_chk;

ALTER TABLE order_returns ADD CONSTRAINT order_returns_refund_shape_chk CHECK (
    (status IN ('REQUESTED', 'REJECTED', 'CANCELLED') AND refund_amount_minor IS NULL)
    OR (status IN ('APPROVED', 'RECEIVED', 'COMPLETED') AND refund_amount_minor >= 0)
);
