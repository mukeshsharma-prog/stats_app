# Outbox — 3 September 2026

Step 0 of the Phase 2 runway: the pull request description and the four outbound
messages, written out ready to send. **Owner:** Naman Vashishtha.

Nothing in this file is a decision. It is the wording for four asks that no amount
of engineering closes, plus a record of what each answer unblocks so the chase can
be tracked rather than remembered.

| # | Goes to | Carries | Unblocks |
|---|---|---|---|
| A | ZC / Hasan | Q15a, Q15b, Q15c, Q15d (T-061) | Nothing formally — stops the `lorastats` DLQ growing |
| B | ZC | `openapi.yaml` confirm/decline | T-038, and T-039–T-046 transitively — all of Phase 4 |
| C | ZC + security | Q8a-2, Q8b-2, Q13b, Q13c | Shared deployment; hardware sizing |
| D | Product + operations | D5a, D5b signatures | T-051 and T-026 — the only two BLOCKED tasks |

---

## 0. Pull request — `fix/review-t007-t022-production-hardening` → `main`

Branch pushed 3 Sep 2026. Open at:
<https://github.com/mukeshsharma-prog/stats_app/pull/new/fix/review-t007-t022-production-hardening>

**Title:**

```
fix(stats-writer,build): per-type consumer, idempotent writes, and five silent-loss closures
```

**Body:**

```markdown
Four reviewed commits that have been sitting off `main`. Every remaining Phase 2
Writer task touches these files, so this lands first.

### What is in it

| Task | Change |
|---|---|
| T-019 | Per-type manual-ack consumer, queue name from `AmqpTopology`, activated by `LorastatsDeploymentCondition` — one consumer per type, never one extended to two |
| T-020 | Manual ACK after the PostgreSQL transaction commits. Proved by stopping the container and asserting nothing is requeued — a queue message count excludes unacked deliveries and could not prove it |
| T-021 | `StatsBatchWriter` takes `List<StatsEnvelope>` and attributes a failure to a single record even when the list holds one. Individual inserts first, behind a batch-shaped interface (G3) |
| T-022 | `INSERT ... ON CONFLICT (ts_device, uid) DO NOTHING`, affected rows read, `records_duplicate_suppressed{type}` metered. A suppressed write that meters nothing is a test failure |
| T-023 | Counter range-check against the signed `BIGINT` maximum **before** conversion. `Long.MAX_VALUE` stores exactly; `Long.MAX_VALUE + 1` — still inside the schema's 19-digit pattern — fails the record |
| T-058 | `record_version` and `passthrough jsonb` persisted and round-tripped. Both were unmapped before, so every production record was stored without `ts.converter` and `version` |
| T-011 | Index correction (DL-093). V2 shipped `(ts_device DESC, uid DESC)`, which the planner never selects because the PK btree answers it with a backward scan; both ADR-003 indexes were absent. V3 drops the redundant one and creates `idx_lorastats_tenant_clsbox_device_uid` and `idx_lorastats_tenant_uid` |
| T-008 | Test infrastructure moved to a plain `stats-testsupport` module (DL-099). Buildship has no model for `testFixtures`, so all 11 Docker-backed integration tests showed unresolved imports |

### Why the index change matters

Measured on PG17 before V3: the tenant page was reading and discarding 2,444 rows
to return 50, and the `uid`-only lookup cost 989 buffers on one of 90 partitions.

### Decisions logged

DL-093 through DL-099, in `claude/DECISIONS.md`, in the same commits.

### Not in scope

Retry tiers and DLQ routing (T-024), the payload validation gate (T-057) and the
retention job (T-025) are still open. Until T-024 lands, a failed write has nowhere
to go — this branch makes the write correct, not yet recoverable.
```

---

## A. To ZC / Hasan — Q15a–Q15d (task T-061)

