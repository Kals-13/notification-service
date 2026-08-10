# Notification Service - Spring Boot application

## Project Context
- Multi-tenant notification service with 4 channels (Email, SMS, Push, In-App)
- Stack: Spring Boot, PostgreSQL, Redis, Spring Security

## Commands
- `mvn clean install` — build project
- `mvn spring-boot:run` — start app on localhost:8080
- `mvn test` — run unit tests

## Architecture
- Domain: Tenant, NotificationTemplate, NotificationJob, DeliveryAttempt, AuditLog
- Channels: Interface based (Email, SMS, Push based, InApp implementations, mocked)
- Rate limiter: Redis token bucket per tenant
- Retry Logic: Exponential backoff for transient failures
- RBAC: Platform Admin, Tenant Admin roles via Spring Security

## Key Requirements
- REST APIs: send notification, create template, configure limits, view delivery report
- Multi-tenant isolation (all queries filter by tenant_id)
- Audit trail (every state transition logged)
- Async dispatch with bounded thread pool
- Rate limiting enforced per tenant across channels
- Per-channel retry on failure
- Unit & integration tests for send flow, rate limiting, retry logic

## Conventions
- Java 17, Spring Boot 4.1.0
- JPA entities use Lombok (@Getter, @Setter, @AllArgsConstructor, @NoArgsConstructor)
- Services handle business logic, repositories for persistence
- Exceptions: custom exception classes for validation, not found, rate limit exceeded
- Tests: JUnit 5, Mockito, Testcontainers for PostgreSQL + Redis
- Commits: Imperative mood, max 72 characters (e.g., "Add rate limiter with Redis token bucket")
- Database: PostgreSQL with indexes on (tenant_id, created_at)

## Key Files to Build (In Order)
1. `Domain entities` (Tenant, NotificationTemplate, NotificationJob, DeliveryAttempt, AuditLog)
2. `Channel abstraction` (NotificationChannel interface + 4 implementations)
3. `Repositories` (JPA + custom queries)
4. `Rate limiter` (Redis-backed token bucket)
5. `Send service` (dispatch, retry, audit)
6. `REST controllers` (send, create template, config, report)
7. `RBAC config` (SecurityConfig, role annotations)
8. `Tests` (unit for rate limiter & retry, integration for send flow)
9. `README.md` with assumptions and architecture

## Notes
- Mocked channels (Email/SMS/Push/InApp) just log, don't integrate with real providers
- Skip: webhooks, real delivery confirmations, metrics dashboards
- Focus: multi-tenant isolation, rate limiting correctness, audit trail completeness