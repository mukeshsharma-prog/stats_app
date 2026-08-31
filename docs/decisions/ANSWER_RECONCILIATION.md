# Stats App — Answer Reconciliation

**Source:** `Stats_App_Answers_v1_0.docx` and the supplied `netstats.json`.
**Status:** Incorporated into the v1.2 implementation update. **Reconciled 24 August 2026.**

> **This file records what ZC answered, and what we did about it. It is not the
> latest word on any of those decisions.** Later amendments override it, in this
> order of authority:
>
> 1. `docs/architecture/adr/` — the ADR file is the record of decision.
> 2. `claude/CODE_START_CHECKLIST.md` §A2 — gate decisions **G1–G6**, 18 Aug 2026.
> 3. `docs/decisions/2B-ANSWERS-18AUG2026.md` — the seven §2b answers, 18 Aug 2026.
> 4. This file.
>
> **If you cached an earlier version, three things changed:**
>
> * **`uid` uniqueness is `PRIMARY KEY (ts_device, uid)` per partition** (G2,
>   ADR-003), not a unique index on `uid`. Rows Q6 and D8 below are amended.
> * **Q1a is closed — there is no internal reference repo.** Spring Boot 3.5.16 is
>   pinned, not provisional.
> * **Q4, Q8 and Q11 are answered**, on 18 Aug 2026. They are struck through in
>   the second table rather than deleted, so the trail stays readable.

## Decisions now closed

| Reference | Answer received | Implementation consequence |
|---|---|---|
| Q1 | Java 21; Spring Boot greater than 3.5.x | Use Java 21. **Spring Boot pinned at 3.5.16** in the version catalog (G6, 18 Aug 2026) — **final, not provisional**, because Q1a closed on 18 Aug 2026: there is no internal reference project. CI, logging, secrets and deployment conventions are ours to define (ADR-001). |
| Q2 | One Docker image for each module | Build three deployable images: Input, Writer, and API. |
| C1 | One queue per stat type | Use per-type queues; the singular `app_statsstore` is a naming prefix only. |
| Q4 | `netstats.json` supplied | `netstats` can enter the implementation scope, but the supplied file is a sample, not a complete formal schema. Nested optionality and constraints require confirmation or documented implementation defaults. |
| Q5 | Every record has a tenant | Require `tenant`; derive API tenant from the validated token. |
| Q6 | `uid` unique; Heimdal does not deliver duplicates | Keep a PostgreSQL uniqueness defence against broker redelivery and race conditions. **Amended 24 Aug 2026 (ADR-003, G2):** on the partitioned design this is `PRIMARY KEY (ts_device, uid)` — in that column order — scoped **per partition**. A unique index on `uid` alone is not creatable on a table partitioned by `ts_device`. |
| Q7 | Heimdal already sends the correct OMS timestamp | Remove Stats App timestamp normalization. Canonical input is already `ts.server`. |
| Q9 | Heimdal handles dead-lettering when it receives non-2xx | Input returns non-2xx on broker failure; no local Input buffer. |
| Q12 | ZC owns Heimdal Core | Use ZC as the upstream contract owner/contact. |
| Q14 | Dominant query pattern: tenant, gateway, `ts.device` | PostgreSQL indexes and API query design must prioritize `(tenant, clsbox, ts.device)`. |
| Q15 | PostgreSQL only; phased delivery is allowed for each stat type | Remove MongoDB from the implementation path and documentation. |
| D1 | Java logger to Docker logs; format is up to engineering | Use structured JSON where practical, but Docker stdout/stderr is the V1 sink. |
| D2 | Payload may be logged | This is accepted for V1 but requires a security warning, configurable logging, and redaction of tokens/secrets. Payloads may contain identifiers such as IMSI. |
| D4 | HTTP success after broker confirmation | Implement publisher confirms before 2xx. |
| D5 | RabbitMQ unavailable => non-2xx | Use 503 for transient broker unavailability unless platform convention requires another non-2xx status. |
| D9 | Retention uses `ts.device` | PostgreSQL retention must be based on device timestamp. Late-arriving records may be near expiry on arrival; this is an accepted business rule that must be documented/tested. |
| D3 | RFC 9457 response shape confirmed | Use the approved Problem Details shape. |
| D6 | Published message is identical to received | No OMS or other transformation in Input; validate and publish the received record **byte-identically**. This is load-bearing for ADR-003: a redelivered record keeps its `ts_device`, lands in the same partition, and hits the same key. **Do not confuse this with the unknown-field policy, which is `D14`** — see `claude/CLAUDE.md` §8. |
| D8 | `uid` is its own PostgreSQL column | Do not use a Mongo `_id`; use a PostgreSQL `uid` column. **Amended 24 Aug 2026:** uniqueness is `PRIMARY KEY (ts_device, uid)` per partition, not a unique index on `uid` — see Q6 above and ADR-003. A suppressed `ON CONFLICT DO NOTHING` insert MUST increment `records_duplicate_suppressed{type}`. |
| D10 | `omsstats` is in scope | Include it in every type list. |

