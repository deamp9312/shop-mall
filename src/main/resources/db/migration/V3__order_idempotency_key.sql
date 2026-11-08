-- REQ-SE-002: second idempotency layer for orders. Redis (shop:idem:order:{key}) answers repeats first; if that key
-- has expired or was lost, this unique key still stops the same request from creating a second order.
ALTER TABLE orders
    ADD COLUMN idempotency_key VARCHAR(64) NULL AFTER member_id,
    ADD CONSTRAINT uk_orders_member_idempotency UNIQUE (member_id, idempotency_key);
