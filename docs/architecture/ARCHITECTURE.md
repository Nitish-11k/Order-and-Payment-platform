# System Architecture Specification

## 1. System Context & Overview

The **Order & Payments Platform** is a distributed, event-driven microservices architecture designed to handle e-commerce checkout, distributed transactions, inventory reservation, and payment processing with high resiliency, strict data consistency, and end-to-end observability.

```mermaid
flowchart TB
    subgraph Clients["Clients & External Systems"]
        WebClient["Web / Mobile App"]
        LoadGen["Load Generator (k6 / Gatling)"]
    end

    subgraph EdgeLayer["Edge / API Gateway (Port: 8080)"]
        Gateway["Spring Cloud Gateway"]
        AuthFilter["JWT Authentication Filter"]
        RateLimiter["Token-Bucket Rate Limiter"]
        RedisStore[("Redis (Rate Limiter & Gateway Cache)")]
    end

    subgraph CoreServices["Domain Microservices"]
        OrderService["order-service (Port: 8081)\n[Saga Orchestrator + State Tracker]"]
        InventoryService["inventory-service (Port: 8082)\n[Stock Reservation + Optimistic Lock]"]
        PaymentService["payment-service (Port: 8083)\n[Payment Engine + Resilience4j]"]
        NotificationService["notification-service (Port: 8084)\n[Event Consumer + Mock Dispatcher]"]
    end

    subgraph DataTier["Data Tier (PostgreSQL - Isolated Databases)"]
        OrderDB[("order_db\n(orders, outbox, idempotency)")]
        InventoryDB[("inventory_db\n(inventory, outbox, processed_events)")]
        PaymentDB[("payment_db\n(payments, outbox, processed_events)")]
        RedisCache[("Redis (Product & Inventory Cache)")]
    end

    subgraph MessagingTier["Event Streaming Tier (Apache Kafka)"]
        KafkaBroker["Kafka Broker Cluster"]
        TopicOrderEvents["orders.v1.events"]
        TopicInventoryEvents["inventory.v1.events"]
        TopicPaymentEvents["payments.v1.events"]
        TopicNotifications["notifications.v1.events"]
        TopicDLT["*.DLT (Dead Letter Topics)"]
    end

    subgraph ExternalProvider["External Systems"]
        MockBank["Payment Gateway Provider\n(Simulated with Latency & Chaos Injection)"]
    end

    subgraph ObservabilityStack["Observability & Diagnostics"]
        OTel["OpenTelemetry Collector / Agent"]
        Prometheus["Prometheus Metrics Scraper"]
        Grafana["Grafana Dashboards"]
        Jaeger["Jaeger Distributed Tracing"]
    end

    %% Client Routing
    WebClient -->|HTTPS / REST| Gateway
    LoadGen -->|REST Load| Gateway
    Gateway --> AuthFilter
    AuthFilter --> RateLimiter
    RateLimiter <-->|Token Balance Check| RedisStore

    %% Gateway to Downstream
    Gateway -->|Forward /api/v1/orders/**| OrderService
    Gateway -->|Forward /api/v1/inventory/**| InventoryService
    Gateway -->|Forward /api/v1/payments/**| PaymentService

    %% Microservice to DB
    OrderService <-->|Transactional Writes| OrderDB
    InventoryService <-->|Optimistic Locking| InventoryDB
    InventoryService <-->|Read Cache| RedisCache
    PaymentService <-->|Idempotent Writes| PaymentDB

    %% External Call
    PaymentService -->|Resilience4j Circuit Breaker| MockBank

    %% Transactional Outbox to Kafka
    OrderService -.->|Outbox Publisher| TopicOrderEvents
    InventoryService -.->|Outbox Publisher| TopicInventoryEvents
    PaymentService -.->|Outbox Publisher| TopicPaymentEvents
    
    %% Kafka Consumers
    TopicOrderEvents -->|Consume OrderCreated / Cancelled| InventoryService
    TopicInventoryEvents -->|Consume StockReserved / Failed| OrderService
    TopicOrderEvents -->|Consume Trigger Payment| PaymentService
    TopicPaymentEvents -->|Consume PaymentCompleted / Failed| OrderService
    TopicOrderEvents -->|Consume Order Status Updates| NotificationService
    TopicPaymentEvents -->|Consume Payment Events| NotificationService

    %% DLTs
    KafkaBroker -.-> TopicDLT

    %% Observability
    OrderService -.->|Traces & Metrics| OTel
    InventoryService -.->|Traces & Metrics| OTel
    PaymentService -.->|Traces & Metrics| OTel
    NotificationService -.->|Traces & Metrics| OTel
    Gateway -.->|Traces & Metrics| OTel
    OTel --> Prometheus
    OTel --> Jaeger
    Prometheus --> Grafana
```

