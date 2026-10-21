# Kafka Event Catalog & Schemas

## 1. Kafka Messaging Standards

### 1.1 Topic Naming Conventions
Topics follow the domain-driven format: `<domain>.<entity>.<version>.<lifecycle>`

| Topic Name | Producer(s) | Primary Consumers | Partition Key | Retention |
| :--- | :--- | :--- | :--- | :--- |
| `orders.v1.events` | `order-service` | `inventory-service`, `payment-service`, `notification-service` | `orderId` | 7 Days |
| `inventory.v1.events` | `inventory-service` | `order-service` | `orderId` | 7 Days |
| `payments.v1.events` | `payment-service` | `order-service`, `notification-service` | `orderId` | 7 Days |
| `notifications.v1.events`| `notification-service`| Auditing / Analytics | `orderId` | 3 Days |
| `*.DLT` | Any Consumer | Dead Letter Topic Monitor | Same as source | 30 Days |

### 1.2 Partitioning & Ordering Guarantee
All saga events associated with a checkout lifecycle use the **`orderId`** as the Kafka message key.
- **Guarantee**: All events for order `ord-987` land on the **same partition** within any given topic.
- **Ordering**: Guarantees strict chronological processing order per order without global partition bottlenecks.

### 1.3 Producer & Consumer Resiliency Parameters
- **Producer**:
  - `acks=all` (Waits for all in-sync replicas to acknowledge)
  - `enable.idempotence=true` (Prevents broker-side duplicates due to network retries)
  - `retries=Integer.MAX_VALUE`
  - `max.in.flight.requests.per.connection=5`
- **Consumer**:
  - `enable.auto.commit=false` (Manual acknowledgment after successful local DB transaction)
  - `auto.offset.reset=earliest`
  - `AckMode=MANUAL_IMMEDIATE`
  - Error Handler: `DefaultErrorHandler` with `BackOff` (3 attempts, 1s initial, 2.0 multiplier, capped at 10s) routing to `<topic>.DLT` on failure exhaustion.

---

## 2. Standard Event Envelope (CloudEvents Compliant)

Every event sent over Kafka wraps the domain payload inside a standard metadata envelope:

```json
{
  "eventId": "c1f7b8a2-5881-432d-9669-e72e01dfba88",
  "eventType": "ORDER_CREATED",
  "aggregateType": "ORDER",
  "aggregateId": "ord-2026-0001",
  "timestamp": "2026-10-21T11:45:00.000Z",
  "correlationId": "corr-uuid-9988",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId": "00f067aa0ba902b7",
  "schemaVersion": "1.0",
  "payload": {}
}
```

---

## 3. Detailed Event Payloads

### 3.1 Order Events (`orders.v1.events`)

#### A. `ORDER_CREATED`
Published by `order-service` when a user submits a checkout request.
```json
{
  "eventId": "3c9d7249-16a7-47b8-8c10-09fa49896350",
  "eventType": "ORDER_CREATED",
  "aggregateType": "ORDER",
  "aggregateId": "ord-7721",
  "timestamp": "2026-10-21T11:45:00.000Z",
  "correlationId": "corr-87612",
  "traceId": "e0a7249b16a747b8",
  "spanId": "16a747b8e0a7249b",
  "schemaVersion": "1.0",
  "payload": {
    "orderId": "ord-7721",
    "customerId": "cust-501",
    "totalAmount": 199.98,
    "currency": "USD",
    "items": [
      {
        "sku": "SKU-MACBOOK-AIR",
        "quantity": 1,
        "unitPrice": 149.99
      },
      {
        "sku": "SKU-USB-C-ADAPTER",
        "quantity": 2,
        "unitPrice": 24.99
      }
    ],
    "shippingAddress": {
      "street": "100 Innovation Way",
      "city": "Seattle",
      "state": "WA",
      "postalCode": "98101",
      "country": "USA"
    }
  }
}
```

#### B. `ORDER_CONFIRMED`
Published when both inventory and payment succeed.
```json
{
  "eventId": "a749b16a-747b-4c10-8c10-fa498963503c",
  "eventType": "ORDER_CONFIRMED",
  "aggregateType": "ORDER",
  "aggregateId": "ord-7721",
  "timestamp": "2026-10-21T11:45:03.200Z",
  "correlationId": "corr-87612",
  "traceId": "e0a7249b16a747b8",
  "spanId": "249be0a716a747b8",
  "schemaVersion": "1.0",
  "payload": {
    "orderId": "ord-7721",
    "customerId": "cust-501",
    "totalAmount": 199.98,
    "currency": "USD",
    "paymentTransactionId": "tx-pay-998822",
    "confirmedAt": "2026-10-21T11:45:03.180Z"
  }
}
```

#### C. `ORDER_CANCELLED`
Published when compensation completes (due to inventory depletion or payment decline).
```json
{
  "eventId": "8c10a749-747b-16a7-4c10-8963503cfa49",
  "eventType": "ORDER_CANCELLED",
  "aggregateType": "ORDER",
  "aggregateId": "ord-7721",
  "timestamp": "2026-10-21T11:45:02.800Z",
  "correlationId": "corr-87612",
  "traceId": "e0a7249b16a747b8",
  "spanId": "47b8249be0a716a7",
  "schemaVersion": "1.0",
  "payload": {
    "orderId": "ord-7721",
    "customerId": "cust-501",
    "reason": "PAYMENT_DECLINED",
    "details": "Insufficient funds in customer account",
    "cancelledAt": "2026-10-21T11:45:02.750Z"
  }
}
```

