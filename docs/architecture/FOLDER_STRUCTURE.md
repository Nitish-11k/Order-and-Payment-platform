# Multi-Module Project Folder Structure

## 1. Directory Layout

The platform uses a Maven multi-module architecture. This isolates domain boundaries while enabling shared event schemas via `common-events` to guarantee compile-time contract safety without circular dependencies.

```
PaymentMethods/
├── pom.xml                                    # Parent POM with dependency management
├── mvnw / mvnw.cmd                            # Maven Wrapper executables
├── README.md                                  # Platform overview & quickstart
├── docs/                                      # Architecture, API & Design specifications
│   └── architecture/
│       ├── ARCHITECTURE.md                    # System architecture & sequence diagrams
│       ├── EVENT_CATALOG.md                   # Kafka topics, partitioning & event schemas
│       ├── DATABASE_SCHEMAS.md                # PostgreSQL DDL & Flyway migrations
│       ├── API_CONTRACTS.md                   # REST API specs & RFC 7807 Problem Details
│       ├── ADR.md                             # Architecture Decision Records
│       └── FOLDER_STRUCTURE.md                # This document
├── common-events/                             # Shared contracts (Event records, Envelopes)
│   ├── pom.xml
│   └── src/
│       └── main/
│           └── java/com/platform/common/
│               ├── event/
│               │   ├── BaseEvent.java
│               │   ├── order/
│               │   │   ├── OrderCreatedEvent.java
│               │   │   ├── OrderConfirmedEvent.java
│               │   │   └── OrderCancelledEvent.java
│               │   ├── inventory/
│               │   │   ├── InventoryReservedEvent.java
│               │   │   ├── InventoryReservationFailedEvent.java
│               │   │   └── InventoryReleasedEvent.java
│               │   └── payment/
│               │       ├── PaymentCompletedEvent.java
│               │       ├── PaymentFailedEvent.java
│               │       └── PaymentRefundedEvent.java
│               └── error/
│                   └── ProblemDetails.java
├── api-gateway/                               # Edge Gateway (Spring Cloud Gateway)
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/platform/gateway/
│       │   │   ├── ApiGatewayApplication.java
│       │   │   ├── config/
│       │   │   │   ├── SecurityConfig.java
│       │   │   │   └── GatewayRoutesConfig.java
│       │   │   ├── filter/
│       │   │   │   ├── JwtAuthenticationFilter.java
│       │   │   │   └── CorrelationIdFilter.java
│       │   │   └── ratelimit/
│       │   │       └── RedisTokenBucketRateLimiter.java
│       │   └── resources/
│       │       └── application.yml
│       └── test/
├── order-service/                             # Order Domain & Saga Coordinator
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/platform/order/
│       │   │   ├── OrderServiceApplication.java
│       │   │   ├── config/
│       │   │   │   ├── KafkaConfig.java
│       │   │   │   └── OpenApiConfig.java
│       │   │   ├── controller/
│       │   │   │   └── OrderController.java
│       │   │   ├── dto/
│       │   │   │   ├── CreateOrderRequest.java
│       │   │   │   ├── OrderResponse.java
│       │   │   │   └── OrderItemDto.java
│       │   │   ├── entity/
│       │   │   │   ├── Order.java
│       │   │   │   ├── OrderItem.java
│       │   │   │   └── OrderStatus.java
│       │   │   ├── exception/
│       │   │   │   └── GlobalExceptionHandler.java
│       │   │   ├── outbox/
│       │   │   │   ├── OutboxMessage.java
│       │   │   │   ├── OutboxRepository.java
│       │   │   │   └── OutboxPublisherScheduler.java
│       │   │   ├── repository/
│       │   │   │   ├── OrderRepository.java
│       │   │   │   └── ProcessedEventRepository.java
│       │   │   ├── saga/
│       │   │   │   ├── OrderSagaCoordinator.java
│       │   │   │   └── OrderSagaConsumer.java
│       │   │   └── service/
│       │   │       ├── OrderService.java
│       │   │       └── OrderServiceImpl.java
│       │   └── resources/
│       │       ├── application.yml
│       │       └── db/migration/
│       │           └── V1__init_order_schema.sql
│       └── test/
├── inventory-service/                         # Stock Management & Optimistic Locking
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/platform/inventory/
│       │   │   ├── InventoryServiceApplication.java
│       │   │   ├── config/
│       │   │   │   ├── KafkaConfig.java
│       │   │   │   └── RedisCacheConfig.java
│       │   │   ├── controller/
│       │   │   │   └── InventoryController.java
│       │   │   ├── dto/
│       │   │   │   ├── InventoryResponse.java
│       │   │   │   └── ReserveStockRequest.java
│       │   │   ├── entity/
│       │   │   │   ├── Inventory.java
│       │   │   │   └── InventoryReservation.java
│       │   │   ├── exception/
│       │   │   │   └── GlobalExceptionHandler.java
│       │   │   ├── kafka/
│       │   │   │   └── InventoryOrderConsumer.java
│       │   │   ├── outbox/
│       │   │   │   ├── OutboxMessage.java
│       │   │   │   ├── OutboxRepository.java
│       │   │   │   └── OutboxPublisherScheduler.java
│       │   │   ├── repository/
│       │   │   │   ├── InventoryRepository.java
│       │   │   │   ├── ReservationRepository.java
│       │   │   │   └── ProcessedEventRepository.java
│       │   │   └── service/
│       │   │       ├── InventoryService.java
│       │   │       └── InventoryServiceImpl.java
│       │   └── resources/
│       │       ├── application.yml
│       │       └── db/migration/
│       │           ├── V1__init_inventory_schema.sql
│       │           └── V2__seed_inventory_data.sql
│       └── test/
├── payment-service/                           # Payment Execution & Circuit Breaker
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/platform/payment/
│       │   │   ├── PaymentServiceApplication.java
│       │   │   ├── client/
│       │   │   │   ├── MockPaymentGatewayClient.java
│       │   │   │   └── PaymentProviderResponse.java
│       │   │   ├── config/
│       │   │   │   ├── KafkaConfig.java
│       │   │   │   └── Resilience4jConfig.java
│       │   │   ├── controller/
│       │   │   │   └── PaymentController.java
│       │   │   ├── dto/
│       │   │   │   ├── PaymentRequest.java
│       │   │   │   └── PaymentResponse.java
│       │   │   ├── entity/
│       │   │   │   ├── Payment.java
│       │   │   │   ├── PaymentStatus.java
│       │   │   │   └── Refund.java
│       │   │   ├── exception/
│       │   │   │   └── GlobalExceptionHandler.java
│       │   │   ├── kafka/
│       │   │   │   └── PaymentOrderConsumer.java
│       │   │   ├── outbox/
│       │   │   │   ├── OutboxMessage.java
│       │   │   │   ├── OutboxRepository.java
│       │   │   │   └── OutboxPublisherScheduler.java
│       │   │   ├── repository/
│       │   │   │   ├── PaymentRepository.java
│       │   │   │   └── ProcessedEventRepository.java
│       │   │   └── service/
│       │   │       ├── PaymentService.java
│       │   │       └── PaymentServiceImpl.java
│       │   └── resources/
│       │       ├── application.yml
│       │       └── db/migration/
│       │           └── V1__init_payment_schema.sql
│       └── test/
├── notification-service/                      # Asynchronous Customer Notifications
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/platform/notification/
│       │   │   ├── NotificationServiceApplication.java
│       │   │   ├── config/
│       │   │   │   └── KafkaConfig.java
│       │   │   ├── kafka/
│       │   │   │   └── NotificationConsumer.java
│       │   │   └── service/
│       │   │       ├── NotificationDispatcher.java
│       │   │       └── MockEmailSmsSender.java
│       │   └── resources/
│       │       └── application.yml
│       └── test/
├── docker/                                    # Container orchestration & configs
│   ├── docker-compose.yml                     # Postgres, Kafka, Zookeeper/KRaft, Redis, Jaeger, Prometheus, Grafana
│   ├── prometheus/
│   │   └── prometheus.yml
│   └── grafana/
│       ├── dashboards/
│       └── provisioning/
└── k8s/                                       # Kubernetes manifests (Phase 6)
```

---

## 2. Package Responsibilities

1. **`controller`**:
   - Accepts HTTP requests, parses headers (`X-Idempotency-Key`, `X-Correlation-Id`).
   - Validates input using `@Valid` and Bean Validation annotations.
   - Delegates business execution immediately to the `service` layer; contains **zero business logic**.
2. **`service`**:
   - Enforces transactional boundaries (`@Transactional`).
   - Executes business logic, handles entity state mutations, and enqueues outbox records.
3. **`outbox`**:
   - Contains entity definition for the local `outbox` table.
   - Houses the scheduled publisher worker (`@Scheduled`) querying pending messages via `SELECT FOR UPDATE SKIP LOCKED` and publishing to Kafka.
4. **`repository`**:
   - Spring Data JPA repositories with custom queries and optimistic locking updates.
5. **`dto`**:
   - Request and response transfer objects strictly separated from JPA database entities. Java `record` types are used for immutability.
6. **`exception`**:
   - Central `@RestControllerAdvice` converting application exceptions into standard RFC 7807 Problem Details.
