# Known Limitations & Scope Boundaries

## Auth model has no principal-to-tenant binding

`SecurityConfig` provisions three in-memory users, each carrying a role (`PLATFORM_ADMIN` or
`TENANT_ADMIN`) but no tenant identity. Role checks are enforced correctly everywhere - a
`TENANT_ADMIN` genuinely cannot reach `PLATFORM_ADMIN` endpoints, and vice versa. What isn't
enforced is *which* tenant a given `TENANT_ADMIN` should be scoped to: any authenticated
`TENANT_ADMIN` can pass any tenant ID as a request parameter and operate on that tenant's data.

The natural next piece of work: a production
identity layer (OAuth2/JWT with a tenant claim, or a user-to-tenant mapping table) would replace
the in-memory `UserDetailsService` and let every service method resolve `tenantId` from the
authenticated principal instead of trusting the request. The rest of the system is already
structured for this - every query is already tenant-scoped by ID, so adding an identity check is
additive, not a rearchitecture.

## Channels are mocked

`EmailChannel`, `SMSChannel`, `PushChannel`, `InAppChannel` log their input and return success.
None calls a real provider (SendGrid, Twilio, FCM). The `NotificationChannel` interface and
`ChannelFactory` lookup are the actual integration point - adding a real provider means
implementing one interface and registering the bean; nothing else in the send/retry/DLQ pipeline
changes.

## `retryFailedNotification` can't resurrect an exhausted job

The guard checks `currentRetry < maxRetries` before resetting the job. A job that's genuinely
`FAILED` is, by definition, already at that limit, so this endpoint rejects it (409). That's
intentional: `retryFailedNotification` is for retrying *before* the budget runs out; recovering
a job that's already exhausted its retries is what the DLQ endpoints
(`POST /api/dlq/{jobId}/retry`) are for.

## Idempotency key claiming is not atomic

See [ARCHITECTURE.md](./ARCHITECTURE.md) for the detailed trade-off. In short: the duplicate-detection path is
check-then-store, which correctly handles the case it's built for (a client retrying after a
timeout) but leaves a narrow window for two truly simultaneous requests with the same key. An
atomic claim (`IdempotencyKeyService.validateAndStore`, already implemented and tested) would
close this, at the cost of returning an error on the second request instead of the cached result
- a different, also valid API contract that would need a product decision, not just a code
change.

## Multi-channel jobs share one status field

If a template lists more than one channel, each channel's outcome updates the job's single
`status` field in turn - whichever channel is processed last determines the job-level status.
Per-channel outcomes are not lost: every attempt is recorded as its own `DeliveryAttempt` row, so
the full history is queryable even though the summary status reflects only the most recent
channel processed. A job-level status that aggregates multiple channel outcomes (e.g. "partially
delivered") would need a defined aggregation rule, which wasn't specified.
