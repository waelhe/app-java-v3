-- V218: connect the direct-delivery machine to confirmed marketplace orders.
-- The seller pickup point is privately stored on the provider profile; only the
-- provider's authenticated self-service writes it. Delivery consumes it through a shared port.
ALTER TABLE provider_profiles
    ADD COLUMN fulfillment_address VARCHAR(250),
    ADD COLUMN fulfillment_latitude DOUBLE PRECISION,
    ADD COLUMN fulfillment_longitude DOUBLE PRECISION;

ALTER TABLE provider_profiles
    ADD CONSTRAINT provider_fulfillment_location_pair_chk CHECK (
        (fulfillment_address IS NULL AND fulfillment_latitude IS NULL AND fulfillment_longitude IS NULL)
        OR (fulfillment_address IS NOT NULL AND length(btrim(fulfillment_address)) > 0
            AND fulfillment_latitude IS NOT NULL AND fulfillment_latitude BETWEEN -90 AND 90
            AND fulfillment_longitude IS NOT NULL AND fulfillment_longitude BETWEEN -180 AND 180)
    );

ALTER TABLE provider_profiles_aud
    ADD COLUMN fulfillment_address VARCHAR(250),
    ADD COLUMN fulfillment_latitude DOUBLE PRECISION,
    ADD COLUMN fulfillment_longitude DOUBLE PRECISION;

ALTER TABLE delivery_quotes
    ADD COLUMN order_id UUID REFERENCES orders(id);

ALTER TABLE delivery_quotes_aud
    ADD COLUMN order_id UUID;

ALTER TABLE delivery_requests
    ADD COLUMN order_id UUID REFERENCES orders(id);

ALTER TABLE delivery_requests_aud
    ADD COLUMN order_id UUID;

ALTER TABLE orders
    ADD COLUMN delivery_request_id UUID UNIQUE REFERENCES delivery_requests(id);

ALTER TABLE orders_aud
    ADD COLUMN delivery_request_id UUID;

CREATE INDEX idx_delivery_quotes_order
    ON delivery_quotes(order_id, created_at DESC)
    WHERE order_id IS NOT NULL;

CREATE INDEX idx_delivery_requests_order_status
    ON delivery_requests(order_id, status, created_at DESC)
    WHERE order_id IS NOT NULL;