> **Subject:** Stats App — four questions on the production `lorastats` records
>
> Hi Hasan,
>
> Thank you for the 15 production records on 29 August. They changed the design
> materially and for the better — every envelope field name we had came from the
> SRS and none of them matched the wire, so we rebuilt against the real format
> before writing the persistence layer rather than after. That was the right
> trade and we would not have found it without the sample.
>
> Four questions came out of it. Only the first is urgent.
>
> **Q15a — which `lorastats` payload dialect is canonical, and are the others
> being retired?**
>
> Three consecutive records in the sample used three incompatible payload shapes.
> All three carry `version: 1`, so nothing on the record distinguishes them:
>
> | `uid` (truncated) | `clsbox` | Shape | Example field |
> |---|---|---|---|
> | `CLSCON-11d3ec4c…` | `04B64810652B` | Bare names, **integer** values, has `timespan` | `"rx_nr_pkts": 219` |
> | `CLSCON-1a449bcd…` | `04B648102E76` | **`lora_`-prefixed** names, integer values | `"lora_rx_nr_pkts": 220` |
> | `CLSCON-a47b270f…` | `04B6481064F9` | Bare names, **string** values, has `rx_type_arol`, no `timespan` | `"rx_nr_pkts": "176"` |
>
> Our reading is that this is either a firmware migration in flight or three
> generations in the field at once. We would rather not guess, because the two
> available responses are very different:
>
> * If one dialect is canonical and the others are being retired, we encode that
>   one and treat the rest as errors — which is what we have built.
> * If all three are legitimate and permanent, we need a discriminator. `version`
>   cannot serve, as all three carry `1`.
>
> **What we have done in the meantime.** We encode the bare-name/integer dialect
> (the first row above) and route the other two to a per-type dead-letter queue
> rather than widening the schema to absorb all three. A permissive union would
> accept a genuinely malformed record silently; the dead-letter queue keeps the
> disagreement visible. The cost is that roughly two-thirds of the `lorastats` we
> saw in the sample would dead-letter today, and our dead-letter queues carry no
> expiry by design, so that volume accumulates until this is answered. Nothing is
> blocked and nothing is lost — but the sooner we know, the less there is to
> redrive.
>
> **Q15b — is `ts.device` genuinely assigned by the gateway, or stamped on
> arrival?** It equals `ts.server` in 14 of the 15 records and differs by one
> second in the fifteenth. We key our 90-day retention on `ts.device` because a
> gateway that was offline should have its records expire on when they were
> *measured*. If the field is actually stamped at receipt, that policy is
> retention-on-arrival and one of our stated behaviours describes nothing real.
>
> **Q15c — does Heimdal POST `_id`?** It is present in the dump you sent. We
> accept and preserve it either way, so this is confirmation rather than a
> request for a change.
>
> **Q15d — can `status` ever report a *failed* conversion?** Every record in the
> sample carries `["conversion ok"]`. If it can carry a failure, we are storing
> records that Heimdal already knows are bad, and we would add a metric for it.
>
> Happy to take Q15a on a call if that is quicker — the other three can be a
> one-line reply each.
>
> Best,
> Naman

---

## B. To ZC — `openapi.yaml` confirmation

> **Subject:** Stats App — OpenAPI definition for confirmation (sent 21 August)
>
> Hi Hasan,
>
> Following up on the API contract. When we asked for the endpoint definitions in
> the original question set, the answer was to create an OpenAPI definition for
> you to confirm or decline. We wrote it and sent it on 21 August:
> `docs/contracts/openapi.yaml`.
>
> It is the one item that genuinely blocks scope for us. The read API is nine
> tasks and none of them can start against an unconfirmed contract — if the shape
> changes after we build the resource server, the tenant claim mapping, the
> pagination and the error catalogue, that is rework rather than adjustment.
>
> The whole of it is one page of decisions:
>
> * `GET /v1/stats/{type}` and `GET /v1/stats/{type}/{uid}`. **GET only** — every
>   other method returns 405 with no side effect.
> * `from` and `to` are **required**, in Unix seconds, with an exclusive upper
>   bound. There is no unbounded query.
> * Fixed sort, `ts.device DESC, uid DESC`, always — there is no sort parameter.
>   Without a unique tie-breaker a paginated query can return one row twice or
>   skip one entirely.
> * Keyset pagination via an opaque signed cursor that binds the tenant and every
>   filter it was issued for.
> * Errors are RFC 9457 Problem Details.
> * Isolation is server-side on `tenant`, taken from the validated token claim.
>   **There is no tenant query parameter**, and a caller-supplied tenant in a
>   query, header, path or body is ignored.
>
> A "no objections" is enough for us to start. If you would rather review it with
> the eventual consumers, could you let us know who they are and roughly when —
> we can plan around a date, but not around silence.
>
> Best,
> Naman

