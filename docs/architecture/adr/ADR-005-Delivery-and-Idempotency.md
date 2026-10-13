# ADR-005 — Delivery Semantics and PostgreSQL Idempotency

**Status:** `ACCEPTED with implementation defaults`
**Decision owner:** Heimdal Core owner / Engineering lead
**Requirements:** STA-04A, STA-06A, STA-10A, STA-12A, STA-12B, STA-13A, STA-20A, STA-21B, STA-25A, STA-27A

> **Numbering warning (G5).** The ARD's ADR-004 covers at-least-once delivery,
> which is this file. Cite the ARD as **`ARD-ADR-04`**; an unqualified "ADR-005"
> always means this file.

## Confirmed facts

Heimdal states `uid` is unique and that it does not intentionally deliver
duplicates. Heimdal owns retry and dead-letter handling when it receives a non-2xx
from the Input Module. The Input Module must return success only after RabbitMQ
publisher confirmation, and non-2xx when RabbitMQ is unavailable.

## Decision

The Stats App uses **at-least-once processing with PostgreSQL idempotency
defence**.

1. Input publishes **persistent** messages to a **durable** topic exchange with
   publisher confirms enabled.
2. Input returns 2xx **only after** broker confirmation.
3. Input returns **503** when RabbitMQ is unavailable or confirmation times out.
   There is no local Input buffer — Input is stateless and Heimdal owns retry.
4. Writer acknowledges **only after** the PostgreSQL transaction commits (D7).
5. `uid` is its own PostgreSQL column. Uniqueness is enforced by
   `PRIMARY KEY (ts_device, uid)` — in that column order — and is scoped **per
   partition**, not globally, per ADR-003 and G2. A global unique index is not
   creatable on a partitioned table.
6. Redelivery is absorbed by
   `INSERT … ON CONFLICT (ts_device, uid) DO NOTHING`. This is safe without any
   field-by-field comparison because ADR-004 guarantees the published message is
   byte-identical to the received record: a redelivered or replayed record
   necessarily carries the same `ts_device` and lands in the same partition.
7. **A suppressed insert is metered, never silent.** The writer reads the
   affected-row count and increments `records_duplicate_suppressed{type}` on zero
   (STA-27A). Discarding a row without a counter would violate the
   no-silent-error-handling rule and would hide a cross-tenant `uid` collision —
   `tenant` is deliberately not in the key, so the counter is the only thing that
   would reveal one.
8. Transient PostgreSQL failures follow the bounded retry path: **three** per-type
   retry tiers `app_statsstore.<type>.retry.<tier>`, delays and the maximum
   attempt count externalised to `application.properties` with starting values of
   30 s / 5 min / 30 min (G1/G4). Immediate requeue is never used for a persistent
   failure. Exhausted retries land in the per-type DLQ
   `app_statsstore.<type>.dlq`, visible in logs, metrics, and alerts.

## The ACK ordering hazard

**Confirm the retry or DLQ handoff before ACKing the original message.** The
tempting order — ACK the failed message, then publish it to the retry queue — opens
a window in which the original is gone and the copy does not exist yet. A crash
inside that window loses the record permanently, and it loses it *silently*,
because from the broker's perspective the message was successfully consumed.

The correct order is: publish to the retry or dead-letter destination, wait for the
publisher confirm on that publish, and only then ACK the original. This makes a
crash produce a duplicate rather than a loss, and rule 6 above already renders the
duplicate harmless. Trading a possible duplicate for a possible loss is the whole
point of at-least-once.

> **Note — 13 October 2026 (DL-168).** The rule says what to do when the hand-off *is*
> confirmed. When it is **not** — a deleted DLQ, a broker refusing publishes under a
> resource alarm — the original must still be returned, and rethrowing does not return
> it: in `MANUAL` acknowledge mode Spring AMQP neither acks nor nacks a delivery whose
> listener threw, so it stays unacknowledged on the open channel, one prefetch slot gone
> per occurrence until the consumer receives nothing. The Writer therefore nacks the
> original **with requeue after a pause** (`stats.amqp.unconfirmed-handoff-requeue-delay`,
> `PT5S` — never immediate, rule 5), counts `records_handoff_unconfirmed{type}`, and
> `StatsAppFailureHandoffUnconfirmed` pages. A refused value and SQLSTATE classes `22` and
> `54` are classified **permanent**; the classifier's default stays *retry*.