## Answers that remain ambiguous or need an implementation choice

| Reference | Remaining point | Required next action |
|---|---|---|
| ~~Q1~~ | ~~“Spring Boot > 3.5.x” is not a reproducible version.~~ | **CLOSED.** 3.5.16 in `gradle/libs.versions.toml` (G6). Q1a closed 18 Aug 2026 — no internal reference service exists, so nothing remains outstanding. |
| Q3 | “Every module declares exchanges, queues and bindings” conflicts with service boundaries if it literally includes the API. | Interpret as every broker-connected deployable; confirm whether `stats-api` should have RabbitMQ dependencies. Use shared topology constants and an identical-declaration integration test. |
| ~~Q4~~ | ~~`netstats.json` shows one sample but does not state whether `eth0`, `wwan`, their nested fields, and `dns` are mandatory or extensible.~~ | **ANSWERED 18 Aug 2026 (Q4a).** `eth0`/`wwan` mandatory; other interfaces allowed via `patternProperties`; `apipa` optional; counters stay digit-only strings on the wire, `BIGINT` in the column. The schema is strict, not a draft. **Residual: the Writer must range-check counters** — the 19-digit cap is wider than signed `BIGINT`. Only IPv6 (Q4b) is open. |
| ~~Q8~~ | ~~A dummy `readOnly` role is permitted, but realm, client, issuer, audience, and tenant claim remain to be configured.~~ | **ANSWERED 18 Aug 2026 (Q8b), ADR-006.** Realm `statsapp`; clients `stats-api` / `stats-input` / `stats-dev-cli`; audience `stats-api`; read role **`stats.readOnly`** — *not* the bare `readOnly` placeholder; tenant claim `tenant`, single-valued String. Live in the dev realm (`docs/setup/keycloak-realm-statsapp.json`), fail-closed with no defaults in code. ZC confirmation (Q8b-2) is a config change. Inbound Heimdal auth is **deferred by decision** (Q8a): `stats.input.auth.mode` has no default and `prod` refuses `none`. |
| ~~Q11~~ | ~~“See file in mail” does not provide a complete edge-case sample set in this workspace.~~ | **BUILT 18 Aug 2026 (Q11a); REBUILT ON PRODUCTION 29 Aug 2026 (ADR-007).** 77 fixtures across `valid/`, `invalid/`, `edge/`, `malformed/` in `docs/schemas/fixtures/`, catalogued in `MANIFEST.md`. `docs/schemas/validate-fixtures.py` asserts 87 of them and exits non-zero for CI. The 18 Aug set was derived from SRS v1.0 and every envelope field name in it was wrong. |
| D7 | ACK timing is left to engineering. | **DECIDED (ADR-005).** Manual ACK after the successful PostgreSQL transaction. Note the ordering hazard: a retry/DLQ handoff must be **publish-confirmed before** the original is ACKed — ACK-then-publish opens a silent loss window. |
| D11 | Batch versus individual writes is left to engineering. | **Settled by G3 (18 Aug 2026).** Bounded micro-batching (100–500 records or 50–200 ms) with single-record fallback is the target, matching ARD-ADR-005. Start with individual writes behind a batch-shaped interface, then benchmark at the clarified peak. |
| D12 | Invalid-filter behavior is left to engineering. | **DECIDED (`docs/contracts/openapi.yaml`).** `from >= to` returns 400 `invalid_time_range`; the query timestamp is `ts.device`. The maximum query window is provisional pending load testing: 7 days with a `clsbox` filter, 1 hour without. |
| D13 | Health checks are left to engineering. | **DECIDED.** Separate liveness/readiness. Readiness probes only that module's real dependencies: Input → broker, **not** the database; Writer → PostgreSQL and broker; API → PostgreSQL and Keycloak, **not** the broker — the API has no broker connection at all, which Q3a settled on 29 Aug 2026 (ADR-002 §11). |