---

## 2. Distributed Saga Workflow

We implement an **Orchestrated Saga** where the `order-service` acts as the Saga Orchestrator maintaining the state machine of each order (`PENDING` -> `INVENTORY_RESERVED` -> `PAYMENT_PROCESSED` -> `CONFIRMED`, or compensation states `CANCELLED` and `REFUNDED`).

### 2.1 Happy Path Flow (Success)

```mermaid
sequenceDiagram
    autonumber
    actor Client as Customer / Web App
    participant GW as API Gateway
    participant OS as Order Service
    participant ODB as order_db
    participant K as Kafka Broker
    participant IS as Inventory Service
    participant IDB as inventory_db
    participant PS as Payment Service
    participant PDB as payment_db
    participant NS as Notification Service

    Client->>GW: POST /api/v1/orders (Idempotency-Key: idemp-uuid-123)
    GW->>GW: Validate JWT & Rate Limit (Token Bucket)
    GW->>OS: Forward POST /api/v1/orders
    
    rect rgb(235, 245, 255)
    note right of OS: Transaction 1 (Atomic Local DB Write)
    OS->>ODB: Check Idempotency Key
    OS->>ODB: INSERT Order (Status: PENDING)
    OS->>ODB: INSERT Outbox (Event: OrderCreatedEvent)
    end
    
    OS-->>GW: HTTP 201 Created (OrderId, Status: PENDING)
    GW-->>Client: HTTP 201 Created

    OS->>K: Outbox Poller publishes OrderCreatedEvent (Key: orderId)
    
    par Inventory Reservation
        K->>IS: Consume OrderCreatedEvent
        rect rgb(235, 255, 235)
        note right of IS: Transaction 2 (Optimistic Lock)
        IS->>IDB: Dedupe check (processed_events)
        IS->>IDB: UPDATE inventory SET available = available - qty, version = version + 1 WHERE sku = ? AND version = ?
        IS->>IDB: INSERT reservation (orderId, sku, qty, status: RESERVED)
        IS->>IDB: INSERT Outbox (Event: InventoryReservedEvent)
        end
        IS->>K: Publish InventoryReservedEvent (Key: orderId)
    end

    K->>OS: Consume InventoryReservedEvent
    OS->>ODB: UPDATE Order SET status = 'INVENTORY_RESERVED'
    OS->>ODB: INSERT Outbox (Event: PaymentTriggeredEvent)
    OS->>K: Publish PaymentTriggeredEvent (Key: orderId)

    par Payment Processing
        K->>PS: Consume PaymentTriggeredEvent
        rect rgb(255, 250, 235)
        note right of PS: Transaction 3 (Payment Execution)
        PS->>PDB: Dedupe check (processed_events)
        PS->>PS: Call Mock Gateway (Circuit Breaker protected)
        PS->>PDB: INSERT payment (orderId, amount, status: COMPLETED)
        PS->>PDB: INSERT Outbox (Event: PaymentCompletedEvent)
        end
        PS->>K: Publish PaymentCompletedEvent (Key: orderId)
    end

    K->>OS: Consume PaymentCompletedEvent
    OS->>ODB: UPDATE Order SET status = 'CONFIRMED'
    OS->>ODB: INSERT Outbox (Event: OrderConfirmedEvent)
    OS->>K: Publish OrderConfirmedEvent (Key: orderId)

    K->>NS: Consume OrderConfirmedEvent
    NS->>NS: Send Order Confirmation Email / SMS
```

---

### 2.2 Compensating Flow 1: Insufficient Inventory (Out of Stock)

```mermaid
sequenceDiagram
    autonumber
    participant OS as Order Service
    participant K as Kafka Broker
    participant IS as Inventory Service
    participant IDB as inventory_db
    participant ODB as order_db
    participant NS as Notification Service

    OS->>K: Publish OrderCreatedEvent (Key: orderId)
    K->>IS: Consume OrderCreatedEvent
    IS->>IDB: Check stock for SKU
    note over IS,IDB: Available stock < requested quantity
    IS->>IDB: INSERT Outbox (Event: InventoryReservationFailedEvent, Reason: OUT_OF_STOCK)
    IS->>K: Publish InventoryReservationFailedEvent (Key: orderId)

    K->>OS: Consume InventoryReservationFailedEvent
    rect rgb(255, 235, 235)
    note right of OS: Compensation Local Tx
    OS->>ODB: UPDATE Order SET status = 'CANCELLED', failure_reason = 'OUT_OF_STOCK'
    OS->>ODB: INSERT Outbox (Event: OrderCancelledEvent)
    end
    OS->>K: Publish OrderCancelledEvent (Key: orderId)

    K->>NS: Consume OrderCancelledEvent
    NS->>NS: Send Order Cancellation Notification to Customer
```

