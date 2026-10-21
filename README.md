# Distributed Event-Driven Order & Payments Platform

Production-grade, event-driven e-commerce microservices platform built with **Java 21**, **Spring Boot 3.3.x**, **Apache Kafka (KRaft)**, **PostgreSQL (Database-per-Service)**, and **Redis**.

---

## Architecture & Design Documentation

All architectural decisions, event contracts, database schemas, and API specifications are documented under [`docs/architecture/`](file:///c:/Users/dell/Documents/python/PaymentMethods/docs/architecture):

- **[System Architecture & Sequence Flows](file:///c:/Users/dell/Documents/python/PaymentMethods/docs/architecture/ARCHITECTURE.md)**: High-level topology, Happy path saga flow, and Compensating transaction sequences.
- **[Kafka Event Catalog & Schemas](file:///c:/Users/dell/Documents/python/PaymentMethods/docs/architecture/EVENT_CATALOG.md)**: Event definitions, CloudEvents envelope, partition keys, and DLT routing.
- **[Database Schemas & Flyway Migrations](file:///c:/Users/dell/Documents/python/PaymentMethods/docs/architecture/DATABASE_SCHEMAS.md)**: Isolated database designs, Flyway migration rules, optimistic locking, and outbox tables.
- **[REST API Contracts & RFC 7807](file:///c:/Users/dell/Documents/python/PaymentMethods/docs/architecture/API_CONTRACTS.md)**: REST endpoints, HTTP idempotency headers, and error models.
- **[Architecture Decision Records (ADRs)](file:///c:/Users/dell/Documents/python/PaymentMethods/docs/architecture/ADR.md)**: Tradeoff analysis for Saga Orchestration, Transactional Outbox, Optimistic Locking, and Circuit Breakers.
- **[Multi-Module Folder Structure](file:///c:/Users/dell/Documents/python/PaymentMethods/docs/architecture/FOLDER_STRUCTURE.md)**: Directory layout and package responsibilities.

---

## Prerequisites

Before starting, ensure you have the following installed on your machine:

1. **Java Development Kit (JDK) 21**
   - Check with: `java -version`
   - Must be JDK 21 or later.
2. **Docker & Docker Compose** (Docker Desktop, Rancher Desktop, or Podman)
   - Check with: `docker --version` and `docker compose version`
3. **Git**
   - Check with: `git --version`

> [!NOTE]
> You do **not** need Maven pre-installed. The project includes an auto-bootstrapping Maven Wrapper (`mvnw` on Linux/macOS, `mvnw.cmd` on Windows).

---

## Quickstart: Step-by-Step Installation & Setup

### Step 1: Clone the Repository (If not already in the project directory)

```bash
git clone <repository-url>
cd PaymentMethods
```

---

### Step 2: Start Infrastructure with Docker Compose

Run the provided Docker Compose file to start PostgreSQL, Kafka (KRaft mode), Redis, and Kafka UI:

```bash
docker compose -f docker/docker-compose.yml up -d
```

Verify that all containers are healthy:

```bash
docker compose -f docker/docker-compose.yml ps
```

You should see:
- `platform-postgres` (Port `5432`): Automatically executes `docker/postgres/init-databases.sql` to initialize:
  - `order_db`
  - `inventory_db`
  - `payment_db`
  - `notification_db`
- `platform-kafka` (Port `9092` external, `29092` internal broker)
- `platform-redis` (Port `6379`)
- `platform-kafka-ui` (Web interface on Port `8085`)

---

### Step 3: Build the Multi-Module Project

Using the included Maven Wrapper:

#### On Windows (PowerShell or Command Prompt):
```powershell
.\mvnw.cmd clean compile
```

#### On Linux / macOS:
```bash
./mvnw clean compile
```

To run a full package build with unit and integration tests:
```bash
.\mvnw.cmd clean verify
```

---

### Step 4: Run the Microservices

You can run each microservice from your terminal, IDE (IntelliJ IDEA / VS Code / Eclipse), or as Spring Boot applications.

#### Terminal Commands:

1. **Order Service** (Port `8081`):
   ```powershell
   .\mvnw.cmd spring-boot:run -pl order-service
   ```

2. **Inventory Service** (Port `8082`):
   ```powershell
   .\mvnw.cmd spring-boot:run -pl inventory-service
   ```

3. **Payment Service** (Port `8083`):
   ```powershell
   .\mvnw.cmd spring-boot:run -pl payment-service
   ```

4. **API Gateway** (Port `8080`):
   ```powershell
   .\mvnw.cmd spring-boot:run -pl api-gateway
   ```

---

## Infrastructure Port & Credential Matrix

| Service | Port | Host URL / Connection String | Credentials / Info |
| :--- | :--- | :--- | :--- |
| **PostgreSQL** | `5432` | `jdbc:postgresql://localhost:5432/<db_name>` | User: `postgres`<br>Password: `postgrespassword` |
| **Kafka Broker** | `9092` | `localhost:9092` | Plaintext (KRaft mode) |
| **Kafka Web UI** | `8085` | [http://localhost:8085](http://localhost:8085) | Monitor topics, partitions, and consumer lag |
| **Redis** | `6379` | `localhost:6379` | Default (no password required locally) |
| **Order Service** | `8081` | [http://localhost:8081/swagger-ui.html](http://localhost:8081/swagger-ui.html) | OpenAPI Docs & Actuator |
| **Inventory Service** | `8082` | [http://localhost:8082/swagger-ui.html](http://localhost:8082/swagger-ui.html) | OpenAPI Docs & Actuator |
| **Payment Service** | `8083` | [http://localhost:8083/swagger-ui.html](http://localhost:8083/swagger-ui.html) | OpenAPI Docs & Actuator |
| **API Gateway** | `8080` | [http://localhost:8080](http://localhost:8080) | Edge entry point & Rate Limiter |

---

## Direct Database Access

You can connect to any of the isolated databases using `psql` or any GUI client (DBeaver, pgAdmin, IntelliJ Database Tool):

```bash
# Connect to order_db
docker exec -it platform-postgres psql -U postgres -d order_db

# Connect to inventory_db
docker exec -it platform-postgres psql -U postgres -d inventory_db

# Connect to payment_db
docker exec -it platform-postgres psql -U postgres -d payment_db
```

### Useful SQL Inspection Queries:

```sql
-- Check Flyway applied migrations
SELECT version, description, type, script, installed_on, success FROM flyway_schema_history;

-- Check inventory seed items and version
SELECT id, sku, product_name, available_quantity, reserved_quantity, version FROM inventory;

-- Check pending transactional outbox events
SELECT id, event_type, status, retry_count, created_at FROM outbox WHERE status = 'PENDING';
```

---

## Project Modules

```
PaymentMethods/
├── common-events/          # Shared event records (CloudEvents) & RFC 7807 problem details
├── api-gateway/            # Spring Cloud Gateway (JWT validation, Redis rate limiter)
├── order-service/          # Saga Orchestrator, Order lifecycle, Outbox poller
├── inventory-service/      # Optimistic locking stock reservation, Redis cache
├── payment-service/        # Mock payment provider, Resilience4j circuit breaker
├── notification-service/   # Asynchronous notification consumer
├── docker/                 # Docker Compose & PostgreSQL init scripts
└── docs/                   # Architecture blueprints, event catalog, ADRs
```

---

## Troubleshooting

### 1. PostgreSQL container fails to initialize all databases
If Docker created `postgres_data` before the `init-databases.sql` script was mounted, reset the volume:
```bash
docker compose -f docker/docker-compose.yml down -v
docker compose -f docker/docker-compose.yml up -d
```

### 2. Maven Wrapper permission denied on Linux/macOS
Ensure execution permissions are granted to the script:
```bash
chmod +x mvnw
```

### 3. Port Conflict (e.g. Port 5432 or 6379 already in use)
If you have a local PostgreSQL or Redis service running, either stop the local service or customize the host port mappings in `docker/docker-compose.yml` (e.g., `"5433:5432"`).
