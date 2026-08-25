# Architecture Review Board Submission — Implementation Amendment

**Product:** Stats App, Datahub Core add-on  
**Document version:** 1.2 draft  
**Purpose:** Correct and complete ARB v1.0 so it is usable as an implementation decision record.  
**Decision status:** `PENDING ARB APPROVAL — PostgreSQL and answered decisions incorporated`

## 1. Decision requested

The ARB is asked to approve the target architecture subject to the decision log in Section 9. The baseline architecture remains:

```text
Heimdal Core
  -> authenticated HTTP POST
Stats Input service
  -> publisher-confirmed AMQP publish
RabbitMQ exchange and per-type queues
  -> independently configurable consumers
Stats Writer service
  -> PostgreSQL tables, indexes/partitions, and retention processing per type
Stats API service
  -> Keycloak-protected, tenant-scoped, GET-only API
External systems and UI
```

The application has **three independently deployable services**: `stats-input`, `stats-writer`, and `stats-api`. RabbitMQ and PostgreSQL are externally managed infrastructure dependencies. This corrects the v1.0 description that called all four layers independently deployable.

## 2. Architecture principles

| Principle | Approved design intent |
|---|---|
| Separation of responsibility | Input validates/publishes; Writer persists; API reads. No component bypasses another. |
| Isolated stat types | One routing key, queue, consumer, PostgreSQL table, retention setting, metric label set, and API operation per type. |
| Safe acceptance | An HTTP success is returned only after the approved RabbitMQ publisher-confirm condition is met. |
| At-least-once persistence | Writer acknowledges only after a successful idempotent database outcome; redelivery must be harmless. |
| Server-side tenant isolation | Request tenant is derived from a validated token claim and applied in every database query. |
| Additive evolution | A new type introduces a new schema, adapter/configuration, queue, consumer, table, indexes, endpoint operation, and tests without changing existing type handling. |
| Operability before release | Each failure path logs safe metadata, emits a metric, has an alert, and is covered by a runbook. |

## 3. Persistence decision

**Decision:** PostgreSQL is the only V1 persistence technology. MongoDB is removed from the implementation scope.

PostgreSQL is selected for the clarified high-volume workload, SQL query patterns, per-type tables, explicit uniqueness, and operational control over indexes, partitioning, retention, backup, and restore. The expected average workload is 23.04 million records per type per day, 92.16 million/day for four types, or 115.2 million/day for five types including `netstats`; synchronized ten-minute reporting may create a much higher burst rate.

| PostgreSQL implementation implication | Required clarification / control |
|---|---|
| One table per stat type | Table naming convention: `<stat-type>`, e.g. `lorastats`; include `omsstats` and `netstats`. |
| Dominant query pattern | Index and query design must prioritize `(tenant, gateway, ts.device)` with a deterministic unique tie-breaker. |
| Duplicate safety | `uid` is a normal PostgreSQL column. Uniqueness is `PRIMARY KEY (ts_device, uid)` scoped **per partition** (ADR-003, G2); a global unique index is not creatable on a partitioned table. Suppressed duplicates are metered, never silent. |
| Retention | The answer selects `ts.device`; choose partition drop/detach or controlled batched deletion and document late-arrival consequences. |
| Schema design | Decide typed columns versus `jsonb` per type; do not use JSON path scans for dominant filters. |
| Disaster recovery | Backup frequency, PITR/restore target, retention, encryption, and restore testing are production requirements—not local-development assumptions. |

## 4. AMQP architecture

### 4.1 Target topology

The SRS requires a dedicated queue and consumer per stat type. The singular `app_statsstore` name from the implementation brief is therefore treated as a **name prefix**, not as a shared queue.

```text
Exchange: app_stats_input                 [TopicExchange]
Routing key: <stat-type>

app_statsstore.lorastats  <- lorastats
app_statsstore.ltestats   <- ltestats
app_statsstore.sysstats   <- sysstats
app_statsstore.omsstats   <- omsstats
app_statsstore.netstats   <- netstats
```

Each main queue must be durable. Accepted messages must be published as persistent. Retry and dead-letter behavior must be defined consistently for every type in the AsyncAPI/topology document.

### 4.2 Topology decisions required

