# Ledgerly

Ledgerly is a mini core banking backend built with a **microservices architecture** on **Java 25** and **Spring Boot 4**. It is engineered for strict money safety, deadlock-free concurrency, double-entry bookkeeping, and production readiness.

---

## Microservices

| Service | Responsibility | Storage | Port | Status |
|---|---|---|---|---|
| **api-gateway** | Routing, JWT validation, Redis rate limiting | Redis | 8080 | Active |
| **identity-service** | User registration, authentication, JWT issuing | PostgreSQL (Port 5433) | 8081 | Active |
| **ledger-service** | Accounts, double-entry ledger, atomic transfers, outbox | PostgreSQL (Port 5436) | 8082 | Active |
| **statement-service** | Kafka event consumer, monthly statements, CSV exports | MongoDB (Port 27018) | 8083 | Active |

---

## Tech Baseline

* **Runtime:** Java 25 (Virtual Threads enabled via `spring.threads.virtual.enabled=true`)
* **Framework:** Spring Boot 4 (Multi-module Maven)
* **Databases & Cache:** PostgreSQL 18 (Alpine) per relational microservice, MongoDB 7 (statement-service), Redis 7 (api-gateway rate limiting), Flyway schema migrations
* **ORM & Data Access:** Spring Data JPA / Hibernate 7, Spring Data MongoDB, Spring Data Redis Reactive
* **Messaging:** Apache Kafka for event-driven transactional outbox publishing
* **API Gateway:** Spring Cloud Gateway 5 (WebFlux) with JWT authentication and Redis token bucket rate limiting
* **Observability & Metrics:** Spring Boot Actuator (liveness/readiness probes, metrics), Micrometer Prometheus, Prometheus TSDB, Grafana, and distributed request correlation via SLF4J MDC
* **Testing:** JUnit 5, AssertJ, Mockito, Testcontainers (PostgreSQL 18, Kafka 4.3.1, MongoDB 7)
* **API Docs:** OpenAPI 3 & Swagger UI via `springdoc-openapi`
* **Code Formatter:** `formatter-maven-plugin` (runs on `validate` and CI)

---

## Interactive API Documentation (Swagger UI)

When running locally, each service exposes an interactive Swagger UI with JWT Bearer authentication support:

