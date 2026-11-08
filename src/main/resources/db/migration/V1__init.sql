-- REQ-DA-001 (domain tables) + REQ-DA-002 (outbox / processed / dead-letter tables)
-- Rules: money BIGINT (KRW), timestamps DATETIME(6), status columns VARCHAR (enum name).
-- Foreign keys only inside one module; cross-module references (member_id, product_id, order_id) are plain columns
-- so that modules talk through Kafka events, not through the schema.

-- member ------------------------------------------------------------------
CREATE TABLE members (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    email         VARCHAR(100) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    name          VARCHAR(50)  NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_members_email UNIQUE (email)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- product -----------------------------------------------------------------
CREATE TABLE categories (
    id   BIGINT      NOT NULL AUTO_INCREMENT,
    name VARCHAR(50) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_categories_name UNIQUE (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE products (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    category_id BIGINT        NOT NULL,
    name        VARCHAR(100)  NOT NULL,
    description VARCHAR(2000) NOT NULL,
    price       BIGINT        NOT NULL,
    stock       INT           NOT NULL,
    image_url   VARCHAR(255)  NOT NULL,
    created_at  DATETIME(6)   NOT NULL,
    updated_at  DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_products_category FOREIGN KEY (category_id) REFERENCES categories (id),
    CONSTRAINT ck_products_price CHECK (price >= 0),
    CONSTRAINT ck_products_stock CHECK (stock >= 0),
    INDEX idx_products_category (category_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- order -------------------------------------------------------------------
CREATE TABLE orders (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    member_id          BIGINT       NOT NULL,
    status             VARCHAR(30)  NOT NULL,
    total_amount       BIGINT       NOT NULL,
    receiver_name      VARCHAR(50)  NOT NULL,
    receiver_phone     VARCHAR(20)  NOT NULL,
    shipping_address   VARCHAR(255) NOT NULL,
    payment_expires_at DATETIME(6)  NOT NULL,
    paid_at            DATETIME(6)  NULL,
    cancel_reason      VARCHAR(30)  NULL,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    version            BIGINT       NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_orders_member_created (member_id, created_at),
    INDEX idx_orders_status_expires (status, payment_expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE order_items (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    order_id     BIGINT       NOT NULL,
    product_id   BIGINT       NOT NULL,
    product_name VARCHAR(100) NOT NULL,
    unit_price   BIGINT       NOT NULL,
    quantity     INT          NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT ck_order_items_quantity CHECK (quantity > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE order_status_history (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    order_id    BIGINT      NOT NULL,
    from_status VARCHAR(30) NULL,
    to_status   VARCHAR(30) NOT NULL,
    reason      VARCHAR(30) NULL,
    event_id    VARCHAR(36) NULL,
    event_type  VARCHAR(50) NULL,
    changed_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_order_status_history_order FOREIGN KEY (order_id) REFERENCES orders (id),
    INDEX idx_order_status_history_order (order_id, changed_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- payment -----------------------------------------------------------------
CREATE TABLE payments (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    order_id     BIGINT      NOT NULL,
    amount       BIGINT      NOT NULL,
    status       VARCHAR(20) NOT NULL,
    method       VARCHAR(20) NULL,
    expires_at   DATETIME(6) NOT NULL,
    approved_at  DATETIME(6) NULL,
    failed_at    DATETIME(6) NULL,
    cancelled_at DATETIME(6) NULL,
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_payments_order UNIQUE (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- stock -------------------------------------------------------------------
CREATE TABLE stock_reservations (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    order_id   BIGINT      NOT NULL,
    product_id BIGINT      NOT NULL,
    quantity   INT         NOT NULL,
    status     VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_stock_reservations_order_product UNIQUE (order_id, product_id),
    CONSTRAINT ck_stock_reservations_quantity CHECK (quantity > 0),
    INDEX idx_stock_reservations_product_status (product_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- notification ------------------------------------------------------------
CREATE TABLE notification_logs (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    order_id   BIGINT       NOT NULL,
    member_id  BIGINT       NOT NULL,
    type       VARCHAR(30)  NOT NULL,
    message    VARCHAR(255) NOT NULL,
    event_id   VARCHAR(36)  NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_notification_logs_order (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- event (REQ-DA-002) ------------------------------------------------------
CREATE TABLE outbox_events (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    event_id    VARCHAR(36)  NOT NULL,
    topic       VARCHAR(100) NOT NULL,
    message_key VARCHAR(100) NOT NULL,
    payload     JSON         NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    retry_count INT          NOT NULL DEFAULT 0,
    created_at  DATETIME(6)  NOT NULL,
    sent_at     DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_outbox_events_event UNIQUE (event_id),
    INDEX idx_outbox_events_status_id (status, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE processed_events (
    consumer_group VARCHAR(50) NOT NULL,
    event_id       VARCHAR(36) NOT NULL,
    processed_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (consumer_group, event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE dead_letter_events (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    original_topic VARCHAR(100)  NOT NULL,
    payload        TEXT          NOT NULL,
    error_message  VARCHAR(1000) NULL,
    created_at     DATETIME(6)   NOT NULL,
    retried_at     DATETIME(6)   NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