---

### 2.3 Compensating Flow 2: Payment Failure (Stock Release Required)

```mermaid
sequenceDiagram
    autonumber
    participant OS as Order Service
    participant K as Kafka Broker
    participant IS as Inventory Service
    participant IDB as inventory_db
    participant PS as Payment Service
    participant PDB as payment_db
    participant ODB as order_db
    participant NS as Notification Service

    note over OS: Inventory is RESERVED. Triggering payment.
    OS->>K: Publish PaymentTriggeredEvent (Key: orderId)
    K->>PS: Consume PaymentTriggeredEvent
    PS->>PS: Call Payment Provider -> Declined / Insufficient Funds
    PS->>PDB: INSERT payment (orderId, status: FAILED, reason: CARD_DECLINED)
    PS->>PDB: INSERT Outbox (Event: PaymentFailedEvent)
    PS->>K: Publish PaymentFailedEvent (Key: orderId)

    K->>OS: Consume PaymentFailedEvent
    rect rgb(255, 235, 235)
    note right of OS: Initiate Compensation
    OS->>ODB: UPDATE Order SET status = 'PAYMENT_FAILED'
    OS->>ODB: INSERT Outbox (Event: ReleaseInventoryCommand)
    end
    OS->>K: Publish ReleaseInventoryCommand (Key: orderId)

    K->>IS: Consume ReleaseInventoryCommand
    rect rgb(255, 240, 240)
    note right of IS: Compensating Action
    IS->>IDB: Dedupe check
    IS->>IDB: UPDATE inventory SET available = available + qty WHERE sku = ?
    IS->>IDB: UPDATE reservation SET status = 'RELEASED' WHERE order_id = ?
    IS->>IDB: INSERT Outbox (Event: InventoryReleasedEvent)
    end
    IS->>K: Publish InventoryReleasedEvent (Key: orderId)

    K->>OS: Consume InventoryReleasedEvent
    OS->>ODB: UPDATE Order SET status = 'CANCELLED'
    OS->>ODB: INSERT Outbox (Event: OrderCancelledEvent)
    OS->>K: Publish OrderCancelledEvent

    K->>NS: Consume OrderCancelledEvent
    NS->>NS: Send Cancellation Notification
```

---

## 3. Transactional Outbox Pattern Details

To eliminate the **Dual-Write Problem** (where a local database write succeeds but publishing to Kafka fails, or vice-versa), every microservice writes its local state and outgoing event into the **same PostgreSQL database within a single ACID transaction**.

```mermaid
flowchart LR
    subgraph ServiceLocalTx["Single DB Transaction (Atomicity)"]
        BusinessLogic["Execute Domain Logic\n(e.g., Save Order)"]
        AppendOutbox["Append Event to 'outbox' Table\n(Status: PENDING)"]
    end

    subgraph BackgroundWorker["Outbox Dispatcher (Scheduled / CDC)"]
        Poller["Outbox Poller\n(Spring @Scheduled + SELECT FOR UPDATE SKIP LOCKED)"]
        Publisher["KafkaTemplate.send(topic, key, payload)"]
        Ack["UPDATE outbox SET status = 'PUBLISHED', published_at = NOW()"]
    end

    subgraph KafkaBrokerCluster["Kafka Broker"]
        KafkaTopic[("Kafka Topic\nPartitioned by orderId")]
    end

    BusinessLogic --> ServiceLocalTx
    AppendOutbox --> ServiceLocalTx
    ServiceLocalTx -->|Commit to DB| DB[("PostgreSQL")]

    DB -->|Read Pending Rows| Poller
    Poller --> Publisher
    Publisher -->|Acknowledge write| KafkaTopic
    KafkaTopic -->|Broker ACK| Ack
    Ack -->|Mark Done| DB
```

### Outbox Processing Characteristics
1. **Guaranteed At-Least-Once Delivery**: Events are never lost if the network or Kafka is down when the business transaction commits.
2. **Batch Polling with Concurrency Control**: We use `SELECT ... FOR UPDATE SKIP LOCKED` with limit (e.g., 50 rows) to allow multiple service instances to poll the outbox concurrently without race conditions or deadlocks.
3. **Partition Key Affinity**: All events for the same `orderId` use `orderId` as the Kafka message key. This guarantees strict in-order processing within a single Kafka partition for that order.
4. **Idempotent Consumers**: Downstream consumers enforce at-least-once deduplication using a `processed_events` table with unique event UUIDs.
