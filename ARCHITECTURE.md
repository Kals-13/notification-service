# Architecture

This document covers the design decisions that aren't self-evident from the code - mainly the
ones where a naive implementation would look identical at a glance but behave incorrectly under
concurrency, or where a dependency shape forced a specific pattern.

## Send flow

```
POST /api/notifications/send
  → validate tenant, template, email format, required template variables
  → create NotificationJob (status QUEUED), persist
  → submit to bounded thread pool (ExecutorConfig, size 10)
  → return 200 immediately with the job's current state
```

Delivery happens entirely on the worker pool, not the request thread. For each channel the
template lists:

1. `RateLimiterService.checkAndConsume` - Redis token bucket check
2. `VariableSubstitutionService.render` - template body rendering
3. `NotificationChannel.send` - the actual (mocked) delivery call
4. On success: `DeliveryAttempt` row (SENT), job → `DELIVERED`
5. On failure: `DeliveryAttempt` row (FAILED); if retry budget remains, backoff + reschedule
   (`RetryService`, job → `SCHEDULED`); otherwise job → `FAILED` and the DLQ event fires

A scheduled task (`NotificationSendService.retryScheduledJobs`, `@Scheduled(fixedDelay = 60_000)`)
sweeps for `SCHEDULED` jobs whose backoff has elapsed and resubmits them to the same pool. Every
transition - sent, rate-limited, retry-scheduled, failed, dead-lettered, cancelled - writes an
`AuditLog` row.

## Rate limiting: atomic token bucket via Lua

One Redis hash per `(tenantId, channel)` pair, holding `tokens` and `last_refill_time`. The
naive implementation of a token bucket is: `GET` the current state, compute the refill, decide
whether to allow the request, `SET` the new state. That's a read-modify-write with a gap in the
middle: under concurrent requests for the same tenant/channel, two callers can both read the
same starting token count, both decide "allowed," and both write, silently letting more requests
through than the configured limit.

The fix is to run the whole cycle as a single Redis command. `checkAndConsume` executes one
`EVAL` (`RateLimiterService`'s `CHECK_AND_CONSUME_SCRIPT`) that reads, computes the refill,
checks availability, and writes the new state atomically - Redis single-threads command
execution, so there's no window for two callers to interleave. Verified directly: a
concurrency test driving 10 threads × 5 requests each against a capacity-2 bucket lets exactly 2
through, deterministically, every run.

## Retry backoff: jitter before capping, not after

`RetryService.calculateBackoffDelay` computes `min(2^attempt * 1000, 30000)` then applies jitter
in the `0.8x to 1.2x` range. The order matters: jittering an already-capped 30000ms value can scale
it up to 36000ms, silently breaking the documented "never wait more than 30s" ceiling on roughly
a third of calls (whenever the random factor lands above ~0.83). The delay is jittered first,
then the *result* is clamped to the cap, so the 30-second ceiling is an actual guarantee rather
than something that holds most of the time.

## Dead letter queue: event-driven, not a direct call

`DeadLetterQueueService` depends on `NotificationSendService` - it needs to redispatch a job
that's been retried out of the DLQ. If `NotificationSendService` also called
`DeadLetterQueueService.moveToDeadLetterQueue()` directly when a job exhausts its retries, that's
a circular bean dependency (`A → B → A`), which Spring's constructor injection can't resolve.

Instead, `NotificationSendService` publishes `MaxRetriesExceededEvent` via
`ApplicationEventPublisher` from the exact point where retries are genuinely exhausted (not from
every failure path - a missing template or bad channel config fails immediately without ever
having a retry budget to begin with, and shouldn't reach the DLQ the same way).
`DeadLetterQueueService` listens via `@EventListener` and performs the `FAILED → DEAD_LETTERED`
transition. Same effect as a direct call, no cycle, and the listener is defensively wrapped so a
DLQ-transition failure can't take down the rest of the dispatch loop.

Retrying a job out of the DLQ resets and redispatches the *same* job (`NotificationJob` row,
same ID) rather than constructing a new `SendNotificationRequest` and calling `sendNotification`
again - that would create a second, unrelated job and leave the original sitting in `QUEUED`
without ever actually being dispatched. `NotificationSendService.redispatchJob` exposes the
dispatch step directly for this case, skipping the retry-budget and ownership checks that
`sendNotification`/`retryFailedNotification` apply - `DeadLetterQueueService` has already done
that validation itself.

## Idempotency: check-then-store, deliberately not atomic

`POST /api/notifications/send` accepts `X-Idempotency-Key`. The handler checks Redis for an
existing job under that key; if found, it returns that job's data without creating anything new.
If not found, it proceeds with the normal send and stores the key afterward.

This is intentionally not the atomic `SETNX`-based claim that `IdempotencyKeyService.validateAndStore`
implements (and that the rate limiter's fix above uses the same pattern for): a `SETNX` claim
needs something to claim the key *for*, and a `NotificationJob`'s ID isn't assigned until it's
persisted. Reserving a client-generated UUID up front to claim against would work, but changes
the job-creation flow more than this endpoint's shape justifies. The trade-off: two requests
with the same key milliseconds apart (true concurrent duplicates) could both pass the check
before either has stored anything. The feature's actual target, a client retrying seconds after
a timeout, hits the store-then-read path cleanly and behaves correctly.

## Multi-tenancy

Every write path takes a `tenantId` and every read/update/delete path that operates on a specific
resource looks it up by `(id, tenantId)` together - `NotificationJobRepository.findByIdAndTenantId`,
`NotificationTemplateRepository.findByTenantIdAndId`, etc. A request for a real resource ID under
the wrong tenant ID gets a 404, not the resource. Role-based access control (`PLATFORM_ADMIN` /
`TENANT_ADMIN`) is enforced at two layers for defense in depth: URL patterns in
`SecurityConfig.securityFilterChain` (before the request reaches a controller) and
`@PreAuthorize` on each controller method (in case a URL pattern is ever missed or changed).

## Async dispatch

`ExecutorConfig` provides a single `ScheduledExecutorService` (`Executors.newScheduledThreadPool(10)`,
bean-managed with `destroyMethod = "shutdown"` so it doesn't leak threads on context shutdown).
Everything that needs to run off the request thread - initial dispatch, scheduled retries, DLQ
redispatch - submits to this same pool.
