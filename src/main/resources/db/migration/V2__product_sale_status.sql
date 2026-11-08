-- REQ-F-013: admin can stop selling a product. Existing rows stay on sale.
ALTER TABLE products
    ADD COLUMN sale_status VARCHAR(20) NOT NULL DEFAULT 'ON_SALE' AFTER image_url;

CREATE INDEX idx_products_sale_status_created ON products (sale_status, created_at);
