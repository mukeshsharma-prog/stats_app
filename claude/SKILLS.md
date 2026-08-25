# SKILLS.md

Reusable, repeatable procedures for the Stats App. Each skill is a checklist
for a task that will be done more than once — the point is that the *second*
time it is done, it is done the same way as the first.

> **Version 1.2 — 16 August 2026.** Updated for **PostgreSQL**. The v1.0 version
> of this file described MongoDB TTL indexes and left C1, Q10, and Q23 open; all
> three are now answered. Retention keys on **`ts.device`**.

---

## Skill 1 — Add a new stat type

The single most repeated task on this project, and the one the whole
architecture is designed around. If this procedure ever requires modifying
an existing stat type's code, **stop** — that violates STA-14/STA-22.

### Prerequisites
- [ ] The schema for the new type is documented (fields, types, mandatory/optional)
- [ ] The type name is confirmed and matches the routing key exactly

### Steps

1. **Define the payload model**
   - Add the `data` payload class. The envelope is shared — do not redefine it.
   - Mandatory envelope fields: `uid`, `tenant`, `maintenant`, `gateway`, `type`, `ts.processor`, `ts.device`

2. **Register validation** *(Module 1, STA-03)*
   - Add the schema to the validation registry keyed by type name
   - Confirm an invalid payload returns **HTTP 400** and is logged
   - Confirm an unrecognised type is rejected, not silently forwarded

3. **Declare the queue and binding** *(Module 2, STA-10A)*
   - Main queue `app_statsstore.<type>`, three retry tiers
     `app_statsstore.<type>.retry.<tier>`, dead-letter queue
     `app_statsstore.<type>.dlq` — one set per type (C1 answered; names per G1/G4)
   - Bind to the `app_stats_input` topic exchange with the type as routing key
   - Set `durable = true`; publish persistent messages *(STA-13A)*
   - Configure the dead-letter exchange `app_stats_dlx` and the retry limit *(STA-12A)*
   - Declare from the shared constants in `stats-common`, identically in every
     broker-connected module, or startup fails with `RESOURCE_LOCKED`

4. **Add the consumer** *(Module 2, STA-20A)*
   - One consumer per type — do not extend an existing consumer to handle two
   - Manual ACK **after** the successful PostgreSQL transaction *(STA-25A)*
   - Per-type concurrency and prefetch, externally configured *(STA-11A)*
   - Failure path must dead-letter with a log entry *(STA-25A)*

5. **Add the table, migration, and indexes** *(Module 2, STA-21A)*
   - New table named after the stat type, created by a **versioned migration**
   - Common envelope columns plus the type's `data` representation
   - `uid` as its own column, with `PRIMARY KEY (ts_device, uid)` **in that column
     order** — scoped per partition, not global *(STA-21B, ADR-003, G2)*
   - Insert with `ON CONFLICT (ts_device, uid) DO NOTHING`, and **increment
     `records_duplicate_suppressed{type}` on a suppressed row** — never silent
   - Index `(tenant, gateway, ts_device DESC, uid DESC)` — the dominant query
     pattern with a unique tie-breaker for keyset paging *(STA-21C)*
   - Retention keyed on `ts.device`, period read from `application.properties`
     *(STA-23A, STA-23B)*
   - Dominant filters must be indexed columns, never JSON path scans

6. **Expose the API endpoint** *(Module 3, STA-33)*
   - GET only *(STA-32)*
   - Same filter parameters as every other type *(STA-34)*
   - Tenant scoping applied server-side *(STA-35)*
   - Pagination consistent with other types *(STA-36)*
   - Update the OpenAPI spec *(STA-37)*

7. **Test** — see Skill 3

8. **Document**
   - Add the type to `CLAUDE.md` §4
   - Record its retention period if it differs from the default

### Definition of done
- [ ] No existing stat type's code was modified
- [ ] Invalid payloads for the new type return 400, and nothing is published
- [ ] A 2xx is returned only after publisher confirm; broker down returns 503
- [ ] A forced write failure lands in **this type's** DLQ
- [ ] A duplicate `uid` redelivery is a harmless no-op
- [ ] A poison message in this type does not stall any other type
- [ ] A cross-tenant read returns nothing
- [ ] Expired rows are removed; rows inside the window are not

