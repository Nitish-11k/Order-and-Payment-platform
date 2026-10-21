# REST API Contracts & RFC 7807 Specification

## 1. Global API Standards

### 1.1 Standard HTTP Headers

| Header | Required | Format | Purpose |
| :--- | :--- | :--- | :--- |
| `Authorization` | Yes (for protected endpoints) | `Bearer <jwt_token>` | JWT containing user identity (`sub`, `roles`) |
| `X-Idempotency-Key` | Yes (on `POST` / state mutation) | `UUIDv4` string | Prevents duplicate processing on retries/network hiccups |
| `X-Correlation-Id` | Optional (Gateway generates if missing)| `UUIDv4` string | Distributed trace correlation across microservices |
| `Content-Type` | Yes | `application/json` | Request payload format |
| `Accept` | Optional | `application/json` | Expected response format |

### 1.2 RFC 7807 Problem Details for Error Responses

All error responses from any service or the API Gateway strictly adhere to **RFC 7807 Problem Details** (`application/problem+json`):

```json
{
  "type": "https://api.platform.com/errors/insufficient-inventory",
  "title": "Insufficient Inventory",
  "status": 409,
  "detail": "Requested 3 units for SKU-MACBOOK-AIR, but only 1 unit is available.",
  "instance": "/api/v1/orders",
  "timestamp": "2026-10-21T11:50:00.120Z",
  "correlationId": "corr-uuid-889900",
  "invalidParams": [
    {
      "field": "items[0].quantity",
      "rejectedValue": 3,
      "reason": "Exceeds current stock"
    }
  ]
}
```

#### Standard HTTP Status Code Mapping:
- **400 Bad Request**: JSON deserialization failure, Bean Validation constraints violated.
- **401 Unauthorized**: Missing, expired, or invalid JWT signature.
- **403 Forbidden**: Authenticated user lacks required roles/permissions.
- **404 Not Found**: Entity (Order, Inventory SKU, Payment) not found.
- **409 Conflict**: Optimistic lock version conflict (`OptimisticLockingFailureException`) or Idempotency key conflict with mismatching payload.
- **422 Unprocessable Entity**: Business rule violation (e.g. order already completed, cannot cancel).
- **429 Too Many Requests**: Redis Token-Bucket rate limit exceeded.
- **500 Internal Server Error**: Unexpected exception, logged with stacktrace and correlation ID.
- **503 Service Unavailable**: Downstream circuit breaker is OPEN (Resilience4j).

---

## 2. API Gateway & Authentication Endpoints

### 2.1 User Login
- **Endpoint**: `POST /api/v1/auth/login`
- **Request**:
```json
{
  "username": "alice",
  "password": "Password123!"
}
```
- **Response**: `200 OK`
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "tokenType": "Bearer",
  "expiresInSeconds": 3600,
  "userId": "cust-501",
  "roles": ["ROLE_CUSTOMER"]
}
```

---

## 3. Order Service Endpoints (`order-service` :8081)

### 3.1 Create Order (Initiate Saga)
- **Endpoint**: `POST /api/v1/orders`
- **Headers**:
  - `Authorization: Bearer <jwt>`
  - `X-Idempotency-Key: e8b9487c-1792-4fcf-8409-b68df567a1c3`
- **Request**:
```json
{
  "customerId": "cust-501",
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
```
- **Response**: `201 Created`
```json
{
  "orderId": "ord-2026-0001",
  "customerId": "cust-501",
  "totalAmount": 199.97,
  "currency": "USD",
  "status": "PENDING",
  "idempotencyKey": "e8b9487c-1792-4fcf-8409-b68df567a1c3",
  "createdAt": "2026-10-21T11:50:00.000Z",
  "links": {
    "self": "/api/v1/orders/ord-2026-0001"
  }
}
```

### 3.2 Get Order By ID
- **Endpoint**: `GET /api/v1/orders/{orderId}`
- **Response**: `200 OK`
```json
{
  "orderId": "ord-2026-0001",
  "customerId": "cust-501",
  "totalAmount": 199.97,
  "currency": "USD",
  "status": "CONFIRMED",
  "failureReason": null,
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
  "createdAt": "2026-10-21T11:50:00.000Z",
  "updatedAt": "2026-10-21T11:50:03.200Z"
}
```

### 3.3 Cancel Order
- **Endpoint**: `POST /api/v1/orders/{orderId}/cancel`
- **Request**:
```json
{
  "reason": "Customer requested cancellation"
}
```
- **Response**: `200 OK` (or `202 Accepted`)
```json
{
  "orderId": "ord-2026-0001",
  "status": "CANCELLATION_REQUESTED",
  "message": "Saga compensating workflow initiated."
}
```

---

## 4. Inventory Service Endpoints (`inventory-service` :8082)

### 4.1 Get Inventory by SKU (Redis Cached)
- **Endpoint**: `GET /api/v1/inventory/{sku}`
- **Cache-Control**: `max-age=60` (or managed via Redis Cache-Aside with invalidation on updates)
- **Response**: `200 OK`
```json
{
  "sku": "SKU-MACBOOK-AIR",
  "productName": "Apple MacBook Air M3 13\"",
  "availableQuantity": 49,
  "reservedQuantity": 1,
  "version": 4
}
```

### 4.2 Manual / Direct Reserve (Internal Testing)
- **Endpoint**: `POST /api/v1/inventory/reserve`
- **Request**:
```json
{
  "orderId": "ord-2026-0001",
  "items": [
    {
      "sku": "SKU-MACBOOK-AIR",
      "quantity": 1
    }
  ]
}
```
- **Response**: `200 OK`
```json
{
  "orderId": "ord-2026-0001",
  "status": "RESERVED",
  "reservedAt": "2026-10-21T11:50:01.000Z"
}
```

---

## 5. Payment Service Endpoints (`payment-service` :8083)

### 5.1 Get Payment Status by Order ID
- **Endpoint**: `GET /api/v1/payments/order/{orderId}`
- **Response**: `200 OK`
```json
{
  "paymentId": "pay-009128",
  "orderId": "ord-2026-0001",
  "customerId": "cust-501",
  "amount": 199.97,
  "currency": "USD",
  "status": "COMPLETED",
  "providerTransactionId": "mock-tx-88192837",
  "failureReason": null,
  "processedAt": "2026-10-21T11:50:02.380Z"
}
```

### 5.2 Direct Payment Charge (Testing Endpoint)
- **Endpoint**: `POST /api/v1/payments/charge`
- **Headers**: `X-Idempotency-Key: idemp-pay-1122`
- **Request**:
```json
{
  "orderId": "ord-2026-0001",
  "customerId": "cust-501",
  "amount": 199.97,
  "currency": "USD"
}
```
- **Response**: `200 OK`
```json
{
  "paymentId": "pay-009128",
  "status": "COMPLETED",
  "transactionId": "mock-tx-88192837"
}
```
