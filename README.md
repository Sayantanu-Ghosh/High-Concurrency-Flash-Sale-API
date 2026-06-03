# High-Concurrency Flash Sale Backend API
This project implements a high-performance, consistent, and scalable backend for flash sales or booking systems, designed to handle massive traffic spikes without overselling.

## Architectural Blueprint
The architecture is built on a high-performance write-back strategy with asynchronous order processing to protect the database and ensure a responsive user experience.

1.  **High-Performance Write-Back Strategy**:
    *   Incoming buy requests never hit the PostgreSQL database directly.
    *   Real-time inventory stock is cached and validated entirely in Redis.
    *   A Redis Lua script handles the "Check Stock & Decrement" logic atomically to eliminate race conditions.

2.  **Asynchronous Decoupling**:
    *   Once the Lua script confirms stock availability, a `202 Accepted` response is immediately returned to the user.
    *   A lightweight order event is pushed into an asynchronous execution queue (a local `LinkedBlockingQueue` managed by a thread pool).
    *   A background worker (`OrderConsumer`) pulls from this queue and batch-writes the final orders to PostgreSQL, preventing database connection starvation.

## Technology Stack
*   **Java 21**
*   **Spring Boot 3**
*   **Redis**: For high-speed, atomic inventory management.
*   **PostgreSQL**: For persistent storage of order data.
*   **Docker & Docker Compose**: For local development environment setup.
*   **Maven**: For dependency management.

## Getting Started

### Prerequisites
- Java 21+
- Docker and Docker Compose

### 1. Launch Local Environment
Start the required PostgreSQL and Redis containers:
```sh
docker-compose up -d
```

### 2. Initialize Database and Inventory
The application uses `schema.sql` to set up the database tables on startup. After the application starts for the first time, you can use a tool like `redis-cli` to initialize the inventory stock in Redis.

For example, to set the stock for `itemId` 1 to 100:
```sh
redis-cli
> SET inventory:1 100
```

### 3. Run the Application
```sh
./mvnw spring-boot:run
```
The API will be available at `http://localhost:8080`.

## API Endpoint

### Place an Order
- **URL**: `/api/sale/buy`
- **Method**: `POST`
- **Content-Type**: `application/json`
- **Body**:
  ```json
  {
    "userId": 1,
    "itemId": 1,
    "quantity": 1
  }
  ```

- **Success Responses**:
  - `202 Accepted`: The request has been accepted for processing. The order will be created asynchronously.
  - `429 Too Many Requests`: Stock is depleted for the requested item.
  - `400 Bad Request`: Invalid request data.

## File Structure
The project follows a standard Maven layout. Key files include:
- `docker-compose.yml`: Sets up local PostgreSQL and Redis containers.
- `src/main/resources/application.properties`: Configures the database, Redis, and application settings.
- `src/main/resources/schema.sql`: Defines the PostgreSQL database schema.
- `src/main/resources/redis/check_and_decr.lua`: The atomic Lua script for inventory control.
- `com.example.flashsale.config.RedisConfig`: Configures Redis connection pools and loads the Lua script.
- `com.example.flashsale.controller.FlashSaleController`: The high-throughput API endpoint.
- `com.example.flashsale.service.InventoryService`: Executes the atomic Lua script.
- `com.example.flashsale.service.OrderConsumer`: The asynchronous worker that saves orders to PostgreSQL.
- `com.example.flashsale.service.OrderQueueService`: A simple in-memory queue for decoupling.
