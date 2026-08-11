# notification-service

A multi-tenant notification service that sends notifications across four channels
(Email, SMS, Push, In-App), with per-tenant rate limiting, exponential-backoff retry,
dead letter queue, idempotency-key support, full audit trail, and role-based access control.

## Tech Stack

- Java 17, Spring Boot 4.1.0 (Spring Framework 7 / Spring Security 7)
- PostgreSQL (persistence), Redis (rate limiting, idempotency keys)
- Spring Data JPA, Spring Security, Jackson 3
- JUnit 5, Mockito, AssertJ, Awaitility, Testcontainers

## Quick Start

### Prerequisites

- Docker (for PostgreSQL + Redis)
- Java 17+
- Maven 3.8+

### Running the Application

```bash
# Start Postgres and Redis
docker-compose up -d

# Start the app (on http://localhost:8080)
mvn spring-boot:run
```

Schema is auto-created on first boot via `spring.jpa.hibernate.ddl-auto=update`.

### Running Tests

```bash
mvn test
```

Tests spin up their own PostgreSQL/Redis containers via Testcontainers; Docker must be running.
No additional setup needed.

## Authentication

HTTP Basic auth with three test users:

| Username | Password | Role |
|---|---|---|
| `admin@platform.com` | `admin123` | `PLATFORM_ADMIN` |
| `tenant1@example.com` | `tenant123` | `TENANT_ADMIN` |
| `tenant2@example.com` | `tenant123` | `TENANT_ADMIN` |

- `PLATFORM_ADMIN`: manage tenants
- `TENANT_ADMIN`: manage templates, send notifications, access DLQ
- `GET /api/health`: public (no auth required)

## API

### Notifications

| Method | Path | Role | Purpose |
|---|---|---|---|
| POST | `/api/notifications/send` | TENANT_ADMIN | Send a notification |
| GET | `/api/notifications/{jobId}` | TENANT_ADMIN | Get job status + delivery attempts |
| GET | `/api/notifications/report` | TENANT_ADMIN | List jobs (paginated, filterable by status/date) |
| POST | `/api/notifications/{jobId}/retry` | TENANT_ADMIN | Retry a failed job |
| DELETE | `/api/notifications/{jobId}` | TENANT_ADMIN | Cancel a scheduled job |

### Templates

| Method | Path | Role | Purpose |
|---|---|---|---|
| POST | `/api/templates` | TENANT_ADMIN | Create template |
| GET | `/api/templates` | TENANT_ADMIN | List templates |
| GET | `/api/templates/{id}` | TENANT_ADMIN | Get template |
| PUT | `/api/templates/{id}` | TENANT_ADMIN | Update template |
| DELETE | `/api/templates/{id}` | TENANT_ADMIN | Delete template |

### Tenants (Admin)

| Method | Path | Role | Purpose |
|---|---|---|---|
| POST | `/api/tenants` | PLATFORM_ADMIN | Create tenant |
| GET | `/api/tenants/{id}` | PLATFORM_ADMIN | Get tenant |
| PUT | `/api/tenants/{id}/limits` | PLATFORM_ADMIN | Update rate limits |
| DELETE | `/api/tenants/{id}` | PLATFORM_ADMIN | Delete tenant |

### Dead Letter Queue

| Method | Path | Role | Purpose |
|---|---|---|---|
| GET | `/api/dlq` | TENANT_ADMIN | List dead-lettered jobs |
| POST | `/api/dlq/{jobId}/retry` | TENANT_ADMIN | Retry a dead-lettered job |
| DELETE | `/api/dlq/{jobId}` | TENANT_ADMIN | Permanently delete a job |

### Health

| Method | Path | Role | Purpose |
|---|---|---|---|
| GET | `/api/health` | public | Health check |

All error responses use a consistent format: `{ error, code, timestamp, path, details }`.

## Idempotency

`POST /api/notifications/send` accepts an optional `X-Idempotency-Key` header.
If provided, the same key sent twice within 24 hours returns the original job (no duplicate created).
Useful for safely retrying after a network timeout.