---

### 3.2 Inventory Events (`inventory.v1.events`)

#### A. `INVENTORY_RESERVED`
Published by `inventory-service` upon successfully reserving quantities using optimistic locking.
```json
{
  "eventId": "16a7fa49-8963-4c10-8c10-3c9d7249b16a",
  "eventType": "INVENTORY_RESERVED",
  "aggregateType": "INVENTORY",
  "aggregateId": "ord-7721",
  "timestamp": "2026-10-21T11:45:01.120Z",
  "correlationId": "corr-87612",
  "traceId": "e0a7249b16a747b8",
  "spanId": "3c9d7249b16a16a7",
  "schemaVersion": "1.0",
  "payload": {
    "orderId": "ord-7721",
    "reservations": [
      {
        "sku": "SKU-MACBOOK-AIR",
        "reservedQuantity": 1
      },
      {
        "sku": "SKU-USB-C-ADAPTER",
        "reservedQuantity": 2
      }
    ],
    "reservedUntil": "2026-10-21T12:00:00.000Z"
  }
}
```

#### B. `INVENTORY_RESERVATION_FAILED`
Published when stock is insufficient or optimistic lock exhausts retries.
```json
{
  "eventId": "9d7249b1-fa49-4c10-8c10-8963503c16a7",
  "eventType": "INVENTORY_RESERVATION_FAILED",
  "aggregateType": "INVENTORY",
  "aggregateId": "ord-7721",
  "timestamp": "2026-10-21T11:45:01.050Z",
  "correlationId": "corr-87612",
  "traceId": "e0a7249b16a747b8",
  "spanId": "7249b16a16a73c9d",
  "schemaVersion": "1.0",
  "payload": {
    "orderId": "ord-7721",
    "failedSku": "SKU-MACBOOK-AIR",
    "requestedQuantity": 1,
    "availableQuantity": 0,
    "failureReason": "INSUFFICIENT_STOCK"
  }
}
```

#### C. `INVENTORY_RELEASED`
Published when compensating release succeeds.
```json
{
  "eventId": "747b8c10-3c9d-4c10-fa49-09fa49896350",
  "eventType": "INVENTORY_RELEASED",
  "aggregateType": "INVENTORY",
  "aggregateId": "ord-7721",
  "timestamp": "2026-10-21T11:45:03.010Z",
  "correlationId": "corr-87612",
  "traceId": "e0a7249b16a747b8",
  "spanId": "fa498963503c747b",
  "schemaVersion": "1.0",
  "payload": {
    "orderId": "ord-7721",
    "releasedItems": [
      {
        "sku": "SKU-MACBOOK-AIR",
        "quantity": 1
      },
      {
        "sku": "SKU-USB-C-ADAPTER",
        "quantity": 2
      }
    ]
  }
}
```

---

### 3.3 Payment Events (`payments.v1.events`)

#### A. `PAYMENT_COMPLETED`
```json
{
  "eventId": "2e01dfba-88c1-432d-9669-c1f7b8a25881",
  "eventType": "PAYMENT_COMPLETED",
  "aggregateType": "PAYMENT",
  "aggregateId": "ord-7721",
  "timestamp": "2026-10-21T11:45:02.400Z",
  "correlationId": "corr-87612",
  "traceId": "e0a7249b16a747b8",
  "spanId": "88c1c1f7b8a25881",
  "schemaVersion": "1.0",
  "payload": {
    "paymentId": "pay-009128",
    "orderId": "ord-7721",
    "customerId": "cust-501",
    "amount": 199.98,
    "currency": "USD",
    "providerTransactionId": "mock-tx-88192837",
    "providerName": "MOCK_PAYMENT_GATEWAY",
    "processedAt": "2026-10-21T11:45:02.380Z"
  }
}
```

#### B. `PAYMENT_FAILED`
```json
{
  "eventId": "dfba88c1-2e01-432d-9669-5881c1f7b8a2",
  "eventType": "PAYMENT_FAILED",
  "aggregateType": "PAYMENT",
  "aggregateId": "ord-7721",
  "timestamp": "2026-10-21T11:45:02.350Z",
  "correlationId": "corr-87612",
  "traceId": "e0a7249b16a747b8",
  "spanId": "b8a25881dfba88c1",
  "schemaVersion": "1.0",
  "payload": {
    "paymentId": "pay-009129",
    "orderId": "ord-7721",
    "customerId": "cust-501",
    "amount": 199.98,
    "currency": "USD",
    "errorCode": "CARD_DECLINED",
    "errorMessage": "Card has insufficient balance"
  }
}
```

---

### 3.4 Dead Letter Topic Payload (`*.DLT`)

When a consumer exhausts all configured retries (e.g. malformed JSON or unrecoverable database errors), Spring Kafka routes the failed record to the corresponding DLT with diagnostic headers:

```json
{
  "originalTopic": "orders.v1.events",
  "originalPartition": 2,
  "originalOffset": 10452,
  "exceptionClass": "org.springframework.dao.DataIntegrityViolationException",
  "exceptionMessage": "ERROR: duplicate key value violates unique constraint 'idx_processed_events_pk'",
  "failedAt": "2026-10-21T11:45:05.120Z",
  "retryCount": 3,
  "originalPayload": { ... }
}
```