## Replay after an upstream outage

Replay is the same mechanism as redelivery, at volume. A 6-hour Heimdal outage is
roughly 28.8M messages; at the requested 2× nominal replay cap (2,667/s) that is
about 3 hours of catch-up while normal ingest continues, holding total ingest
around 4,000/s (Q13a).

Every replayed record is a duplicate by definition, so `ON CONFLICT DO NOTHING`
absorbs the entire replay and `records_duplicate_suppressed` will spike for its
duration. **That spike is the system working**, and alerting must not treat it as
an incident. Likewise, a 503 from Input during replay is the backpressure channel
functioning as designed, not a fault. Note that the 2× cap is a request to
Heimdal, not something the Stats App can enforce. **Q13c closed 4 Sep 2026 (DL-109):
Heimdal agrees the cap** — and it remains unenforceable by us, so every defence below
stays exactly as it is.

## Implementation defaults

| Choice | Default | Reason |
|---|---|---|
| Consumer ACK | After the successful PostgreSQL transaction (D7) | Prevents loss before persistence; satisfies STA-25A. |
| Retry/DLQ handoff | Publish-confirm the handoff **before** ACKing the original | ACK-then-publish opens a silent loss window. See above. |
| Writer batching | Individual writes first, behind a batch-shaped interface; bounded micro-batching (100–500 records or 50–200 ms, whichever first) with single-record fallback is the target (D11, G3) | Makes batching a tuning change against a real benchmark rather than a rewrite, while single-record fallback preserves per-record DLQ precision. |
| Duplicate handling | `ON CONFLICT (ts_device, uid) DO NOTHING`, metered | Defence in depth even though upstream says duplicates do not occur. |
| Input broker failure | HTTP 503, no local buffer | Keeps Input stateless and activates Heimdal retry. |
| Replay rate | 2× nominal (2,667/s), **agreed by Heimdal 4 Sep 2026 (Q13c, DL-109)** | Bounds catch-up to ~3 h after a 6 h outage. **Still not enforceable by us** — 503 backpressure, bounded prefetch and T-018's rate limit are the controls we own. |

## Required tests

* Publisher-confirm timing: a 2xx is never returned before the confirm arrives.
* Broker unavailable and confirm timeout both produce 503, and nothing is
  published.
* Manual ACK occurs only after commit; a forced rollback leaves the message
  unacknowledged.
* A crash between the PostgreSQL commit and the ACK produces a redelivery that is
  absorbed idempotently — and increments `records_duplicate_suppressed`. **A
  suppressed write that meters nothing is a test failure, not a pass.**
* A crash between the retry publish and the original ACK produces a duplicate,
  not a loss. Assert the record is present exactly once.
* Retry tier progression: a transient failure walks 30 s → 5 min → 30 min and then
  dead-letters, with all four values read from configuration rather than code.
* DLQ routing is per type, and a poison message in one type does not delay
  another (the head-of-line test that motivated the per-type topology).
* Recovery after the database becomes available again drains the backlog without
  loss or duplication.

## Decisions register

| Decision | Resolution | Status |
|---|---|---|
| Delivery semantics | At-least-once with database idempotency defence | **DECIDED** |
| Consumer ACK point | After the PostgreSQL commit | **DECIDED (D7)** |
| Retry/DLQ handoff ordering | Confirm the handoff publish before ACKing the original | **DECIDED** |
| Unconfirmed handoff | Nack the original with requeue after a configured pause; metered and alerted | **DECIDED 13 Oct 2026 (DL-168)** |
| Duplicate outcome | `ON CONFLICT (ts_device, uid) DO NOTHING`, metered | **DECIDED** |
| `uid` uniqueness scope | `(ts_device, uid)`, per partition — see ADR-003 | **DECIDED (G2)** |
| Writer batching | Individual first behind a batch-shaped interface; micro-batching is the target | **DECIDED (D11, G3)** |
| Replay rate cap | 2× nominal, agreed by Heimdal | **CLOSED 4 Sep 2026 (Q13c → DL-109)** — agreed, and we still cannot enforce it. T-049 unblocked |