---

## Skill 2 — Review a change for tenant isolation

Run this on **every** change touching a query, a controller, or a security
config. Tenant isolation is a security control, not a feature.

### Checklist

- [ ] Is the tenant value derived from the **validated token**, never from a
      request parameter, header, or body field?
- [ ] Can a caller override the tenant by supplying a `tenant` query
      parameter? (They must not be able to.)
- [ ] Is the tenant filter applied in the **query itself**, not by filtering
      results after retrieval?
- [ ] Is the tenant filter applied to **every** query path, including counts,
      aggregates, and pagination cursors?
- [ ] Does the pagination cursor leak information about records the caller
      cannot see?
- [ ] Is the correct field used? **It is `tenant`, always** — `maintenant` is
      never the isolation field. *(Answered: Q23, Q5, ADR-006.)*
- [ ] Does a test exist where `tenant` and `maintenant` **differ**?
- [ ] Does an error message reveal the existence of another tenant's data?
      (404 and 403 can leak; prefer returning empty results.)

### Red flags
- Any `if` statement deciding whether to apply the tenant filter
- A tenant value passed as a method parameter from a controller
- A repository method with no tenant argument
- Tests where every fixture has `tenant == maintenant`

---

## Skill 3 — Write tests for a stat type

### Unit — validation *(Module 1)*
- [ ] Valid payload accepted
- [ ] Each mandatory envelope field missing → 400
- [ ] Each mandatory payload field missing → 400
- [ ] Wrong data type in a field → 400
- [ ] Unknown stat type → 400
- [ ] Extra unexpected fields → confirm intended behaviour (reject or ignore)
- [ ] `ts` in **seconds**, not milliseconds
- [ ] The published message is **identical** to what was received — no transformation
- [ ] An `omsstats` record with `ts.processor` passes unchanged; no `ts.converter`
      handling exists *(ADR-004)*

### Integration — messaging *(Module 1 → 2)*
- [ ] Published with the correct routing key
- [ ] Lands in the correct per-type queue, and only that one
- [ ] 2xx returned **only after** publisher confirm *(STA-04A)*
- [ ] Broker unavailable or confirm timeout → **503**, nothing published *(STA-06A)*
- [ ] Consumed and written to the correct table
- [ ] Simulated write failure → message reaches that type's DLQ *(STA-12A)*
- [ ] Retry limit respected, not infinite
- [ ] Message survives a broker restart *(STA-13A)*
- [ ] A poison message in one type does **not** stall other types *(STA-10B)*

### Integration — persistence *(Module 2)*
- [ ] Written to the type's own table, not a shared one
- [ ] ACK happens **after** the successful transaction, never before *(STA-25A)*
- [ ] A rolled-back transaction does not ACK
- [ ] Duplicate `uid` redelivery is an idempotent no-op, not a DLQ *(STA-25A)*
- [ ] Expired rows removed, keyed on **`ts.device`** *(STA-23B)*
- [ ] A late-arriving record with an old `ts.device` behaves as documented —
      near-immediate expiry is correct, not a bug
- [ ] Retention value read from configuration, not hard-coded
- [ ] Verified against a real PostgreSQL Testcontainer, not a mock

### Integration — API *(Module 3)*
- [ ] Unauthenticated request → 401 *(STA-31)*
- [ ] Authenticated without the required role → 403
- [ ] POST/PUT/PATCH/DELETE → 405, on every endpoint *(STA-32)*
- [ ] Time range filter works at both boundaries
- [ ] **Cross-tenant read returns nothing** — with differing `tenant`/`maintenant`
- [ ] Pagination returns each record exactly once, none skipped
- [ ] Pagination stable when new records arrive mid-iteration

### Load *(before go-live)*
- [ ] Sustained ingestion at the average rate: 267 records/sec/type, 1,333/sec
      across five types
- [ ] Sustained ingestion at the **burst** rate: **13,333/s**, the 10× design
      target from assuming synchronised 10-minute boundaries *(Q13a, answered
      18 Aug 2026)*. Writer is sized for drain (~2,000/s per type), not for peak
