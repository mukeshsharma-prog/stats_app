# §2b answers — 18 August 2026

**Answered by:** Naman Vashishtha · **Status:** DECIDED, implement as written · **Supersedes:** the
`Needs ZC / Hasan` table in `claude/CLAUDE.md` §7 and rows O-02, O-04, O-12 in ARD v1.1 §18.

Seven items were open against ZC. All seven now have a working answer. Three were answered
outright, two were delegated to engineering ("you suggest"), one is a build instruction ("make it"),
and one turned out not to exist. Each row below records the answer, the engineering decision derived
from it, and what genuinely stays open.

> **These are our decisions, not ZC's.** Where a row says *provisional*, it is safe to build on and
> cheap to change — but it must be re-confirmed when ZC responds, and none of them may be
> represented to ZC as agreed.

---

## 1. Internal reference repo — CLOSED, it does not exist

**Answer:** *"There is no reference repo."*

This was never a waiting game; there was nothing to wait for. Q1a and the D-01 residual are closed,
and ARD v1.1 O-12 is withdrawn.

**Decision.** The Stats App defines its own conventions and becomes the reference for whatever comes
after it. Nothing is blocked, but nothing is inherited either — CI, logging, secrets, and deployment
each need a deliberate choice rather than a copy.

| Convention | Decision | Where it lands |
|---|---|---|
| Build | Gradle Wrapper only, Kotlin DSL, version catalog in `gradle/libs.versions.toml`; no module declares its own versions | already in place |
| Spring Boot | **3.5.16 is now the pinned version, not a provisional one.** With no reference repo to override it, there is nothing left to reconcile it against | `libs.versions.toml` — drop the "provisional" comment |
| Logging | Structured JSON to stdout, one event per line; `traceId` and `uid` on every record; no payloads on the success path | `stats-common` logging config |
| Secrets | Environment variables injected by the platform; never in `application.properties`, images, or Git. The dev stack uses literal dev-only values that are obviously fake | `docker/` and deployment manifests |
| CI | `./gradlew build` plus Testcontainers integration tests on every push; the build fails on a failed schema-fixture assertion | to be written in Phase 1 |
| Deployment | Three images, one per application module, tagged with the Git SHA; immutable | Phase 1 |

**Consequence to accept:** these conventions are now ours to maintain, and a later ZC standard may
force a migration. That is strictly better than blocking Phase 1 on a document that does not exist.

---

## 2. netstats nested rules — ANSWERED, strict validation now written

**Answer:** *"for now keep them as mandatory."*

Applied to `docs/schemas/netstats-v1.schema.json`, which is no longer a draft.

| Question | Decision | Why |
|---|---|---|
| Are `eth0`/`wwan` mandatory? | **Yes, both required** | As answered. A gateway missing either is a malfunctioning gateway, and finding out at ingest is better than finding out from a null column. |
| Are other interfaces allowed? | **Yes**, validated against the same interface shape via `patternProperties` | A new interface name must never require a schema change (STA-14A). `eth1` on a gateway is not an error. |
| Is `apipa` optional? | **Optional** | Forced by the evidence, not chosen: in ZC's own supplied sample, `eth0` carries `apipa` and `wwan` does not. Requiring it would reject the only real record we have. |
| Do `tx`/`rx` counters stay strings? | **Yes on the wire, `BIGINT` in the column** | Heimdal sends strings; changing that is ZC's call and not worth blocking on. But the schema now constrains them to digits only, so a non-numeric counter fails at **ingest with a 400** instead of failing a cast at query time months later. The Writer converts once, on write. |

**The overflow gap that the schema cannot close.** The counter pattern caps at 19 digits, which
rejects anything above `9999999999999999999`. Signed `BIGINT` stops at `9223372036854775807`. The
range between the two is not expressible as a regex, so **the Writer must range-check before
conversion**. Left unchecked this is the silent overflow that corrupts a value with no error
anywhere — the failure mode with no observable behaviour. Fixture:
`fixtures/invalid/netstats-counter-overflow.json`.

**Still open, and narrower than before:** IPv6. The schema accepts IPv4 only, because IPv6 does not
appear in the sample. `fixtures/invalid/netstats-ipv6-address.json` asserts rejection today; if ZC
confirms IPv6 gateways exist, that fixture flips to valid and the `ipv4` `$def` widens. One schema
change, no code change.

---

## 3. Inbound Heimdal auth — DEFERRED, endpoint built unauthenticated behind a switch

**Answer:** *"for now don't use heimdal auth, just see the schema and based on this write code, will
see about heimdal later when it comes from ZC."*

**Decision.** Build the Input endpoint's validation, publish and confirm path now. Authentication
becomes a **pluggable filter selected by configuration**, so ZC's eventual answer is a config change
and a filter implementation — never a rewrite of the endpoint.