## Volume correction

The answer text writes `160k*10*6*24*numberOfStat`, but “every 10 minutes” means **six reporting intervals per hour**, not ten. The corrected calculation is:

```text
records per type per day = 160,000 gateways × 6 × 24
                         = 23,040,000 records/type/day

4 types = 92,160,000 records/day
5 types = 115,200,000 records/day

steady records/second/type = 160,000 / 600 = 266.67
steady records/second for 4 types = 1,066.67
steady records/second for 5 types = 1,333.33
```

These are average rates. **Answered 18 Aug 2026 (Q13a): assume synchronised ten-minute boundaries until Heimdal telemetry proves otherwise.** Every gateway reporting every type once per cycle is 800,000 records per cycle; landing them in 60 s gives the design target of **13,333/s — 10× the average**.

| Arrival window | Peak rate | Multiple of average |
|---|---:|---:|
| Spread evenly over 600 s | 1,333/s | 1× — only true if reporting is jittered |
| 120 s | 6,667/s | 5× |
| **60 s** | **13,333/s** | **10× — the design target** |
| 30 s | 26,667/s | 20× |

Input is sized for 13,333/s (a replica-count decision — it is stateless and CPU-bound on validation). The Writer is sized for **drain**, about 2,000/s per type, not for peak. Writer prefetch starts at 500. Queue depth spiking each cycle is the buffer working: **alert on oldest message age, never on depth.** `max_wal_size` is sized for the burst, not the average.

**Q13b — gateway report jitter — is the single most valuable open question**, worth 10× on hardware.

## `netstats` sample interpretation

The supplied record contains the common envelope and this payload shape:

```text
data.eth0.apipa : string IP address
data.eth0.gw    : string IP address
data.eth0.tx    : string counter
data.eth0.rx    : string counter
data.eth0.ip    : string IP address
data.eth0.dns   : array of string IP addresses
data.eth0.mask  : string IP address/mask

data.wwan.gw    : string IP address
data.wwan.tx    : string counter
data.wwan.rx    : string counter
data.wwan.ip    : string IP address
data.wwan.dns   : array of string IP addresses
data.wwan.mask  : string IP address/mask

data.timespan  : integer seconds
```

**Answered 18 August 2026 (Q4a).** The schema is no longer a draft:

| Question | Answer |
|---|---|
| Are `eth0`/`wwan` always present? | **Yes, both mandatory.** |
| Are additional interfaces allowed? | **Yes**, validated against the same interface shape via `patternProperties`, so a new interface name never needs a schema change (STA-14A). |
| Is `apipa` optional? | **Optional** — ZC's own sample carries it on `eth0` and omits it on `wwan`. |
| Must counters remain strings? | **Yes on the wire**, `BIGINT` in the column, and constrained to digits only so a non-numeric counter fails at ingest with a 400. |

**The one gap the schema cannot close:** the digit pattern caps at 19 digits (`9999999999999999999`), which is wider than signed `BIGINT` (`9223372036854775807`). That gap is not expressible as a regex, so **the Writer MUST range-check before conversion** — an unchecked overflow corrupts the value with no error anywhere. Fixture: `fixtures/invalid/netstats-counter-overflow.json`.

**Still open:** IPv6 (Q4b). The schema accepts IPv4 only; if ZC confirms IPv6 gateways exist, one `$def` widens and no code changes.
