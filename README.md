# Ledgerly

Ledgerly is a mini core banking backend built as a microservices architecture. It focuses on money safety, concurrency control, event-driven statements, and production readiness.

## Architecture

| Service | Responsibility | Storage | Port | Status |
|---|---|---|---|---|
| api-gateway | Routing, JWT validation, rate limiting | Redis | 8080 | Planned |
| identity-service | Authentication, user registration, JWT issue | PostgreSQL | 8081 | In progress |
| ledger-service | Accounts, double-entry ledger, transfers, outbox | PostgreSQL | 8082 | Planned |
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
- Infrastructure: Docker Compose with PostgreSQL 18 Alpine (identity_db on port 5433).

### Backlog
- identity-service: Testcontainers tests.
- ledger-service: Accounts, double-entry ledger, transfers, and pessimistic locking.
- ledger-service: Transactional outbox and Kafka event publishing.
- statement-service: Kafka consumer and MongoDB statements.
- api-gateway: Spring Cloud Gateway with JWT check and Redis rate limit.
- Observability and CI: Prometheus, Grafana, and GitHub Actions pipeline.

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

### 5. Run a service
```bash
cd identity-service
../mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

### 6. Stop database
```bash
docker compose -f deploy/docker-compose.yml down
```