---

## C. To ZC + security — Q8a-2, Q8b-2, Q13b, Q13c

Send with a real deadline. Two of these have a stated default that we implement
unless someone objects; the other two are requests for data we would otherwise
have to assume.

> **Subject:** Stats App — inbound auth, Keycloak values, and two capacity questions
>
> Hi both,
>
> Four items. Two carry our default position, so they need an objection rather
> than an answer; two are requests for data.
>
> **1. Heimdal inbound authentication (Q8a-2) — our default is OAuth2 client
> credentials.** The Input port is currently unauthenticated. That was a
> deliberate decision on 18 August so development would not block on the answer,
> and the risk was accepted on the understanding that it closes before any shared
> deployment. **Anything that can reach the Input port today can write
> statistics.**
>
> Our position: OAuth2 client credentials. Keycloak already exists in this
> landscape, a `heimdal-ingest` client in the `statsapp` realm with the
> `stats.ingest` role costs nothing new, and credential rotation is already
> solved there. mTLS would be equally sound *if* the platform already runs a CA
> both sides trust — if it does, say so and we will do that instead, but we would
> rather not stand one up for this alone.
>
> The mechanism is a configuration switch on our side
> (`stats.input.auth.mode = none | mtls | oauth2`) with no default in code, so
> this is a settings change and not a rebuild. **Unless you object by 17
> September 2026, we will implement OAuth2 client credentials** and come back to
> you for the issuer, audience and client identity.
>
> **2. Keycloak realm values (Q8b-2) — proposed, live in dev, needs confirming.**
> We built the realm rather than waiting: realm `statsapp`; clients `stats-api`,
> `stats-input`, `stats-dev-cli`; audience `stats-api`; read role
> `stats.readOnly`; ingest role `stats.ingest`; tenant claim `tenant`, as a
> **single-valued string**. The importable realm is at
> `docs/setup/keycloak-realm-statsapp.json`.
>
> The one item worth a second look is the single-valued tenant claim. It is
> deliberate: a multi-valued claim turns tenant isolation into `tenant IN (...)`,
> and one misconfigured user then reads several tenants with every test still
> passing. If any consumer needs access to more than one tenant, we would rather
> know now and design a privileged access path explicitly than discover it as a
> claim that happens to be an array.
>
> **3. Gateway report jitter (Q13b) — the most valuable answer you can give us.**
> Do gateways spread their reporting across the 10-minute cycle, or do they fire
> on the boundary? We currently design for the synchronised case: 800,000 records
> landing in about 60 seconds, a design target of 13,333/s against a 1,333/s
> average. If gateways jitter, the peak drops by an order of magnitude and so
> does the hardware.
>
> This does not need a survey. A histogram of `ts.server mod 600` over any 24-hour
> window answers it outright, from whatever telemetry you already hold. If it is
> easier, we can also answer it ourselves from our first day of real ingest — in
> which case tell us and we will size conservatively until then.
>
> **4. Replay rate cap (Q13c).** If Heimdal is replaying a backlog after an
> outage, can it cap the replay at twice nominal — about 2,667/s? A six-hour
> outage is 28.8 million messages, which is roughly three hours of replay at that
> cap and holds total ingest at 4,000/s while live traffic continues.
>
> We will return 503 if we are pushed beyond what we can confirm, and Heimdal
> retries on any non-2xx, so nothing is lost either way — a 503 during replay is
> the backpressure channel working rather than an incident. But our own rate
> limiting cannot substitute for the sending side honouring a cap, so we would
> like the agreement rather than to rely on the mechanism.
>
> Best,
> Naman

