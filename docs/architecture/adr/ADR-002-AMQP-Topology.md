# ADR-002 — Per-Type RabbitMQ Topology and Ownership

**Status:** `APPROVED`
**Decision owner:** Platform engineering / Architecture Review Board
**Requirements:** STA-04, STA-10 through STA-14, STA-20, STA-25 through STA-27
**Amended 29 August 2026:** Q3a closed — only broker-connected modules declare topology
(§11); dead-letter retention and redrive authority decided (§12).
**Amended 29 August 2026 (ADR-007 §4):** §12 gains a volume warning — the DLQ now
receives routine traffic, not only exceptional traffic.

## Context

The SRS requires a dedicated queue and consumer per stat type. The implementation brief names one `app_statsstore` queue. A shared queue violates type isolation, prevents independent consumer scaling, and permits head-of-line blocking when a poison message of one type holds up other types.

## Proposed decision

1. `app_stats_input` is the ingress exchange.
2. The routing key equals the exact approved stat type.
3. Every stat type has a dedicated durable main queue named `app_statsstore.<type>`.
4. Every type has a dedicated retry/DLQ path as specified in `contracts/amqp-topology-v1.md`: **three** retry tiers `app_statsstore.<type>.retry.<tier>` and a DLQ `app_statsstore.<type>.dlq`, sharing the main queue's prefix. All tier delays and the maximum attempt count are externalised, never hard-coded; starting values are 30 s / 5 min / 30 min. Immediate requeue is never used for a persistent failure. *(G1/G4, 18 Aug 2026)*
5. Main queues, retry queues, dead-letter queues, exchange, and bindings are durable. Ingress publishes persistent messages.
6. Every broker-connected module declares identical topology, as answered. Confirm whether `stats-api` is intended to connect to RabbitMQ; if it is not broker-connected, it does not declare broker resources. Declaration arguments must be identical and integration-tested to prevent RabbitMQ resource-lock errors.
7. The Input service confirms publish success before returning an acceptance response.

## Proposed topology

```text
app_stats_input exchange [TopicExchange]
  | lorastats -> app_statsstore.lorastats -> writer:lorastats -> lorastats table
  | ltestats  -> app_statsstore.ltestats  -> writer:ltestats  -> ltestats table
  | sysstats  -> app_statsstore.sysstats  -> writer:sysstats  -> sysstats table
  | omsstats  -> app_statsstore.omsstats  -> writer:omsstats  -> omsstats table
  | netstats  -> app_statsstore.netstats  -> writer:netstats  -> netstats table
```

## Alternatives rejected

| Alternative | Rejection reason |
|---|---|
| One shared `app_statsstore` queue | Conflicts with STA-10/20, causes shared failure domain and shared scaling bottleneck. |
| Input service writes PostgreSQL directly | Violates buffer/decoupling and single-writer architecture. |
| Input service owns all topology permanently | Couples ingress deployability to persistence infrastructure ownership; use only if explicitly approved by platform. |


## Decisions carried over from the ARD — ratified 24 August 2026

Three ARD proposals touching this topology had been recorded as "adopted" without
ever landing in a decision. They are settled here.

### 8. Queue type — **quorum**, for main, retry and dead-letter queues

Every queue is declared with `x-queue-type: quorum`.

The dev and target broker is RabbitMQ 4, where classic mirrored queues no longer
exist — quorum is *the* replicated durable queue type, so this is less a choice
than an acknowledgement of the platform. On a single-node dev broker a quorum
queue runs at replication factor 1 and behaves like a durable classic queue; in
production it survives the loss of a node, which is the whole point of declaring
messages persistent in the first place (STA-13A).

Quorum queues support both mechanisms the retry design depends on: queue-level
`x-message-ttl` for the tier delays and `x-dead-letter-exchange` for the bounce
back. Nothing in G1/G4 has to change.

**Two consequences to hold on to.** Quorum queues keep more state resident than
classic ones, and a synchronised burst leaves roughly 12,000 messages per type
queued (Q13a) — comfortable, but if measurement disagrees the lever is
`x-max-in-memory-length`, not a change of queue type. And **a queue's type is
fixed at declaration**: switching an existing queue means deleting and
redeclaring it, and a mismatched redeclaration is exactly the `RESOURCE_LOCKED`
startup failure this ADR already warns about. Declare from the shared constants
in `stats-common` and nowhere else.