| Decision | Recommended direction | Status |
|---|---|---|
| Exchange type | **TopicExchange**, while using exact type routing keys; it allows future pattern subscriptions without changing existing bindings. | ANSWERED |
| Topology declaration | Every broker-connected module declares identical exchange/queue/binding definitions; confirm whether `stats-api` is broker-connected. | ANSWERED / CLARIFY API SCOPE |
| Retry design | **Three** per-type retry tiers `app_statsstore.<type>.retry.<tier>`; fixed delays, not a backoff multiplier; all delays and the attempt cap externalised to `application.properties`, starting at 30 s / 5 min / 30 min. Immediate requeue is never used for a persistent failure. | **DECIDED (G1/G4, ADR-002)** |
| DLQ design | Per-type DLQ `app_statsstore.<type>.dlq` via DLX `app_stats_dlx`, with a documented redrive process and restricted access. | **DECIDED (G1, ADR-002)** |
| Producer success | Publisher confirm succeeds before HTTP acceptance response. Broker unavailable/time-out returns 503; no local buffering. | **DECIDED (D4, ADR-005)** |
| Consumer acknowledgement | Manual acknowledgement after a successful PostgreSQL transaction; duplicate-safe via `ON CONFLICT (ts_device, uid) DO NOTHING`, metered. The retry/DLQ handoff must be publish-confirmed **before** the original is ACKed. | **DECIDED (D7, ADR-005)** |

## 5. Security architecture

The target API follows OAuth2/OIDC through Keycloak. The SRS also requires that only Heimdal Core can write. Both channels require a fully specified security contract.

| Surface | Required specification before code | Default direction, not a final decision |
|---|---|---|
| Heimdal -> Input | Authentication protocol, issuer/client identity, audience/scope, certificate/secret rotation, endpoint allow-list, 401/403 behavior. | **Deferred by decision (Q8a).** A config-selected filter `stats.input.auth.mode=none\|mtls\|oauth2`, no default in code, `WARN` on every `none` startup, and a test refusing `prod`. **Risk accepted: the Input port is unauthenticated until Q8a-2 closes** — must close before any shared deployment. |
| API -> consumer | Keycloak realm, resource/client identifier, issuer/JWK configuration, roles/scopes, tenant claim, claim-to-authority mapper, 401/403 behavior. | **Proposed and live in the dev realm (Q8b, ADR-006):** realm `statsapp`, confidential client/audience `stats-api`, read role **`stats.readOnly`**, tenant claim `tenant` single-valued String. Awaiting ZC confirmation (Q8b-2), which is a config change. |
| Tenant isolation | Authoritative tenant claim, mapping validation, tenantless record policy, privileged support/admin model, audit behavior. | Store/filter `tenant`; `maintenant` is not a substitute. No user-supplied tenant override. |
| Data handling | Classification of IMSI, gateway MAC, tenant data; payload logging configuration; redaction of secrets; log/DLQ access; retention; encryption; backup. | Payload logging is permitted in V1 Java/Docker logs; never log tokens/secrets; configure retention/access and document the sensitivity. |

## 6. Reliability, scaling, and operations

### 6.1 Required service behavior

| Scenario | Required outcome |
|---|---|
| Invalid inbound record | HTTP 400 using approved problem-details contract; safe metadata logged; nothing published. |
| RabbitMQ unavailable | HTTP 503 (or formally approved equivalent); no local persistent buffer; Heimdal retry/replay policy governs recovery. |
| Database transient failure | Message remains safely retriable under bounded policy; relevant metric and log emitted. |
| Database permanent failure | Message moved to its type DLQ; safe error logged; queue/DLQ alerts fire. |
| Process shutdown/scaling | Graceful stop allows in-flight writes to finish; unacknowledged messages return to the queue. |
| Duplicate redelivery | Idempotent write outcome; no duplicate document and no DLQ due solely to a known duplicate. |
| PostgreSQL dependency failure | Writer readiness fails; backlog grows visibly; messages are not silently lost. |

### 6.2 Non-functional decisions still required

| Topic | Required measurable decision |
|---|---|
| Capacity | Baseline is 160,000 gateways every 10 minutes: 266.67 records/sec/type, 1,066.67/sec for four types, 1,333.33/sec for five types **average**. The **design target is 13,333/s** — a 10× synchronised burst, assumed until Heimdal telemetry proves otherwise (Q13a). Input is sized for peak, the Writer for drain (~2,000/s per type), prefetch 500, `max_wal_size` for the burst. Replay is requested at a 2× cap (2,667/s). Still to validate: payload sizes and the PostgreSQL storage/index projection. **Q13b — gateway jitter — is worth 10× on hardware.** |
| Performance | Ingestion response target, end-to-end persistence lag target, API latency/page target, max query window and page size. |
| Availability | Target availability, planned maintenance policy, RTO, RPO, broker/database dependency availability. |
| Scaling | Replica count, consumer concurrency/prefetch per type, autoscaling signal, queue backlog budget, connection limits. |
| Observability | Dashboard and alert owners; processed/error/duration/queue depth/DLQ-depth metric definitions and thresholds. |
| Disaster recovery | Backup schedule, restore procedures, data-loss acceptance, routine restore testing. |