```properties
# stats-input
stats.input.auth.mode=none          # none | mtls | oauth2   (no default in the code — must be set)
stats.input.auth.required-role=stats.ingest
# mtls
stats.input.auth.mtls.trusted-issuer-dn=
stats.input.auth.mtls.allowed-subject-dn=
# oauth2 client credentials
stats.input.auth.oauth2.issuer-uri=
stats.input.auth.oauth2.audience=
```

**Three guardrails, because "we'll add auth later" is how endpoints ship unauthenticated:**

1. The property has **no default in code**. A deployment that does not set `stats.input.auth.mode`
   fails to start. `none` has to be typed by a person.
2. `mode=none` logs a `WARN` on every startup naming the endpoint as unauthenticated.
3. A test asserts the application refuses to start with `mode=none` under the `prod` profile. This
   is the one that actually stops it reaching production.

**Risk accepted and recorded:** until ZC answers, anything that can reach the Input port can write
statistics. That is acceptable on a local dev stack and in no other environment. This must be raised
again before any shared deployment.

---

## 4. Keycloak realm and claims — PROPOSED BY ENGINEERING

**Answer:** *"You suggest this as per your knowledge."*

A complete working realm is committed at `docs/setup/keycloak-realm-statsapp.json`, imported by the
dev stack at startup. These are proposals, and the point of the design is that ZC's answer changes
configuration only.

| Item | Proposed value | Reasoning |
|---|---|---|
| Realm | `statsapp` | One realm for the app; the Datahub Core realm, if there is one, replaces it with a config change |
| Issuer | `http://localhost:8080/realms/statsapp` (dev); real HTTPS issuer in production | Environment-owned; validated on every token as a **string**, never fetched. A containerised module overrides only the JWK set URI (`keycloak:8080`) so it can fetch keys over the compose network — the issuer value itself does not change. |
| API client | `stats-api` — confidential, resource server, no flows enabled | The API never initiates a login, it only validates bearer tokens |
| Ingress client | `stats-input` — service account, client credentials | Ready for item 3 when Heimdal auth arrives |
| Dev helper | `stats-dev-cli` — public, direct access grants | So a developer can fetch a token with one `curl`. **Dev realm only** |
| Audience | `stats-api` | Rejecting a token minted for another audience is what stops a token from a neighbouring service being replayed here |
| Read role | `stats.readOnly` (realm role) | Matches the name already used throughout the SRS and ARD |
| Ingest role | `stats.ingest` (realm role) | Same |
| Tenant claim | **`tenant`, a single-valued String**, mapped from the user attribute of the same name | See below |

**Why the tenant claim is single-valued and a String.** A multi-valued claim quietly turns every
query into `tenant IN (...)`, and the moment that exists, one misconfigured user can read several
tenants and every test still passes. Single-valued makes the isolation predicate `tenant = :claim`,
which is the shape the index is built for and the shape a reviewer can verify at a glance. If a user
genuinely needs several tenants, that is the privileged cross-tenant access model in ARD v1.1 O-11 —
a separate approved role with its own audit design, not a wider claim.

```properties
# stats-api — fail-closed, no defaults
spring.security.oauth2.resourceserver.jwt.issuer-uri=
stats.security.audience=
stats.security.tenant-claim=tenant
stats.security.read-role=stats.readOnly
```

**Fail-closed rules, all four release-gated by a test:**

- A token with **no** `tenant` claim is rejected with `403`. It never means "all tenants". Fixture
  user: `no-tenant`.
- A token with an **empty** `tenant` claim is rejected the same way. Fixture user: `empty-tenant`.
- A token without `stats.readOnly` is rejected with `403`. Fixture user: `no-role`.
- A `tenant` value supplied as a query parameter, header, or cursor field is **ignored**, and the
  claim always wins.

The realm ships **five** users precisely because each one is a test: `alpha-reader` and
`beta-reader` are the cross-tenant isolation pair, `no-role` proves 403, `no-tenant` (no `tenant`
attribute at all) proves an **absent** claim fails closed, and `empty-tenant` proves a **blank** one
does too. Absent and empty are separate users on purpose — they are different code paths, and a
naive `claim != null` check passes the second and then filters on an empty string.

---

## 5. Burst profile — ASSUME THE WORST CASE UNTIL MEASURED

**Answer:** *"do better validation if not also."* — read as: do not wait for ZC's number, work it out
properly, and make the pipeline safe if the answer turns out to be the bad one.

**Decision: design for synchronised reporting.** Gateways are assumed to align on 10-minute
boundaries unless Heimdal telemetry proves otherwise. This is the safe direction of error: a system
sized for a burst handles a smooth flow, and a system sized for the average collapses on a burst.

Every gateway reporting every type once per 10-minute cycle is **800,000 records per cycle**
(160,000 × 5). Where those land inside the 600-second window is the entire question:

