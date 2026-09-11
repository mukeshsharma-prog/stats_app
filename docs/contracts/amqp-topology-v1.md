# Stats App AMQP Contract and Topology — V1 Draft

**Status:** `PENDING PLATFORM APPROVAL`  
**Applies to:** Stats Input publisher, RabbitMQ infrastructure, Stats Writer consumers.  
**Requirements:** STA-04, STA-10 through STA-14, STA-20, STA-25 through STA-27.

## 1. Canonical message

The Input Module validates the **common envelope** before publication, then publishes the record **byte-identically**. It does **not** validate the per-type payload — that is the Writer's gate, and a payload failure is a dead-letter rather than a 400 (ADR-007 §5). There is **no normalisation of any kind**, for `omsstats` or any other type — ADR-004 is ACCEPTED and Heimdal already sends the canonical `ts.server`. A record carrying `ts.converter` is **valid**: production sends it on 100% of records, and the rule that rejected it was withdrawn on 29 Aug 2026 (ADR-007 §2). It is stored, returned verbatim, and never read.

This is load-bearing beyond ADR-004: because the published message is identical to the received record, a redelivered or replayed record carries the same `ts_device`, lands in the same daily partition, and collides with the same `(ts_device, uid)` key. If Input ever rewrote a timestamp, ADR-003's per-partition duplicate suppression would stop working and the failure would be silent.

The message MUST contain a `type` whose literal matches the AMQP routing key, selected queue, schema, consumer, and persistence table.

| Property | Value |
|---|---|
| Content type | `application/json` |
| Delivery mode | Persistent |
| Correlation ID | `uid` unless platform tracing supplies an approved additional ID |
| Message body | The validated JSON record, byte-identical to what was received |
| Routing key | Exact `type` literal |
| Publisher guarantee | Confirm required before successful HTTP acceptance |
| Mandatory flag | **Set.** An unroutable message is returned and treated as a failed acceptance — 503 plus `records_unroutable{type}`. A confirm alone does not cover this: the broker ACKs a message no queue wanted. |
| Queue type | `quorum` — RabbitMQ 4 has no classic mirroring (ADR-002 §8) |

## 2. Exchange and queues

| Resource | Proposed name | Type / purpose | Durable | Owner |
|---|---|---|---:|---|
| Ingress exchange | `app_stats_input` | `TopicExchange` | Yes | Every broker-connected module declares identical topology; confirm whether `stats-api` is broker-connected. |
| Main queue | `app_statsstore.<type>` | Main work queue per type, **`x-queue-type: quorum`** | Yes | Every broker-connected module must declare the identical durable definition; topology conflict testing required. |
| Retry queues | `app_statsstore.<type>.retry.<tier>` | Delayed redelivery holding queues per type — **three tiers**, `x-queue-type: quorum`, delays externalised (starting values 30 s / 5 min / 30 min) via `x-message-ttl` + DLX | Yes | Same sole owner. Immediate requeue is never used for a persistent failure. |
| Dead-letter queue | `app_statsstore.<type>.dlq` | Final failed messages per type, `x-queue-type: quorum` | Yes | Same sole owner |
| Dead-letter exchange | `app_stats_dlx` | Route failures/retry paths | Yes | Same sole owner |

`<type>` is one of the five V1 types: `lorastats`, `ltestats`, `sysstats`, `omsstats`, and `netstats`. All five have strict, normative schemas in `docs/schemas/`; `netstats` was finalised by Q4a on 18 August 2026.

## 3. Main flow

```text
HTTP Input
  -> validate schema and authenticated source
  -> publish persistent JSON to app_stats_input using routing key <type>
  -> receive publisher confirm
  -> return 2xx accepted response

app_stats_input
  -> app_statsstore.<type>
  -> dedicated <type> consumer
  -> idempotent PostgreSQL persistence
  -> manual ACK after success
```

## 4. Failure and retry flow