## 7. Risks and mitigations

| Risk | Impact | Mitigation and release gate |
|---|---|---|
| ~~`netstats` sample is not yet a formal schema~~ — **CLOSED (Q4a, 18 Aug 2026)** | Residual: the counter schema caps at 19 digits, which is wider than signed `BIGINT`. An unchecked overflow corrupts the value silently. | The Writer MUST range-check counters before conversion (STA-04C). Fixture: `invalid/netstats-counter-overflow.json`. |
| Upstream retry/replay is unknown | Broker/Input outage may cause data loss or an untested surge. | Approve inbound contract and test replay at peak rate. |
| Tenant claim/record policy is unclear | Cross-tenant exposure or unusable data. | Security approval and tests with `tenant != maintenant`. |
| Shared queue interpretation is implemented | Head-of-line blocking and failed independent scale requirement. | Approve per-type topology; test poison-message isolation. |
| PostgreSQL retention is implemented as unbounded row deletion | At this volume it may create bloat, locks, and lag. | Evaluate time partitioning first; otherwise use bounded, indexed, observable deletion batches. |
| Capacity is not quantified | Inadequate indexes, queue limits, and deployment sizing. | Agree NFR/capacity document and run load test before go-live. |
| Sensitive fields leak to logs/DLQ | Security/compliance breach. | Data-classification approval, redaction tests, access control and retention controls. |

## 8. Required artefacts before implementation approval

| Artefact | Owner | Approval needed |
|---|---|---|
| PostgreSQL implementation baseline | Engineering + data platform | Capacity/DB approval |
| SRS v1.2 implementation amendment | Product + engineering | Product/engineering sign-off |
| BRD v1.2 implementation amendment | Product owner | Business sign-off |
| OpenAPI 3.1 document | API owner + consumers | Consumer/security sign-off |
| AsyncAPI / AMQP topology document | Platform + engineering | Platform sign-off |
| JSON Schema catalogue with fixtures | Heimdal owner + engineering | Integration sign-off |
| ADRs 001–006 | Engineering/ARB | ARB or delegated technical authority |
| NFR/capacity specification | Product + operations | Operations sign-off |
| Security/data handling specification | Security/compliance | Security sign-off |
| Operations runbook | Operations + engineering | Operational-readiness sign-off |

## 9. ARB decision log

| ID | Decision | Decision / rationale | Owner | Status |
|---|---|---|---|---|
| ARB-01 | V1 database | PostgreSQL only; MongoDB removed. | ARB | PENDING APPROVAL |
| ARB-02 | Application deployment | Three independently deployable Docker images; RabbitMQ/PostgreSQL are infrastructure. | ARB | PENDING APPROVAL |
| ARB-03 | Queue topology | Per-type queue using `app_statsstore.<type>` convention. | Platform/ARB | PENDING APPROVAL |
| ARB-04 | Exchange/topology owner | Choose exchange type and one ownership model. | Platform/ARB | PENDING APPROVAL |
| ARB-05 | Delivery semantics | Publisher confirms + at-least-once + idempotent persistence. | ARB + Heimdal owner | PENDING APPROVAL |
| ARB-06 | Retention | `ts.device` is selected; approve partitioning or bounded deletion, per-type properties, and late-arrival behavior. | ARB + data owner | PENDING APPROVAL |
| ARB-07 | Security | Approve inbound auth, Keycloak client/claim mapping, data handling. | Security/ARB | PENDING APPROVAL |
| ARB-08 | Scale/SLO | Approve capacity envelope, deployment, monitoring, RTO/RPO. | Operations/ARB | PENDING APPROVAL |
| ARB-09 | V1 type scope | All five types. `netstats` nested-field rules answered 18 Aug 2026 (Q4a); the schema is strict. Only IPv6 support (Q4b) is open, and it widens one `$def`. | Product/ARB | **DECIDED** |

## 10. Board approval condition

Architecture approval authorizes **Phase 1 foundation work** after ARB-01 and ARB-02. It authorizes Input/Writer/API production feature work only when the corresponding decision rows and companion contracts have been approved. No approval is implied for assumptions marked `PENDING APPROVAL`.