| Arrival window | Peak rate | Multiple of average | Reading |
|---|---|---|---|
| Spread evenly over 600 s | 1,333/s | 1× | The average. Only true if reporting is jittered. |
| 120 s | 6,667/s | 5× | ARD v1.0's provisional peak |
| **60 s** | **13,333/s** | **10×** | **The design target** |
| 30 s | 26,667/s | 20× | Survivable only because the broker absorbs it |

**What this changes:**

| Area | Decision |
|---|---|
| Input sizing | Size for **13,333/s**. Input is stateless and CPU-bound on validation, so this is a replica-count decision, not an architecture one. |
| Publisher confirms | Confirm latency, not throughput, is the limit — a burst that outruns confirms turns into 503s and Heimdal retries into the next burst. Measure p99 confirm latency under burst before fixing replica count. |
| Queue depth | A 60-second synchronised burst leaves ~12,000 messages per type queued that the Writer drains over the rest of the cycle. **This is the buffer working as designed** and must not be alerted on. Alert on *oldest message age* exceeding one cycle, not on depth. |
| Writer prefetch | Start at **500 per consumer thread** — above the 100–500 micro-batch band so a batch can always be filled, low enough that one consumer cannot monopolise a burst. Tune against measurement. |
| Writer sizing | Sized for **drain**, not for peak. It does not need 13,333/s; it needs to clear a cycle's backlog before the next cycle arrives — about 2,000/s per type sustained. |
| Partitioning | Unchanged. The burst is intra-day, so daily partitions are unaffected. WAL and checkpoint sizing are affected: 800,000 rows in 60 s is a checkpoint spike, and `max_wal_size` must be set for the burst rather than the average. |

**The one thing worth asking ZC for:** report jitter. If gateways spread their reporting across the
cycle instead of firing on the boundary, the peak drops by 10× and so does the hardware. It is a
Heimdal-side configuration question and it is worth more than any tuning we can do here.

---

## 6. Replay burst after a Heimdal dead-letter recovery — PROPOSED BY ENGINEERING

**Answer:** *"you suggest."*

**The size.** Heimdal holds what it could not deliver. The planning figure is a **6-hour outage:
28.8 million messages** (115.2M ÷ 24 × 6). This is not a worst case, it is a plausible one — a night
of an unnoticed outage is longer.

**The problem.** Replay does not arrive instead of live traffic, it arrives **on top of it**. Left
uncontrolled, Heimdal replays as fast as it can, the Input returns 503 under the strain, Heimdal
treats 503 as a failure and re-queues, and the recovery makes the outage longer. Three decisions
prevent that.

**Decision 1 — ask Heimdal to rate-limit replay to 2× nominal (2,667/s).** Then:

```
backlog 28,800,000 ÷ 2,667/s  = ~3 hours of replay
total ingest during replay    = 2,667 + 1,333 = 4,000/s   (3× nominal, well inside the burst design)
```

A 6-hour outage costs about 3 hours of recovery, and the system stays inside limits it was sized
for. Without a cap, the same backlog either takes the pipeline down or takes as long anyway.

**Decision 2 — 503 is the backpressure channel, and it already works.** If replay outruns the
publisher-confirm path, Input returns 503, Heimdal retains the message and retries. That is the
contract from STA-04A doing exactly what it was designed for. Nothing new is needed — but the DLQ
redrive runbook must state that a 503 during replay is **the mechanism working**, not an incident.

**Decision 3 — replayed records are duplicates by definition, and that is already handled.** A
replayed record carries the same `uid` and the same `ts_device`, so `ON CONFLICT (ts_device, uid) DO
NOTHING` absorbs it as a no-op. `duplicates_total` rising sharply during a replay is expected.
Fixture: `fixtures/edge/<type>-replay-duplicate.json`.

**The trap in the replay path.** A replayed record's `ts_device` is as old as the outage. A replay
after an outage longer than the retention window produces records with **no live partition**, which
lands them in the `expired_on_arrival` path — a deliberate data-loss path that still needs product
sign-off (D5b). For a 6-hour outage this is irrelevant; for a recovery from backup weeks later it is
the whole story. Fixture: `fixtures/edge/<type>-late-arrival.json`.

---

## 7. Realistic non-production samples — BUILT

**Answer:** *"make it."* Done — `docs/schemas/fixtures/`, **68 files across four directories**, replacing the two
combined bundles (both kept, so nothing referencing them breaks). `docs/schemas/validate-fixtures.py`
asserts the whole set: **76 assertions, 0 failures**, and it exits non-zero so CI can gate on it.
*(As of 5 September 2026 the gate is **88 assertions** over 76 fixture files — see ADR-007 and DL-115. The 68/76 figures above are the 18 August set, kept as history.)*