```bash
curl -X POST http://localhost:8080/api/notifications/send \
  -H 'X-Idempotency-Key: order-123-send-1' \
  -H 'Content-Type: application/json' \
  -u tenant1@example.com:tenant123 \
  -d '{
    "tenantId": "550e8400-e29b-41d4-a716-446655440000",
    "templateId": "550e8400-e29b-41d4-a716-446655440001",
    "recipientEmail": "user@example.com",
    "variables": {"userName": "Alice"}
  }'
```

Second request with the same key returns the original job immediately (no new send).

## Architecture

The service is organized into layers:

```
src/main/java/com/example/notificationservice/
├── domain/       JPA entities (Tenant, NotificationTemplate, NotificationJob, etc.)
├── repository/   Spring Data repositories
├── service/      Business logic (send, retry, rate limiting, audit)
├── controller/   REST endpoints
├── config/       Security, thread pool configuration
├── channel/      Notification channel abstraction (Email, SMS, Push, In-App)
├── dto/          Request/response DTOs
├── event/        Application events (MaxRetriesExceededEvent)
└── exception/    Custom exceptions, global error handler

src/test/java/com/example/notificationservice/
├── service/      Unit tests + service-layer integration tests
├── controller/   API integration tests (real HTTP)
└── integration/  Full-stack service integration tests
```

### Send Flow

1. `POST /api/notifications/send` validates the request and creates a `NotificationJob` (status: `QUEUED`)
2. Returns immediately (async processing)
3. Async worker fetches the template, checks rate limits (Redis token bucket), and renders variables
4. For each channel, sends the notification; on failure, schedules retry with exponential backoff
5. Periodic task (`every 60s`) picks up jobs whose retry delay has elapsed
6. Every state transition is logged to the audit trail

### Key Design Decisions

For detailed architecture decisions (event-driven DLQ, Lua-based atomic rate limiting,
async dispatch with bounded threads, etc.), see [ARCHITECTURE.md](./ARCHITECTURE.md).

## Testing

**71 tests** across unit, service integration, and API integration layers, all passing against
real Postgres/Redis (via Testcontainers) rather than in-memory substitutes:

- **Unit tests (21)**: `RateLimiterServiceTest`, `RetryServiceTest`, `IdempotencyKeyServiceTest`
  - No Spring context; fast
  - Real Redis via Testcontainers where the test's whole point is real Redis behavior (TTL, atomicity)

- **Service integration tests (20)**: `NotificationSendServiceIntegrationTest`, `DeadLetterQueueServiceTest`
  - Full Spring context; real PostgreSQL + Redis containers
  - Async operations verified with Awaitility (polling, not sleep)

- **API integration tests (30)**: `NotificationControllerIntegrationTest`, `DeadLetterQueueControllerIntegrationTest`, plus the base context-load test
  - Real HTTP via `TestRestTemplate`; HTTP Basic auth against the app's real users
  - Real containers; tests security flows, pagination, filtering, and error response shapes

Run tests with:
```bash
mvn test                                                # all tests
mvn test -Dtest=RateLimiterServiceTest                  # single class
mvn test -Dtest=*IntegrationTest                        # integration tests only
```

Full breakdown, including the two concurrency bugs the test suite caught, in
[TESTING.md](./TESTING.md).

## Troubleshooting

**Port 5432 already in use.** If you run PostgreSQL natively on your machine, it may bind to
port 5432 before the Docker container. Shut down the native instance first
(`brew services stop postgresql` on Homebrew, or the equivalent for your setup).

**Docker not running.** Testcontainers requires Docker. Start Docker Desktop (or `dockerd`)
before running tests.

**Tests seem to hang.** Testcontainers pulls images on first run, which is slow. Subsequent runs
reuse the cached images and are much faster.

## Known Limitations & Future Work

For current limitations (auth model, hardcoded channels, etc.) and design rationale, see:
- [KNOWN_LIMITATIONS.md](./KNOWN_LIMITATIONS.md)
- [ARCHITECTURE.md](./ARCHITECTURE.md)