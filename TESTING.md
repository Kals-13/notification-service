# Testing Strategy

71 tests across three layers, run with `mvn test`. Every integration-level test runs against
real Postgres/Redis via Testcontainers rather than in-memory substitutes - several of the bugs
this build fixed (see below) only reproduce against real infrastructure, not a mock.

## Layers

**Unit tests (21)** - `RateLimiterServiceTest` (7), `RetryServiceTest` (7),
`IdempotencyKeyServiceTest` (7). No Spring context: these classes have few enough dependencies
that constructing them directly is faster and just as valid. `RetryServiceTest` has zero
external dependencies and runs in milliseconds. `RateLimiterServiceTest` fakes Redis in Java
(a `synchronized` map standing in for the atomic Lua script) specifically to verify the
concurrency contract without needing a live server for every run. `IdempotencyKeyServiceTest`
uses a real Testcontainers Redis instead: TTL expiry and `SETNX` atomicity are exactly the kind
of behavior a hand-rolled fake would have to reimplement to test faithfully, and getting that
reimplementation subtly wrong is how a fake ends up hiding a bug instead of catching one.

**Service integration tests (20)** - `NotificationSendServiceIntegrationTest` (14),
`DeadLetterQueueServiceTest` (6). Full Spring context, real Postgres + Redis containers,
`ChannelFactory` mocked (`@MockitoBean`) so channel success/failure is controllable. Async
completion is polled with Awaitility (`await().atMost(...).untilAsserted(...)`), never
`Thread.sleep` - a fixed sleep is either too short (flaky) or too long (slow) against real
infrastructure timing.

**API integration tests (30)** - `NotificationControllerIntegrationTest` (22),
`DeadLetterQueueControllerIntegrationTest` (7), plus the base `NotificationServiceApplicationTests`
context-load check (1). Real HTTP over `TestRestTemplate` against a running embedded server
(`webEnvironment = RANDOM_PORT`), authenticated via `TestRestTemplate.withBasicAuth(...)` against
the same in-memory users the app actually runs with - not `@WithMockUser`, which only populates
the security context for the test's own thread and has no effect on requests a real server
thread handles.

## Running tests

```bash
mvn test                                              # everything
mvn test -Dtest=RateLimiterServiceTest                # one class
mvn test -Dtest=*IntegrationTest                      # integration tests only
mvn test -Dtest=NotificationControllerIntegrationTest#testSendNotificationAsTenantAdmin_Success  # one test
```

Docker must be running for anything that touches Testcontainers (everything except the three
unit test classes).

## Bugs these tests caught

Two are worth calling out because they only surfaced under real concurrent load, not in a
single-threaded pass:

- **Rate limiter lost updates**: a 10-thread × 5-request test against a capacity-2 bucket let 3
  requests through instead of 2 on the first real run - the original read-then-write
  implementation had exactly the race a token bucket can't afford. Fixed with the atomic Lua
  script described in [ARCHITECTURE.md](./ARCHITECTURE.md); the same test now passes deterministically.
- **Retry backoff exceeding its cap**: `testBackoffDelay_Capped` failed intermittently (roughly
  1 in 3 runs) once real random jitter was in play, because the cap was applied before jitter
  instead of after. Fixed by capping the jittered result.

Both were found by running the actual test suite against real infrastructure and treating a
failure as a signal to fix the code, not the test.