- [ ] Queue depth spikes each cycle and drains — **this is the buffer working.**
      Alert on *oldest message age*, never on depth
- [ ] Replay at the requested 2× cap (2,667/s) on top of live traffic, and
      `records_duplicate_suppressed` spikes without an alert firing *(Q13c)*
- [ ] Behaviour when consumption is slower than ingestion — queue grows, nothing lost
- [ ] Behaviour when the database is unavailable — messages buffer, then drain
- [ ] Retention/deletion lag under sustained write load; table and index growth

---

## Skill 4 — Diagnose a stuck or growing queue

The pipeline's defining failure mode: it fails **silently**. The endpoint
keeps returning 200 and RabbitMQ keeps accepting messages while nothing is
being written.

### Triage order

1. **Which queue?** Per-type queues mean the affected type localises the fault.
2. **Is the consumer alive?** Check process health and consumer count on the queue.
   Zero consumers on a growing queue is the answer.
3. **Is the database reachable?** Connection pool exhaustion looks like a slow
   consumer, not an outage.
4. **Is the DLQ growing too?** DLQ growth means messages are being processed
   and *rejected* — a data or schema problem, not a capacity problem.
5. **Is ingestion abnormally high?** Compare against the expected rate — 267/sec
   per type, 1,333/sec across five types. A Heimdal replay after an outage
   produces a legitimate burst; Heimdal owns dead-lettering and retries anything
   we answered with a non-2xx.
6. **Is one message poisoning the consumer?** Same message repeatedly
   retried and failing. Check the retry counter.
7. **Is retention the real problem?** A bloated table or a long-running deletion
   job slows writes and looks exactly like a slow consumer.

### After resolution
- [ ] Redrive DLQ messages once the root cause is fixed — explicit, traceable,
      rate-limited, and per type. Blind automatic replay is not permitted.
- [ ] Confirm no data was lost
- [ ] If the alert did not fire, fix the alert threshold

---

## Skill 5 — Add or change a retention policy

Retention keys on **`ts.device`** — the device's own timestamp, not the time we
received the record *(STA-23B, answered D9)*. Default 90 days.

### Steps
1. Confirm the intended period with the requester — it affects storage cost
2. Set the value in `application.properties`, never in code *(STA-23A)*
3. Restart the writer; confirm the new value is picked up and reported
4. Confirm rows past the boundary are removed
5. Confirm rows inside the boundary are **not** removed
6. Confirm the deletion job is bounded — it must not hold a long transaction or
   scan unindexed
7. Watch deletion lag and table/index growth after the change *(STA-23C)*
8. Record the value and the reason for it

### Cautions
- Shortening retention **deletes data on the next sweep**. Confirm nobody
  depends on it first, and consider running one sweep in dry-run.
- Lengthening retention increases storage. Check the projection against the
  volume figures — up to 115.2M records/day across five types.
- **Late-arriving records expire early, by design.** A gateway offline for weeks
  delivers records already deep into their window. That is the accepted business
  rule, not a defect. Do not "fix" it by switching to `ts.processor`.
- **Unbounded `DELETE` is the trap at this volume.** It creates table and index
  bloat, long transactions, and lock contention that slows the writer. Evaluate
  time partitioning first — dropping a partition is a different operation from
  deleting rows, with different locking and monitoring. If partitioning is not
  approved yet, use bounded, indexed, resumable batches and accept the trade-off
  explicitly *(STA-23C, ADR-003)*.
- Retention lives behind its own abstraction, separate from the write path.

---

## Skill 6 — Extend the API safely

The API is versioned *(STA-38)* specifically so consumers do not break.

### Non-breaking — safe in the current version
- Adding a new optional query parameter
- Adding a new field to a response
- Adding a new endpoint
- Loosening validation

### Breaking — requires a new version
- Removing or renaming a response field
- Making an optional parameter mandatory
- Changing a field's type or format
- Changing default sort order, page size, or pagination semantics
- Tightening validation
- Changing an error status code

### For any change
- [ ] Update the OpenAPI spec in the same commit *(STA-37)*
- [ ] Confirm it is still GET-only *(STA-32)*
- [ ] Run Skill 2 (tenant isolation)
- [ ] Confirm the change applies consistently across **all** stat types