### 9. Publish with `mandatory=true` — an unroutable message is a **failed** acceptance

The Input Module publishes with the `mandatory` flag set and registers a
`ReturnsCallback`. A returned message is treated as a failure: the request gets
**503**, `records_unroutable{type}` is incremented, and the event is logged at
`ERROR` with the routing key.

**This closes a silent-loss hole that publisher confirms do not.** A confirm tells
you the broker accepted the message, not that any queue wanted it. If a binding is
missing — a new stat type deployed before its queue exists, a typo in a routing
key, a partially applied topology change — the broker ACKs the publish and drops
the message on the floor. Input would return 200, Heimdal would mark it delivered
and never retry, and the record would be gone with every component reporting
success. That is precisely the failure rule 8 and STA-04A exist to prevent, and
confirms alone do not prevent it.

### 10. Rejected — a separate maintenance deployment

The ARD proposes a sixth scheduled Writer invocation dedicated to partition
maintenance. **Not adopted.** ADR-003 D3 already guards retention with a
PostgreSQL advisory lock per stat type, which is required anyway because each
per-type Writer scales to several replicas. A dedicated instance would add a
deployment to operate and would still need the same lock. Keep the lock; drop the
instance.

## Decisions added 29 August 2026

Neither of these came from the ARD. §11 closes a question that had been open against ZC
since the answer set; §12 closes one that had been sitting with operations.

### 11. Topology is declared by **broker-connected** modules only — Q3a closed

**Decision, 29 August 2026:** `stats-input` and `stats-writer` declare the topology.
**`stats-api` holds no RabbitMQ connection at all.** Declarations come from the shared
constants in `stats-common` and must be identical and idempotent.

ZC's answer to Q3 read *"every module have to declare the exchanges, queues and
bindings."* Taken literally that hands the read-only API a broker dependency it has no
use for, and adds a third declaring module to a set where any argument mismatch is a
`RESOURCE_LOCKED` startup failure rather than a warning. Read as *every broker-connected
module*, the answer and the service boundaries agree.

This is no longer treated as a blocker. The enforcement is **T-032**, the declaration
conflict test, which proves at build time what a platform sign-off would only assert in
prose — and quorum queues sharpen it further, because a queue's type is fixed at
declaration (§8). Obtain the one-line platform acknowledgement as a courtesy; do not
wait on it, and do not give the API a broker connection in the meantime.

### 12. Dead-letter retention and redrive authority — no TTL, ever

**Decision, 29 August 2026: the DLQ carries no `x-message-ttl` and no expiry of any
kind.** A TTL'd dead-letter queue discards records silently, which violates rule 9
(nothing is silently dropped) and the no-silent-error-handling rule outright. A message
that has reached the DLQ has already survived three retry tiers; deleting it on a timer
destroys the only remaining copy of a record Heimdal believes it delivered.

| Control | Value |
|---|---|
| Alert | DLQ depth > 0, **and** oldest-DLQ-message age |
| Manual disposition | Expected within 14 days |
| Redrive authority | Authorised operations role only |
| Redrive scope | Explicit, per stat type — never blanket |
| Redrive rate | Capped at 2× nominal (2,667/s), the same cap as replay |
| Precondition | Root cause identified and resolved first; blind automatic replay is not permitted |

Bounding broker disk is therefore an **alerting and disposition** problem, not an expiry
problem. Depth above zero is already an actionable signal, so an unbounded DLQ that is
actually watched cannot grow unnoticed.

