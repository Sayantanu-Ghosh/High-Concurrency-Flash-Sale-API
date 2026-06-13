# High-Concurrency Flash Sale Backend API

This project implements a high-performance, consistent, and horizontally scalable backend for flash sales or booking systems, designed to handle massive traffic spikes without overselling.

## Architectural Blueprint

The architecture is built on a high-performance write-back strategy with asynchronous, distributed order processing to protect the database and ensure sub-millisecond response times under extreme load.

```
                  +--------------------------------+
                  |      Nginx Load Balancer       |
                  |          (Port 8080)           |
                  +--------------------------------+
                             /            \
                            /              \
                           v                v
                  +---------------+  +---------------+
                  |  App Node 1   |  |  App Node 2   |
                  |  (Port 8080)  |  |  (Port 8080)  |
                  +---------------+  +---------------+
                      |        \        /        |
                      |         \      /         |
                      |          v    v          |
                      |     +--------------+     |
                      |     | Redis Cache  |     |
                      |     | & Lua Script |     |
                      |     +--------------+     |
                      |            |             |
                      |            v             |
                      |     +--------------+     |
                      |     | Redis List   |     |
                      |     | (Order Q)    |     |
                      |     +--------------+     |
                      |            |             |
                      |            v             |
                      |     +--------------+     |
                      |     |Order Consumer|     |
                      |     +--------------+     |
                      \            |            /
                       \           v           /
                        +---------------------+
                        | PostgreSQL Database |
                        +---------------------+
```

1.  **High-Performance Write-Back Strategy**:
    *   Incoming buy requests are validated entirely in-memory using **Redis**.
    *   Real-time inventory stock is tracked in Redis.
    *   An atomic Lua script ([check_and_decr.lua](src/main/resources/redis/check_and_decr.lua)) handles the "Check User Idempotency & Decrement Stock" logic. This prevents race conditions and prevents users from purchasing more than once (1 order limit per user per item).

2.  **Distributed Asynchronous Decoupling**:
    *   Once the Lua script confirms stock availability, a `202 Accepted` response is returned immediately to the client.
    *   A lightweight order event is serialized and pushed into a distributed **Redis List** queue (`flash_sale:order_queue`).
    *   This makes the application nodes completely stateless, allowing horizontal scaling.

3.  **Resilient Batch Persistence & Competing Consumer Rollback**:
    *   A background worker ([OrderConsumer](src/main/java/com/example/flashsale/service/OrderConsumer.java)) pulls batches from the Redis queue.
    *   It attempts to write orders to **PostgreSQL** in a single optimized Hibernate batch transaction.
    *   If database insertion fails (e.g. unique constraint violation or DB connection issue), the consumer isolates the failing orders and runs a compensating rollback transaction using a Lua script ([refund_stock.lua](src/main/resources/redis/refund_stock.lua)) to refund the stock in Redis and clear the user from the purchased set.
    *   Includes a graceful shutdown hook (`@PreDestroy`) that drains any leftover queue items, ensuring zero data loss.

4.  **Automatic Cache Pre-Heating**:
    *   Upon startup, the [RedisInventoryWarmupService](src/main/java/com/example/flashsale/service/RedisInventoryWarmupService.java) queries PostgreSQL and loads active item stock into Redis, so the system is immediately ready for incoming traffic.

---

## Technology Stack
*   **Java 17**
*   **Spring Boot 3**
*   **Spring Data Redis & Lettuce**: Reactive/asynchronous Redis connectivity.
*   **PostgreSQL**: Durable storage for items, users, and orders.
*   **Docker & Docker Compose**: Local environment setup (scale ready).
*   **Nginx**: Reverse proxy and round-robin load balancer.
*   **Spring Boot Actuator & Micrometer Prometheus**: Real-time health monitoring and metric collection.

---

## Getting Started

### Prerequisites
- Docker and Docker Compose
- JDK 17+ (if running nodes locally outside Docker)

### 1. Build and Run via Docker Compose (Recommended)
This command compiles the application inside a multi-stage Docker build, and runs the entire stack (Postgres, Redis, two App replicas, and Nginx load balancer):
```sh
docker-compose up --build -d
```
The application will be accessible via Nginx on port `8080` (e.g. `http://localhost:8080`).

### 2. Verify Health
Access the Spring Boot Actuator health endpoint (which check both Database and Redis connectivity):
```sh
curl http://localhost:8080/actuator/health
```

### 3. API Endpoints

#### Purchase Item (Rate Limited & Idempotent)
- **URL**: `/api/sale/buy`
- **Method**: `POST`
- **Content-Type**: `application/json`
- **Headers**:
  *   Use different client IPs or configure custom routing. The rate limiter restricts rapid requests per client IP.
- **Request Body**:
  ```json
  {
    "userId": 1,
    "itemId": 1,
    "quantity": 1
  }
  ```
- **Responses**:
  *   `202 Accepted`: Request accepted. The order is queued and will be persisted asynchronously.
  *   `409 Conflict`: User has already purchased this item.
  *   `429 Too Many Requests`: Stock is fully depleted, or user exceeded the API rate limit (5 requests per 10 seconds).
  *   `400 Bad Request`: Invalid input (e.g., negative quantity or missing IDs).

#### Warmup / Pre-heat Inventory (Admin)
- **URL**: `/api/sale/admin/warmup`
- **Method**: `POST`
- **Description**: Reloads all item stock from the database into Redis and clears purchase sets.
- **Response**: `200 OK`

---

## Real-Time Administration & Load-Testing Dashboard

You can visually monitor the system and run load tests directly from your web browser! A premium single-page control panel is hosted inside the Spring Boot container.

### How to Access
Once your Docker containers are running (via `docker-compose up --build -d`), open your browser and navigate to:
```
http://localhost:8080/
```

### Dashboard Features
1. **Live Indicators**: Automatically updates Redis inventory stock, the pending Redis List queue size, and the PostgreSQL database order counts.
2. **Interactive Controls**:
   * **Pre-Heat Redis Cache**: Re-caches inventory and prepares the database items for traffic.
   * **Reset DB & Warm Cache**: Wipes all orders from PostgreSQL database table and refreshes Redis caches back to default state in one click (useful for repeatable load testing).
3. **Simulated Load Generator**: Configure waves of concurrent request sizes (e.g. 100, 250, or 500 requests at once) in various modes:
   * **100% Unique Users**: Simulates active rush-hour purchasing where multiple unique users attempt to buy stock.
   * **Single User Repeating Requests**: Spams requests from identical user credentials concurrently to test the Lua script's user idempotency.
   * **Mixed Mode**: 70% unique users and 30% duplicates.
4. **Instant Metrics Feed**: View live HTTP responses count (202 Success vs 409 Duplicate vs 429 Sold Out) and average request latency.

---

## Verification & Testing

The unit tests run a high-concurrency simulation of the Lua script logic in a thread-safe environment.

To run the tests:
```sh
# If maven is installed locally
mvn clean test
```
The test suite verify:
1.  **Concurrent Purchases**: 100 unique users racing for 10 items. Exactly 10 succeed, stock becomes 0, and the queue contains exactly 10 orders.
2.  **Idempotency Guarantee**: 20 rapid requests from the *same* user. Exactly 1 succeeds, 19 are rejected, and stock is decremented by exactly 1.
3.  **Mixed Waves**: Two consecutive waves of concurrent user requests.
