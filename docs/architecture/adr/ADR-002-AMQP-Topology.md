# ADR-002 — Per-Type RabbitMQ Topology and Ownership

**Status:** `APPROVED`
**Decision owner:** Platform engineering / Architecture Review Board
**Requirements:** STA-04, STA-10 through STA-14, STA-20, STA-25 through STA-27

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

## Decisions register

| Decision | Resolution | Status |
|---|---|---|
| Exchange, routing key, per-type queues | `app_stats_input` TopicExchange; key = stat type; `app_statsstore.<type>` | **DECIDED** |
| Retry and DLQ shape | Three tiers `.retry.<tier>` (30 s / 5 min / 30 min, externalised) + `.dlq`, DLX `app_stats_dlx` | **DECIDED (G1/G4)** |
| Queue type | **Quorum**, on main, retry and DLQ | **DECIDED 24 Aug 2026** |
| Unroutable message | `mandatory=true` + `ReturnsCallback`; returned ⇒ 503 and `records_unroutable{type}` | **DECIDED 24 Aug 2026** |
| Separate maintenance deployment | **Rejected** — ADR-003 D3's advisory lock already covers it | **DECIDED 24 Aug 2026** |
| `stats-api` broker connection | None. Confirm the reading with platform | **OPEN (Q3a)** |