| Failure class | Example | Proposed handling | Approval needed |
|---|---|---|---|
| Input validation | Missing field, wrong type, unknown stat type | Return 400; safe log; no publish. | Error contract approval |
| Input auth | Invalid Heimdal identity | 401/403; no publish. | Security approval |
| Broker publish | Broker unavailable or confirm timeout | Return 503; safe metric/log; rely on upstream retry/replay. | **DECIDED (ADR-005)** |
| Unroutable publish | No queue bound for the routing key — new type, typo, half-applied topology | Message is **returned** (mandatory flag). Return 503, increment `records_unroutable{type}`, log at `ERROR` with the routing key. **Never a 2xx:** the confirm succeeded, so without this the record is lost behind a success. | **DECIDED (ADR-002 §9)** |
| Transient persistence | PostgreSQL timeout/temporary unavailable | Route to the next per-type retry tier (30 s → 5 min → 30 min). **Never an immediate requeue** — that is a hot loop against a failing database. | **DECIDED (G4)** |
| Duplicate | Redelivery after write/ACK gap | `ON CONFLICT (ts_device, uid) DO NOTHING`; ACK; **increment `records_duplicate_suppressed{type}`** — a suppressed row that meters nothing is a bug. | **DECIDED (ADR-005)** |
| Permanent persistence | Schema mapping failure, violated invariant | Route to type DLQ; safe error log and metric. | Error taxonomy approval |
| Retry exhausted | Repeated transient failure | Move to type DLQ; alert. | **DECIDED (G4)** — three tiers, then `<type>.dlq` |

The retry policy is fixed by G1/G4 (18 August 2026): **three tiers per stat type**, fixed delays rather than a multiplier, all values externalised and never hard-coded. Immediate requeue is never used for a persistent failure.

```properties
# stats-writer. Starting values — tune against measurement, never hard-code.
stats.amqp.retry.tier1-delay=PT30S
stats.amqp.retry.tier2-delay=PT5M
stats.amqp.retry.tier3-delay=PT30M
stats.amqp.max-retries=3          # one attempt per tier; exhausted -> <type>.dlq
# There is deliberately NO dlq TTL property. The DLQ never expires messages -- see §6.
```

There is no `retry-multiplier` and no `retry-max-delay-ms`: the tiers are explicit, not derived from a backoff curve. An exhausted message goes to `app_statsstore.<type>.dlq`.

**Ordering hazard (ADR-005).** Publish to the retry or dead-letter destination, wait for the publisher confirm on *that* publish, and only then ACK the original. The tempting order — ACK the failed message, then publish it onward — opens a window in which the original is gone and the copy does not exist yet, and a crash inside it loses the record *silently*.

> **BUILT 5 September 2026 — T-024, DL-122.** `FailedDeliveryRouter` returns only once
> the onward copy is confirmed, and `LorastatsConsumer` ACKs only after it returns; an
> unconfirmed hand-off throws `UnconfirmedHandoffException` and nothing is acknowledged.
> Attempts are counted in `x-stats-attempt` — **our** header, because a tier's TTL
> expiring appends `x-death` and our explicit publish does not, so a count taken from
> `x-death` would reset and the record would retry forever. A **permanent** failure
> (`PermanentFailureException`, a Jackson parse failure, `DataIntegrityViolationException`)
> skips all three tiers; anything unrecognised is retried, because wrong-and-retry costs
> 35½ minutes and wrong-and-dead-letter costs a person.
>
> Values are properties, exactly as specified below. The **payload gate that produces most
> permanent failures is still T-057**, and DLQ depth/age alerting is still T-060 — the
> queue is real and nobody is watching it yet.

## 5. Required consumer configuration

| Setting | Requirement |
|---|---|
| Consumer binding | Exactly one type per listener/binding. |
| Acknowledgement | Manual, after successful idempotent write. |
| Concurrency | Per type; external configuration. |
| Prefetch | Per type; external configuration and capacity-tested. |
| Error classification | Explicit mapping from exception category to retry, DLQ, or alert. |
| Graceful shutdown | Stop intake, complete in-flight work within grace period, return unacknowledged work safely to queue. |
| Metrics | Per type: receive, success, duplicate, retry, DLQ, processing duration, consumer availability, queue depth. |