| Directory | Files | What it proves |
|---|---|---|
| `valid/` | `<type>-valid`, `<type>-boundary`, `<type>-tenant-isolation-pair` per type, plus three netstats cases | The happy path, the edges of each numeric range, and the tenant pair |
| `invalid/` | `<type>-missing-required`, `<type>-wrong-type` per type, six envelope failures, four netstats failures | Every rejection returns 400 and publishes nothing |
| `edge/` | `late-arrival`, `retention-boundary-inside`, `replay-duplicate`, `clock-skew-future` per type | The cases that pass schema validation and carry a behavioural decision. `clock-skew-future` is decided (29 Aug 2026) and marked `$expect: "input-reject"`; `late-arrival` still awaits the D5b signature |
| `malformed/` | Eight non-schema inputs | The parser layer, before validation runs |

**The fixtures that matter most,** because each one catches a failure that is invisible in a naive
test:

- **`<type>-tenant-isolation-pair.json`** — two records on the same gateway, and `record_b.maintenant`
  is deliberately set to `tenant-alpha`. An implementation filtering on `maintenant` returns both
  records for tenant-alpha and leaks tenant-beta's data. An implementation filtering on `tenant`
  returns exactly one. This is the single highest-value fixture in the set.
- **`envelope-ts-in-milliseconds.json`** — a millisecond timestamp is a *valid integer*. Nothing
  fails loudly; the record simply lands ~53,000 years in the future where retention never reaches
  it. The envelope schema now bounds `ts` to 2000–2100, which is what turns this into a 400.
- **`envelope-oms-ts-converter.json`** — ADR-004 says a record carrying `ts.converter` is invalid,
  but `ts` accepted unknown members, so it passed. The envelope now carries an explicit
  `not: {required: [converter]}`.
  > **REVERSED 29 August 2026 (ADR-007 §2).** Production sends `ts.converter` on **100% of
  > records**, so this "fix" rejected every record Heimdal sends. The `not` clause is removed
  > and the fixture moved to `valid/omsstats-ts-converter-present.json`. Worth keeping as a
  > record of how a schema gap found by a fixture was closed in the wrong direction — the
  > fixture was right that the rule was unenforced, and wrong that the rule was correct.
- **`netstats-counter-overflow.json`** — the 20-digit counter the Writer must range-check.
- **`<type>-boundary.json` for lorastats** — every counter at zero except `tx_success` at the signed
  64-bit maximum. A 32-bit column wraps this silently; that is the whole point of the fixture.
- **`<type>-clock-skew-future.json`** — a gateway with a bad clock reports `ts.device` an hour ahead
  of `ts.server`. **Answered on 29 Aug 2026 (Q14a, ADR-003 D9): rejected at Input** with 400 and
  the field code `clock_skew_exceeded`, beyond a configurable tolerance of five minutes. The files
  stay under `edge/` and stay schema-valid — the check compares two fields, which JSON Schema cannot
  do — and now carry an explicit `$expect: "input-reject"` marker that the CI gate asserts.

Three of these fixtures found real schema gaps when first run — `ts.converter`, millisecond
timestamps, and the counter ceiling all validated clean against the schemas as they stood. The
schemas were tightened; that is the fixtures earning their keep before a line of Java exists.

---

## What is still open after this

| Item | Owner | Note |
|---|---|---|
| Heimdal inbound auth — mTLS or OAuth2, issuer, audience, rotation | ZC | Deferred by decision, not blocked. Must be closed before any shared deployment. |
| Keycloak realm/client/claim confirmation | ZC + security | Proposal above is live in the dev realm; confirmation is a config change. |
| netstats IPv6 | ZC | Schema accepts IPv4 only; one `$def` widens if IPv6 exists. |
| ~~Gateway report jitter~~ | ZC / Heimdal | **ANSWERED 4 Sep 2026 (Q13b, DL-104): gateways JITTER across the cycle.** Expected steady state ~1,333/s; the 13,333/s design target below becomes configured headroom, and hardware is not sized down until the `ts.server mod 600` histogram confirms it. |
| Heimdal replay rate cap at 2× nominal | ZC / Heimdal | Needs agreement, not just our own rate limiting. |
| ~~Clock-skew policy — accept or reject a future `ts.device`~~ | ~~Product + engineering~~ | **CLOSED 29 Aug 2026 (Q14a, ADR-003 D9).** Rejected at Input beyond `stats.input.validation.max-clock-skew` (default `PT5M`): 400, field code `clock_skew_exceeded`, metered. It was an engineering decision, not a product one. |
| Late-arrival `expired_on_arrival` sign-off | Product | Unchanged — D5b. Engineering agreed the behaviour on 29 Aug 2026 and added a non-zero-counter alert; the signature is still outstanding. |
| Volume arithmetic confirmation | Product + Heimdal | Unchanged — D-11 / O-01. |
