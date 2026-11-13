# Ledgerly

Ledgerly is a mini core banking backend built as a microservices architecture. It focuses on money safety, concurrency control, event-driven statements, and production readiness.

## Architecture

| Service | Responsibility | Storage | Port | Status |
|---|---|---|---|---|
| api-gateway | Routing, JWT validation, rate limiting | Redis | 8080 | Planned |
| identity-service | Authentication, user registration, JWT issue | PostgreSQL | 8081 | Completed |
| ledger-service | Accounts, double-entry ledger, transfers, outbox | PostgreSQL | 8082 | In Progress |
| statement-service | Event consumer, statements, CSV exports | MongoDB | 8083 | Planned |

## Tech Baseline

- Java 25
- Spring Boot 4
- PostgreSQL 18 (Alpine)
- Flyway migrations
- Maven wrapper (multi-module)
- Code formatter: formatter-maven-plugin

## Features

### Completed
- Multi-module Maven structure with parent POM and formatter plugin.
- common-lib: BaseEntity (UUID v7, JPA auditing, @Version) and GlobalExceptionHandler (ProblemDetail).
- identity-service: module skeleton, virtual threads, UTC timezone, and Hikari pool.
- identity-service: User entity, Role enum, UserRepository, and Flyway migration V001__create_users.sql (case-insensitive unique email index and format check constraint).
- identity-service: Register and login endpoints with Jakarta Validation, BCrypt password hashing, and ProblemDetail error handling.
- identity-service: JWT token generation (JwtService), Spring Security configuration, and GET /api/v1/users/me endpoint.
- identity-service: OpenAPI 3.1 documentation and interactive Swagger UI with JWT Bearer authentication support.
- identity-service: Dev data seeder (UserDataSeeder) using Spring Data JPA for 2 Admins and 3 Customers.
- identity-service: Asynchronous welcome notification dispatching (@Async) using Java 25 virtual threads on user registration.
- identity-service: Testcontainers 2.x configured with PostgreSQL 18 container.
- identity-service: AuthService and JwtService unit tests with JUnit 5 and Mockito.
- identity-service: End-to-end integration tests (AuthIntegrationTest) with Testcontainers and PostgreSQL 18.
- Infrastructure: Docker Compose with PostgreSQL 18 Alpine (identity_db on port 5433, ledger_db on port 5436).
- CI: GitHub Actions workflow with format check, compilation, and verify.
- ledger-service: Module bootstrap with virtual threads, UTC timezone, Hikari pool, and Testcontainers PostgreSQL 18 context bootstrap.
- ledger-service: Money value object with discrete minor units math, ISO 4217 currency safety, and unit tests (MoneyTest).
- ledger-service: Flyway schema migration (V001__create_ledger_tables.sql) for accounts, transfers, and ledger entries with engine-level balance checks and composite indices.
- ledger-service: Domain entities (Account, Transfer, LedgerEntry) with rich DDD behaviors, enums, and domain exceptions.
- ledger-service: Unit tests across domain models (MoneyTest, AccountTest, TransferTest, LedgerEntryTest).

### Backlog
- ledger-service: Core repositories, transfer validation rules, and double-entry transfer engine.
- ledger-service: Pessimistic locking, transactional outbox, and Kafka event publishing.
- statement-service: Kafka consumer and MongoDB statements.
- api-gateway: Spring Cloud Gateway with JWT check and Redis rate limit.
- Observability: Prometheus and Grafana dashboards.

## Dev Seed Credentials

When running with `-Dspring-boot.run.profiles=dev`, the following test users are automatically seeded with password `Password123!`:

| Role | Email | Name | Password |
|---|---|---|---|
| `ADMIN` | `admin1@ledgerly.com` | Admin One | `Password123!` |
| `ADMIN` | `admin2@ledgerly.com` | Admin Two | `Password123!` |
| `USER` | `ahmed@ledgerly.com` | Ahmed Mordi | `Password123!` |
| `USER` | `bob@ledgerly.com` | Bob Jones | `Password123!` |
| `USER` | `charlie@ledgerly.com` | Charlie Brown | `Password123!` |

## Local Development

### 1. Clone the repository
```bash
git clone https://github.com/ahmedmmordi/ledgerly.git
cd ledgerly
```

### 2. Configure environment
```bash
cp deploy/.env.example deploy/.env
```

### 3. Start database
```bash
docker compose -f deploy/docker-compose.yml up -d
```

### 4. Build and verify
```bash
./mvnw formatter:format
./mvnw verify
```

### 5. Run services

Each microservice runs independently with its own database and port:

- **Identity Service** (Port 8081, DB Port 5433):
  ```bash
  cd identity-service
  ../mvnw spring-boot:run -Dspring-boot.run.profiles=dev
  ```

- **Ledger Service** (Port 8082, DB Port 5436):
  ```bash
  cd ledger-service
  ../mvnw spring-boot:run -Dspring-boot.run.profiles=dev
  ```

Both services can run concurrently in separate terminal sessions.

### 6. Stop database
```bash
docker compose -f deploy/docker-compose.yml down
```
