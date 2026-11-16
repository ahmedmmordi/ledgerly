# Ledgerly

Ledgerly is a mini core banking backend built with a **microservices architecture** on **Java 25** and **Spring Boot 4**. It is engineered for strict money safety, deadlock-free concurrency, double-entry bookkeeping, and production readiness.

---

## Services

| Service | Responsibility | Storage | Port | Status |
|---|---|---|---|---|
| **api-gateway** | Routing, JWT validation, Redis rate limiting | Redis | 8080 | Planned |
| **identity-service** | User registration, authentication, JWT issuing | PostgreSQL (Port 5433) | 8081 | Active |
| **ledger-service** | Accounts, double-entry ledger, atomic transfers, outbox | PostgreSQL (Port 5436) | 8082 | Active |
| **statement-service** | Kafka event consumer, monthly statements, CSV exports | MongoDB (Port 27018) | 8083 | Planned |

---

## Tech Baseline

* **Runtime:** Java 25 (Virtual Threads enabled via `spring.threads.virtual.enabled=true`)
* **Framework:** Spring Boot 4 (Multi-module Maven)
* **Databases:** PostgreSQL 18 (Alpine) per microservice, Flyway schema migrations
* **ORM:** Spring Data JPA / Hibernate 7
* **Messaging:** Apache Kafka for event-driven transactional outbox publishing
* **Testing:** JUnit 5, AssertJ, Mockito, Testcontainers (PostgreSQL 18, Kafka)
* **API Docs:** OpenAPI 3 & Swagger UI via `springdoc-openapi`
* **Code Formatter:** `formatter-maven-plugin` (runs on `validate` and CI)

---

## Interactive API Documentation (Swagger UI)

When running locally, each service exposes an interactive Swagger UI with JWT Bearer authentication support:

* **Identity Service UI:** [http://localhost:8081/swagger](http://localhost:8081/swagger) (OpenAPI spec: `/api-docs`)
* **Ledger Service UI:** [http://localhost:8082/swagger](http://localhost:8082/swagger) (OpenAPI spec: `/api-docs`)

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

### 3. Start Infrastructure Containers
Start PostgreSQL databases and Kafka event broker:
```bash
docker compose -f deploy/docker-compose.yml up -d
```
* `identity_db`: mapped to host port **5433** (container 5432)
* `ledger_db`: mapped to host port **5436** (container 5432)
* `kafka`: broker mapped to host port **9093** (internal 9092)

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

### 5. Run Microservices

Each microservice runs independently with its own database and port:

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

Both services can run concurrently in separate terminal sessions.

### 6. Stop Infrastructure Containers
```bash
docker compose -f deploy/docker-compose.yml down
```