---

## Skill 8 — Instrument a failure path

Run this whenever you add or change code that can fail. The pipeline's
defining characteristic is that it fails *silently* — every failure path must
leave a trace someone can act on.

### For every failure path, confirm

- [ ] Does it log, dead-letter, or return an error? (One of the three —
      never none.)
- [ ] Does the log line include `uid`, stat type, tenant, and gateway, so the
      record can be traced across all three modules? *(D1)*
- [ ] Is the **full payload** kept out of `INFO` and above? These records carry
      device identifiers — IMSI, MAC, gateway IDs. *(D2)*
- [ ] Is the level right? `WARN` for handled-but-abnormal (retry, dead-letter,
      validation failure); `ERROR` only for conditions needing a human. *(D1)*
- [ ] Is there a metric as well as a log? Logs explain *why*; metrics show
      *how often*. *(STA-27)*
- [ ] Would this failure be visible on a dashboard within minutes, or only
      discovered by someone reading logs?

### The silent-failure test

For each new failure path, answer: **if this started happening 100 times an
hour tonight, how would anyone find out before tomorrow?**

If the answer is "someone would notice the data was missing," it is not
instrumented.

### Error response contract *(D3 — RFC 9457 confirmed by ZC)*
- 400 responses use RFC 9457 Problem Details, naming **every** offending field
  and reason in one response — not one round trip per failure
- `uid` echoed back so the rejection can be correlated with our logs
- Never echo the offending **value** — payloads carry device identifiers
- Unrecognised stat type gets its own code (`unknown_stat_type`), not a
  generic field error — it signals a routing problem, not a malformed record
- Same shape reused by Module 1 and Module 3, so they do not diverge
- Cross-tenant reads return **empty**, never 403 — a 403 confirms the record
  exists

---

## Skill 7 — Onboarding a new engineer

Order matters — the domain before the code.

1. **Read** `CLAUDE.md` end to end, especially §5 (rules) and §6 (traps)
2. **Read** `PROJECT_STATUS_AND_PLAN.md` for where the project actually is
3. **Read** `docs/requirements/BRD-v1.2-amendment.md` for the business workflow
4. **Skim** `docs/requirements/SRS-v1.2-amendment.md` — you will cite `STA-nn` IDs
5. **Read** `CLAUDE.md` §7 so you know what is still unsettled and what is closed
6. **Run** the local stack per `DEVELOPMENT.md`
7. **Trace one record** end to end: POST → exchange → queue → table → GET
8. **Break it deliberately:** send an invalid payload, stop the consumer, stop
   RabbitMQ (expect a 503, not a 200), stop PostgreSQL. Watch where each failure
   surfaces — and notice which ones are silent.
9. **First task:** something that exercises Skill 1 or Skill 3

### The four traps to internalise on day one
1. **`tenant` vs `maintenant`** — they are equal in most sample data. Filtering
   on the wrong one passes every test and leaks data in production. Always write
   fixtures where they differ.
2. **Unix seconds, not milliseconds** — treating `ts.device` as millis gives 1970.
3. **Unsorted pagination is not stable** — always sort on an indexed field with a
   unique tie-breaker, not only when the caller asks.
4. **Never swallow an exception** — return an error, dead-letter, or log at
   WARN/ERROR with `uid` and stat type. Silence is the one unacceptable outcome.

### Watch for
This is a JVM/Spring Boot project. If your background is Python, the shape is
familiar even if the syntax is not:

| Concept | Python equivalent | Here |
|---|---|---|
| Dependency injection | manual wiring / FastAPI `Depends` | Spring constructor injection |
| Message consumer | `pika` callback | `@RabbitListener` |
| Repository | SQLAlchemy / psycopg wrapper | Spring Data repository interface |
| Migrations | Alembic | Flyway or Liquibase, versioned |
| Config | `.env` / Pydantic settings | `application.properties` + `@ConfigurationProperties` |
| Request validation | Pydantic model | Bean Validation annotations |
| Route | FastAPI decorator | `@GetMapping` |