---

## D. To product + operations — D5a and D5b

These are the only two tasks in the tracker that are BLOCKED, and both are blocked
on a signature rather than on information. Engineering has already agreed the
substance of D5b; what is missing is somebody accepting it on the product side,
because it is a deliberate data-loss path.

> **Subject:** Stats App — two decisions that need a product signature
>
> Hi,
>
> Two items. Both have an engineering recommendation attached; neither is
> engineering's to sign, because one is a deliberate data-loss path and the other
> is a spend.
>
> **1. Late-arriving records (D5b) — we are asking you to accept a small, known
> data loss.**
>
> We keep 90 days of statistics, and we expire them based on when the gateway
> *measured* the reading, not when we received it. A gateway that has been
> offline for months therefore delivers records that are already past the
> retention window at the moment they arrive. There is no storage for them —
> the daily partition they belong in has already been dropped.
>
> Our recommendation is to skip the write, count it as
> `records_expired_on_arrival`, acknowledge the message so it does not retry
> forever, and **alert whenever that counter is non-zero** — because a systemic
> gateway backlog and a harmless one-off look identical in a raw count.
>
> The alternative is writing rows into a partition that does not exist, which
> fails, or re-creating expired partitions so retention can immediately remove
> them again. Neither is better; they are just less visible.
>
> This is genuinely lost data, which is why we would like it acknowledged rather
> than assumed. Engineering agreed the policy on 29 August. One task (T-026)
> stays blocked until it is signed.
>
> **2. Backup and restore targets (D5a) — we need a number.**
>
> We have no RPO or RTO for this system. Our proposal, to react to rather than
> to invent:
>
> * **RPO ≤ 5 minutes**, via continuous WAL archiving. This is the tight one.
>   Heimdal retries only when we return an error, so anything we accepted and
>   then lost is unrecoverable — nobody will send it again.
> * **RTO 1 hour** to restore a single day's partition, **24 hours** for a full
>   cluster restore. This is deliberately loose. It is 90 days of telemetry
>   rather than financial records, and at multi-terabyte scale a tight
>   full-restore target may not be purchasable at any sensible price.
>
> The question that settles it: **if we lost the last 15 minutes of statistics,
> or took 8 hours to come back, what actually breaks?** If the answer is
> "nothing urgent", the proposal above is comfortable. If something downstream
> depends on this data being continuously present, we need to know now — it
> changes the storage design and the spend, not just the runbook.
>
> An earlier architecture document proposed RPO ~0 and RTO 30 minutes. We do not
> think that is achievable at this volume and it was never costed, which is why
> we are putting a different number in front of you rather than adopting it.
>
> Best,
> Naman

---

## Tracking

Update `Stats_App_Tracker.xlsx` → *Open Questions* when each is sent, and again
when answered. The tasks that come off the blocked list:

| Answer arrives | Then |
|---|---|
| Q15a | ADR-007 §4 is either confirmed or re-opened; the `lorastats` DLQ stops growing; T-060 thresholds can be normalised |
| `openapi.yaml` confirmed | T-038 starts; T-039–T-046 unblock in sequence; Phase 4 has a start date |
| Q8a-2 | T-017's `mtls` or `oauth2` branch gets its real implementation; shared deployment becomes possible |
| Q13b | Prefetch, concurrency and replica counts get their real values — all configuration, no code |
| Q13c | T-049 (replay drill) and T-052 (redrive runbook) get a rate to test against |
| D5b signed | T-026 unblocks |
| D5a signed | T-051 unblocks; backup configuration can be specified |