> **Pressure on this decision — added 29 August 2026 (ADR-007 §4).** The paragraph
> above assumes the DLQ receives *exceptional* traffic. It no longer does. ADR-007
> encodes one `lorastats` payload dialect and dead-letters the other two, which at
> 115.2M records/day is on the order of **15M messages/day accumulating with no
> expiry**. "Depth > 0 is actionable" degrades to noise at that rate, and disposition
> within 14 days is not achievable by hand.
>
> **The no-TTL rule still stands** — a TTL'd DLQ is silent data loss, and that is
> just as true at 15M/day as at 15/day. What changes is the urgency of the cause:
> the dialect question (ADR-007 Q15a) is the fix, and it belongs to ZC. Until it is
> answered, DLQ depth alerting must be sized for this volume and must be in place
> **before Phase 2 ships**, and the per-type alert thresholds cannot be uniform —
> `lorastats` will sit permanently above whatever threshold the other four types use.
>
> Do not resolve this by widening the `lorastats` schema to absorb the dialects.
> That is precisely the option ADR-007 §4 considered and rejected, and doing it here
> as an operational expedient would silently ratify a contract nobody understands.

**Enforced in code since 29 August 2026.** `AmqpTopology` owns the declaration argument
maps as well as the names, and `deadLetterQueueArguments()` **has no parameter to set a
TTL or a dead-letter exchange with** — the policy is unrepresentable rather than merely
documented. `AmqpTopologyTest` asserts the map is exactly `{x-queue-type: quorum}`. The
same move gives the modules one source for their arguments instead of two hand-built
maps, which is what the §11 declaration-conflict test (T-032) compares.

**The trap to write into the runbook:** a message that has sat in the DLQ long enough
becomes `expired_on_arrival` the moment it is redriven, because its `ts.device` has aged
past the 90-day retention window (ADR-003). **A redrive after roughly 90 days is not a
recovery — it is a no-op that increments a counter.** Operators must know this before
they attempt it, or a failed recovery will look like a successful one.

## Consequences and tests

Each stat type needs a dedicated binding, consumer configuration, metrics, DLQ alarm, and isolation test. A poison message in one type must not slow delivery for another type. Every topology argument must be identical across local, test, and production environment definitions.

Added by the decisions above:

* Every queue declares `x-queue-type: quorum`, from the shared constants only. A
  test asserts the declared arguments match across modules, because a mismatch is
  a `RESOURCE_LOCKED` startup failure rather than a warning.
* **Publish a message with a routing key no queue is bound to, and assert the
  request receives 503 and `records_unroutable` increments.** A test that asserts
  only the publisher confirm would pass while the record is silently discarded —
  which is the entire reason `mandatory` is set.
* The declaration-conflict test (T-032) covers `stats-input` and `stats-writer` only.
  `stats-api` starting without any broker connection is itself an assertion (§11).
* No DLQ declaration carries `x-message-ttl` or a dead-letter exchange. **Asserted:**
  `AmqpTopologyTest.declaresDeadLetterQueuesWithNoExpiryAndNoOnwardRoute` (§12).
* Both broker-connected modules build their queue arguments from `AmqpTopology`'s maps
  rather than assembling their own, so the declaration-conflict test compares one
  source. `stats-api` starting with no broker connection is itself an assertion (§11).

## Decisions register

| Decision | Resolution | Status |
|---|---|---|
| Exchange, routing key, per-type queues | `app_stats_input` TopicExchange; key = stat type; `app_statsstore.<type>` | **DECIDED** |
| Retry and DLQ shape | Three tiers `.retry.<tier>` (30 s / 5 min / 30 min, externalised) + `.dlq`, DLX `app_stats_dlx` | **DECIDED (G1/G4)** |
| Queue type | **Quorum**, on main, retry and DLQ | **DECIDED 24 Aug 2026** |
| Unroutable message | `mandatory=true` + `ReturnsCallback`; returned ⇒ 503 and `records_unroutable{type}` | **DECIDED 24 Aug 2026** |
| Separate maintenance deployment | **Rejected** — ADR-003 D3's advisory lock already covers it | **DECIDED 24 Aug 2026** |
| `stats-api` broker connection | **None.** Only broker-connected modules declare topology; platform ack is a courtesy, not a gate | **DECIDED (§11), 29 Aug 2026 — closes Q3a** |
| Dead-letter retention | **No TTL, ever.** Alert on depth > 0 and oldest-message age; manual disposition within 14 days | **DECIDED (§12), 29 Aug 2026 — and asserted in `AmqpTopologyTest`** |
| Redrive authority | Authorised ops role, explicit per type, rate-limited to 2× nominal, root cause resolved first | **DECIDED (§12), 29 Aug 2026** |
