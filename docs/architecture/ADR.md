# Architecture Decision Records (ADRs)

This document records the architectural and design decisions made for the **Order & Payments Platform**, including the context, considered alternatives, chosen solution, and tradeoffs.

---

## ADR-001: Orchestrated Saga vs Choreographed Saga

### Status
Accepted

### Context
When a customer places an order, the workflow spans three independent microservices:
1. `order-service` creates the order.
2. `inventory-service` reserves stock.
3. `payment-service` charges the customer.
4. If payment fails or stock is exhausted, previously committed steps must be compensated (e.g., release reserved stock, mark order as cancelled).

We must decide between an **Orchestrated Saga** and a **Choreographed Saga**.

### Alternatives Considered
1. **Choreographed Saga**:
   - Services broadcast domain events and independently listen/react to each other.
   - *Pros*: Loose coupling, no single central coordinator.
   - *Cons*: Difficult to trace end-to-end status; "spaghetti event" problem as workflow grows (e.g., adding fraud checks, notifications, vouchers); cyclic event dependencies; compensating actions are fragmented across multiple codebases.
2. **Orchestrated Saga (Chosen)**:
   - `order-service` acts as the central coordinator (orchestrator) managing the order state machine.
   - It issues commands/events (`ORDER_CREATED`, `PAYMENT_TRIGGERED`, `RELEASE_INVENTORY_COMMAND`) and listens to replies (`INVENTORY_RESERVED`, `PAYMENT_COMPLETED`, `PAYMENT_FAILED`).
   - *Pros*:
     - **Centralized State & Visibility**: An order's exact lifecycle phase is immediately visible in `order_db`.
     - **Predictable Error Handling**: Compensation logic is explicitly orchestrated from one place.
     - **Easy Testing**: The entire state machine can be tested deterministically with integration tests.
   - *Cons*:
     - `order-service` has additional logic coordinating the sequence.

### Real-World Production Note
In large enterprises with dozens of saga steps (e.g., Uber, Netflix), dedicated workflow orchestrators like **Temporal.io** or **AWS Step Functions** are used. For this platform, implementing the orchestrator state machine directly within `order-service` using Spring State Machine/Clean Service patterns provides transparency, zero external runtime dependencies, and high performance.

---

## ADR-002: Reliable Event Publishing (Transactional Outbox Pattern vs Dual Writes vs Debezium CDC)

### Status
Accepted

### Context
When a service modifies its local state (e.g., Order Created in PostgreSQL), it must publish an event to Kafka. If the database commit succeeds but the Kafka publish fails (or vice versa), the system enters an inconsistent state (the **Dual-Write Problem**).

### Alternatives Considered
1. **Direct Dual-Write (Application writes to DB, then calls `KafkaTemplate.send()`)**:
   - *Rejected*: Inevitably leads to data divergence. If Kafka is temporarily unreachable, the database row exists but downstream services never know. If published before DB commit, a DB rollback leaves a ghost event in Kafka.
2. **Distributed 2-Phase Commit (XA Transactions)**:
   - *Rejected*: Kafka does not support XA/JTA transactions with PostgreSQL. 2PC introduces extreme latency, tight runtime coupling, and coordinator single points of failure.
3. **Change Data Capture (Debezium / Kafka Connect)**:
   - Streams PostgreSQL Write-Ahead Log (WAL) directly to Kafka.
   - *Pros*: Zero polling overhead on the database; near-instant event streaming.
   - *Cons*: Requires a dedicated Kafka Connect cluster and database superuser WAL replication permissions, increasing operational complexity for self-contained environments.
4. **Transactional Outbox with Scheduled Poller + `SKIP LOCKED` (Chosen)**:
   - The domain entity and the outbox event are saved in the **same local database transaction**.
   - A background Spring `@Scheduled` worker queries `SELECT * FROM outbox WHERE status = 'PENDING' ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 50` and publishes to Kafka.
   - *Pros*:
     - 100% atomic: if the DB transaction rolls back, no event is persisted.
     - `SKIP LOCKED` allows multiple service replicas to poll concurrently without deadlocks or duplicate work.
     - Simple to deploy, inspect, and test with standard Testcontainers.

