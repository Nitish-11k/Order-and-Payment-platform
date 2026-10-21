# Database Schemas & Migrations Specification

## 1. Multi-Database Architecture

In adherence to the **Database-per-Service** pattern, each microservice owns its own isolated PostgreSQL schema/database. Direct cross-database joins are strictly forbidden. Data is shared exclusively via asynchronous Kafka events or REST APIs.

```
PostgreSQL Instance (Port: 5432)
├── order_db        (Owned by order-service)
├── inventory_db    (Owned by inventory-service)
└── payment_db      (Owned by payment-service)
```

Flyway manages migrations automatically on service startup:
- Location: `src/main/resources/db/migration/`
- Naming format: `V{version}__{description}.sql` (e.g. `V1__init_order_schema.sql`)

---

## 2. Service 1: `order_db` (Order Service)

### Flyway Migration: `V1__init_order_schema.sql`

```sql
-- 1. Orders Table
CREATE TABLE orders (
    id VARCHAR(64) PRIMARY KEY,
    customer_id VARCHAR(64) NOT NULL,
    total_amount NUMERIC(12, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    failure_reason VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_orders_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX idx_orders_customer ON orders(customer_id);
CREATE INDEX idx_orders_status ON orders(status);

-- 2. Order Items Table
CREATE TABLE order_items (
    id BIGSERIAL PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    sku VARCHAR(64) NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(12, 2) NOT NULL CHECK (unit_price >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_order_items_order ON order_items(order_id);

-- 3. Transactional Outbox Table
CREATE TABLE outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    correlation_id VARCHAR(64),
    trace_id VARCHAR(64),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    published_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_outbox_poll ON outbox(status, created_at) WHERE status = 'PENDING';

-- 4. Processed Events (Consumer Idempotency Deduplication)
CREATE TABLE processed_events (
    event_id VARCHAR(64) NOT NULL,
    consumer_group VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (event_id, consumer_group)
);
```

---

## 3. Service 2: `inventory_db` (Inventory Service)

### Flyway Migration: `V1__init_inventory_schema.sql`

```sql
-- 1. Product Inventory Table (Optimistic Locking via version)
CREATE TABLE inventory (
    id BIGSERIAL PRIMARY KEY,
    sku VARCHAR(64) NOT NULL UNIQUE,
    product_name VARCHAR(128) NOT NULL,
    available_quantity INT NOT NULL CHECK (available_quantity >= 0),
    reserved_quantity INT NOT NULL DEFAULT 0 CHECK (reserved_quantity >= 0),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_inventory_sku ON inventory(sku);

-- 2. Inventory Reservations Log
CREATE TABLE inventory_reservations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id VARCHAR(64) NOT NULL,
    sku VARCHAR(64) NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0),
    status VARCHAR(32) NOT NULL, -- RESERVED, CONFIRMED, RELEASED
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_reservations_order ON inventory_reservations(order_id);

-- 3. Transactional Outbox Table
CREATE TABLE outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    correlation_id VARCHAR(64),
    trace_id VARCHAR(64),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    published_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_outbox_poll ON outbox(status, created_at) WHERE status = 'PENDING';

-- 4. Processed Events Table (Consumer Deduplication)
CREATE TABLE processed_events (
    event_id VARCHAR(64) NOT NULL,
    consumer_group VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (event_id, consumer_group)
);
```

### Seed Data: `V2__seed_inventory_data.sql`
```sql
INSERT INTO inventory (sku, product_name, available_quantity, reserved_quantity, version)
VALUES 
('SKU-MACBOOK-AIR', 'Apple MacBook Air M3 13"', 50, 0, 0),
('SKU-USB-C-ADAPTER', 'Multiport USB-C Adapter 4K', 150, 0, 0),
('SKU-MECH-KEYBOARD', 'Mechanical Gaming Keyboard RGB', 30, 0, 0),
('SKU-WIRELESS-MOUSE', 'Ergonomic Wireless Mouse', 75, 0, 0),
('SKU-LAST-ITEM-TEST', 'Ultra Rare Collector Mug (Concurrency Test)', 1, 0, 0);
```

---

## 4. Service 3: `payment_db` (Payment Service)

### Flyway Migration: `V1__init_payment_schema.sql`

```sql
-- 1. Payments Table
CREATE TABLE payments (
    id VARCHAR(64) PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL UNIQUE,
    customer_id VARCHAR(64) NOT NULL,
    amount NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    status VARCHAR(32) NOT NULL, -- PENDING, COMPLETED, FAILED, REFUNDED
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    provider_transaction_id VARCHAR(128),
    failure_reason VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payments_order ON payments(order_id);
CREATE INDEX idx_payments_status ON payments(status);

-- 2. Refunds Table
CREATE TABLE refunds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id VARCHAR(64) NOT NULL REFERENCES payments(id),
    order_id VARCHAR(64) NOT NULL,
    amount NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    status VARCHAR(32) NOT NULL, -- COMPLETED, FAILED
    reason VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refunds_payment ON refunds(payment_id);

-- 3. Transactional Outbox Table
CREATE TABLE outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    correlation_id VARCHAR(64),
    trace_id VARCHAR(64),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    published_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_outbox_poll ON outbox(status, created_at) WHERE status = 'PENDING';

-- 4. Processed Events Table (Consumer Deduplication)
CREATE TABLE processed_events (
    event_id VARCHAR(64) NOT NULL,
    consumer_group VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (event_id, consumer_group)
);
```

---

## 5. Service 4: `notification-service` Storage

For local tracking and test verification, the notification service tracks dispatched notifications:

```sql
CREATE TABLE notification_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id VARCHAR(64) NOT NULL,
    recipient VARCHAR(128) NOT NULL,
    channel VARCHAR(16) NOT NULL, -- EMAIL, SMS
    template_name VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,  -- DISPATCHED, FAILED
    payload JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE processed_events (
    event_id VARCHAR(64) NOT NULL,
    consumer_group VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (event_id, consumer_group)
);
```