* **Identity Service UI:** [http://localhost:8081/swagger](http://localhost:8081/swagger) (OpenAPI spec: `/api-docs`)
* **Ledger Service UI:** [http://localhost:8082/swagger](http://localhost:8082/swagger) (OpenAPI spec: `/api-docs`)
* **Statement Service UI:** [http://localhost:8083/swagger](http://localhost:8083/swagger) (OpenAPI spec: `/api-docs`)

---

## Observability & Monitoring

Ledgerly includes an out-of-the-box monitoring and observability stack:

* **Prometheus:** [http://localhost:9091](http://localhost:9091) (Scrapes `/actuator/prometheus` on all 4 microservices every 5 seconds, view scrape targets and live UP/DOWN status at [http://localhost:9091/targets](http://localhost:9091/targets)).
* **Grafana Dashboard:** [http://localhost:3001/d/ledgerly](http://localhost:3001/d/ledgerly) (Credentials: `admin` / `admin`). Pre-provisioned with the **Ledgerly Banking Platform** dashboard (auto-refreshes charts every 5 seconds, 1-hour time window).
* **Distributed Request Correlation:** Automatic `X-Correlation-Id` generation at `api-gateway` and propagation to downstream SLF4J MDC across all services, ensuring every log entry is grep-searchable by request ID.

---

## Dev Seed Credentials & Test Data

When running with the `dev` profile (`-Dspring.profiles.active=dev`), the following test data is automatically seeded:

### Test Users (`identity-service`)
All pre-seeded users use password **`Password123!`**:

| Role | Email | Name | Password |
|---|---|---|---|
| `ADMIN` | `admin1@ledgerly.com` | Admin One | `Password123!` |
| `ADMIN` | `admin2@ledgerly.com` | Admin Two | `Password123!` |
| `USER` | `ahmed@ledgerly.com` | Ahmed Mordi | `Password123!` |
| `USER` | `bob@ledgerly.com` | Bob Jones | `Password123!` |
| `USER` | `charlie@ledgerly.com` | Charlie Brown | `Password123!` |

### Pre-Funded Accounts (`ledger-service`)

| Account Type | Owner / Holder | Currency | Initial Balance | User UUID |
|---|---|---|---|---|
| `SYSTEM` Vault | Cash Vault (`SYSUSD00000001`) | `USD` | System Source | `00000000-0000-0000-0000-000000000000` |
| `CUSTOMER` | Ahmed Mordi | `USD` | $10,000.00 (`1,000,000` cents) | `0193297a-9a90-7888-8123-000000000001` |
| `CUSTOMER` | Bob Jones | `USD` | $5,000.00 (`500,000` cents) | `0193297a-9a90-7888-8123-000000000002` |
| `CUSTOMER` | Charlie Brown | `USD` | $2,500.00 (`250,000` cents) | `0193297a-9a90-7888-8123-000000000003` |

---

## Local Development & Getting Started

### 1. Prerequisites
* **Java 25**
* **Docker** & **Docker Compose**
* Maven (or the bundled `./mvnw` wrapper)

### 2. Configure Environment
```bash
cp deploy/.env.example deploy/.env
```

### 3. Start the Complete Platform
Start all the interconnected containers (the 4 Java microservices, databases, Kafka, Redis cache, Prometheus scraper, and Grafana):
```bash
# Build images and start all the containers in background
docker compose -f deploy/docker-compose.yml up -d --build

# Verify all the containers are healthy
docker compose -f deploy/docker-compose.yml ps
```
* **API Gateway**: mapped to host port **8080** (single entry point for all client traffic)
* **Grafana Dashboard**: `http://localhost:3001/d/ledgerly` (login: `admin` / `admin`)
* **Prometheus Targets**: `http://localhost:9091/targets` (auto-scrapes all 4 microservices)
* `identity_db`: mapped to host port **5433** (container 5432)
* `ledger_db`: mapped to host port **5436** (container 5432)
* `statement-db`: mapped to host port **27018** (container 27017)
* `kafka`: broker mapped to host port **9093** (internal 9092)
* `redis`: cache and rate limiter mapped to host port **6380** (container 6379)
* `prometheus`: metrics scraper mapped to host port **9091** (container 9090)
* `grafana`: dashboards mapped to host port **3001** (container 3000)

### 4. Build and Verify
Run formatting, compilation, and tests across all modules:
```bash
# Auto-format Java code
./mvnw formatter:format

# Run tests
./mvnw test

# Full check: format check, compilation, and verify
./mvnw verify
```

### 5. Alternative: Run Microservices Standalone

If developing locally without running the application containers, start only the infrastructure in Docker, then run each service independently using the `dev` profile:

* **API Gateway** (Port `8080`, Redis Port `6380`):
  ```bash
  cd api-gateway
  ../mvnw spring-boot:run -Dspring-boot.run.profiles=dev
  ```

* **Identity Service** (Port `8081`, DB Port `5433`):
  ```bash
  cd identity-service
  ../mvnw spring-boot:run -Dspring-boot.run.profiles=dev
  ```

* **Ledger Service** (Port `8082`, DB Port `5436`):
  ```bash
  cd ledger-service
  ../mvnw spring-boot:run -Dspring-boot.run.profiles=dev
  ```

* **Statement Service** (Port `8083`, DB Port `27018`):
  ```bash
  cd statement-service
  ../mvnw spring-boot:run -Dspring-boot.run.profiles=dev
  ```

All four services can run concurrently in separate terminal sessions. Requests from clients enter via the **API Gateway** on port `8080` and are authenticated and rate-limited before routing downstream.

### 6. Manage Infrastructure Containers

To pause containers without losing database state:
```bash
docker compose -f deploy/docker-compose.yml stop
```

To resume paused containers:
```bash
docker compose -f deploy/docker-compose.yml start
```

To stop and remove containers:
```bash
docker compose -f deploy/docker-compose.yml down
```