### Real-World Production Note
In hyperscale production (>50,000 writes/sec), Debezium CDC is the industry standard to avoid polling latency and DB read load. Our implementation uses the Outbox table schema in a way that is 100% compatible with Debezium CDC outbox routing if transitioned later.

---

## ADR-003: Concurrency Control in Inventory (Optimistic Locking vs Pessimistic Locking)

### Status
Accepted

### Context
During flash sales or high-demand checkout events, multiple customers attempt to purchase the last remaining units of an inventory item simultaneously. We must prevent overselling without degrading database throughput.

### Alternatives Considered
1. **Pessimistic Locking (`SELECT FOR UPDATE`)**:
   - Acquires an exclusive database lock on the inventory row for the duration of the reservation transaction.
   - *Rejected*: Severely limits throughput. Long-running transactions hold database row locks, leading to connection pool exhaustion, latency spikes, and potential deadlocks when multiple SKUs are ordered in different sequences.
2. **Redis Distributed Locks (Redlock)**:
   - Locks the SKU in Redis before updating Postgres.
   - *Rejected*: Introduces an extra network hop and operational failure mode (Redis partition or lock expiry during slow GC pause). Does not provide ACID guarantees with Postgres.
3. **Optimistic Locking (`@Version` on JPA Entity / Version Column) (Chosen)**:
   - Uses a `version` column: `UPDATE inventory SET available_quantity = available_quantity - ?, version = version + 1 WHERE sku = ? AND version = ?`.
   - If another transaction modified the row concurrently, Hibernate throws `OptimisticLockingFailureException`.
   - *Pros*:
     - Non-blocking reads; maximum database concurrency under normal load.
     - Guarantees stock will never go negative.
     - Coupled with Spring `@Retryable` (up to 3 attempts with random jitter) to gracefully retry transient conflicts.

---

## ADR-004: Consumer Idempotency & Deduplication

### Status
Accepted

### Context
Kafka provides **at-least-once** delivery guarantees. Network timeouts during partition rebalances or consumer restarts can cause consumers to receive the same event multiple times.

### Alternatives Considered
1. **Relying on Redis for Deduplication**:
   - Key: `processed_event:{eventId}` with TTL.
   - *Rejected*: If the Redis write succeeds but the service crashes before committing the database transaction, the event is permanently lost upon redelivery. Redis is not transactional with PostgreSQL.
2. **Local Database `processed_events` Table (Chosen)**:
   - Each service maintains a `processed_events(event_id, consumer_group, processed_at)` table with a composite primary key.
   - When consuming an event, the check-and-insert into `processed_events` is executed **inside the same local database transaction** as the business logic.
   - *Pros*:
     - Absolute atomicity: if the transaction commits, the event is recorded as processed.
     - Duplicate events cause a unique constraint violation (`DataIntegrityViolationException`), allowing the consumer to immediately acknowledge and skip without side effects.

---

## ADR-005: Payment Provider Resiliency & Circuit Breaking

### Status
Accepted

### Context
External payment gateways (Stripe, Adyen, mock banks) are third-party systems subject to unpredictable latency, rate limits, and outages. Unbounded synchronous calls can starve the calling service's thread pool.

### Decision
Wrap all payment gateway interactions with **Resilience4j**:
1. **Circuit Breaker**:
   - Sliding window of 10 calls.
   - Opens when failure rate exceeds 50% or slow call rate (>2000ms) exceeds 50%.
   - In OPEN state, immediately returns a `PaymentProviderUnavailableException` (HTTP 503) without contacting the external provider, protecting downstream resources.
   - Transitions to HALF-OPEN after 10 seconds to probe recovery.
2. **Bulkhead**: Limits concurrent calls to 20 to prevent runaway threads.
3. **Retry with Exponential Backoff**: Up to 3 attempts only for transient HTTP 5xx or connection timeouts (never for 4xx card declines).