## 6. Dead-letter retention and redrive contract — **DECIDED (ADR-002 §12, 29 August 2026)**

**The DLQ carries no `x-message-ttl` and no expiry of any kind.** A TTL'd dead-letter queue discards records silently, which violates rule 9 and the no-silent-error-handling rule. A message that reached the DLQ has already survived three retry tiers; deleting it on a timer destroys the only remaining copy of a record Heimdal believes it delivered. Bounding broker disk is an alerting and disposition problem, not an expiry problem.

| Control | Value |
|---|---|
| Message expiry | **None.** No `x-message-ttl` and no `x-dead-letter-exchange` on any `<type>.dlq`. Enforced by construction: `AmqpTopology.deadLetterQueueArguments()` takes no parameter for either, and `AmqpTopologyTest` asserts the map exactly |
| Alert | DLQ depth > 0, **and** oldest-DLQ-message age |
| Manual disposition | Expected within 14 days |
| Redrive authority | Authorized operations role only |
| Redrive scope | Explicit, traceable, scoped per stat type — never blanket |
| Redrive rate | Capped at 2× nominal (2,667/s), the same cap as replay |
| Precondition | Root cause identified and resolved first; blind automatic replay is not permitted |

The operations runbook must additionally define access, payload masking, audit evidence, and success verification.

**A DLQ is inert until an operator acts.** No service automatically consumes or
redrives it. An authorised manual redrive preserves the attempt and safe failure
metadata and gives the record one processing attempt. If that attempt fails, the
Writer returns it directly to the same type's DLQ without allocating the three retry
tiers again. The confirmed manual hand-off and its audit fields are specified in
`docs/runbooks/dlq-redrive.md`.

**Two things the runbook must state outright.** A 503 during redrive or replay is the backpressure channel working, not an incident. And a message that has sat in the DLQ long enough becomes `expired_on_arrival` the moment it is redriven, because its `ts.device` has aged past the 90-day retention window — **a redrive after roughly 90 days is not a recovery, it is a no-op that increments a counter.**

**That counter is real since 10 September 2026 (T-026, DL-133), so the runbook can be checked against it rather than merely warned about.** A redriven message reaches `RetentionGuard`, which increments `records_expired_on_arrival{type}`, logs a `WARN` naming the `uid`, `clsbox` and the record's age in days, and ACKs — storing nothing. Watch that counter *while* redriving: if it moves in step with the redrive, nothing is being recovered. Nothing is dead-lettered a second time either, which is the point — the record is valid, and returning it to a queue with no TTL would only guarantee the same verdict on the next attempt.

## 7. Contract tests

The automated integration suite must prove all of the following for each type:

| # | Contract test | Status, 5 September 2026 |
|---|---|---|
| 1 | A validated message uses the correct routing key and reaches only its type queue | **DONE** — `IngestPublishIntegrationTest` |
| 2 | **A message published with a routing key no queue is bound to returns 503 and increments `records_unroutable`** — asserting only the publisher confirm would pass while the record is silently discarded | **DONE** (T-015, DL-118) — against a real broker with a genuinely unbound key |
| 3 | Main/retry/DLQ queues are durable and accepted messages survive broker restart | **PART** — durability is asserted on the declarations (T-032); the restart drill is Phase 5 |
| 4 | A database outage follows the approved retry path; it does not discard or pre-ACK a message | **DONE** (T-024) — `RetryAndDeadLetterIntegrationTest`; the main queue is empty only *after* the copy exists |
| 5 | A permanent failure reaches only the appropriate type DLQ | **DONE** (T-024) — and on the first attempt, not after three tiers |
| 6 | A poison message in one type does not prevent a different type from persisting | **NOT DONE** — needs a second type; Phase 3 |
| 7 | A redelivery after persistence does not produce duplicate data | **DONE** — `LorastatsBatchWriterIntegrationTest`, metered not silent |
| 8 | All metrics and logs identify the stat type and `uid` without sensitive payload leakage | **PART** — the stat type is on every counter and the failure header carries a class name, never an exception message (STA-03A); `uid` is not yet on the Writer's failure logs, because reading it means parsing a body that has already failed to parse |
