# Business Requirements Document — Implementation Amendment

**Product:** Stats App  
**Document version:** 1.2 draft  
**Status:** `PENDING BUSINESS APPROVAL — PostgreSQL and answered decisions incorporated`

## 1. Purpose of this amendment

BRD v1.0 correctly explains the intended business workflow but does not identify the first API consumers, measurable business outcomes, data-loss/replay expectations, or V1 deferrals. This amendment makes those items explicit so engineering can make correct storage, API, security, and operational decisions.

## 2. V1 business scope

### 2.1 In scope

V1 delivers a reliable statistics-ingestion and retrieval capability for five types: `lorastats`, `ltestats`, `sysstats`, `omsstats`, and `netstats`. The `netstats` nested-interface rules were answered on 18 August 2026 (Q4a): `eth0` and `wwan` are mandatory, additional interfaces are allowed, `apipa` is optional, and counters stay strings on the wire. Strict validation is finalized.

The business capability includes authenticated ingestion from Heimdal Core, per-type buffering, per-type persistent storage, automatic retention, operational visibility, and a secure, tenant-scoped, read-only API. The solution must retain the ability to add a new type without reworking existing types.

### 2.2 Conditional scope

`netstats` is fully in the V1 delivery sequence. Q4a was answered on 18 August 2026 and the schema is strict, so this is no longer conditional. One narrow item remains open and does not gate delivery: **Q4b — do any gateways report IPv6 addresses?** The schema accepts IPv4 only; if IPv6 exists, one `$def` widens and no code changes.

### 2.3 Explicitly out of scope for V1

| Out-of-scope capability | Reason / future trigger |
|---|---|
| Write/update/delete through the Stats API | The API is a retrieval interface only. |
| Cross-tenant user access | Requires a separately approved privileged-access model, business justification, audit, and security design. |
| Aggregation/analytics/dashboard calculations | Not included unless a named consumer use case and query/SLO requirements are approved. |
| MongoDB implementation | Out of scope. PostgreSQL is the only V1 persistence implementation. |
| Unbounded historical data | Retention policy applies; the default is 90 days unless an approved type-specific policy changes it. |
| Local disk buffering in Input service | Would change the stateless scaling and operational model. |

## 3. Consumers and use cases — approval required

The phrase “external systems / UI” is not sufficient to design an API. Each consumer must have an owner, access model, expected query patterns, and operational criticality.

| Consumer/use case | Owner | Tenant context | Data type(s) | Required filters | Expected volume/SLO | Status |
|---|---|---|---|---|---|---|
| Operations troubleshooting UI | PENDING | Normal single-tenant user | PENDING | Time range, gateway, type | PENDING | PENDING APPROVAL |
| External integration | PENDING | Normal single-tenant service | PENDING | PENDING | PENDING | PENDING APPROVAL |
| Support/administration | PENDING | Potentially privileged | PENDING | PENDING | PENDING | PENDING APPROVAL |
| Compliance/export use case | PENDING | PENDING | PENDING | PENDING | PENDING | PENDING APPROVAL |

No API operation, index, or aggregation should be created solely because it seems useful. It should trace to one of these approved consumer use cases.

## 4. Business rules requiring sign-off

| ID | Business rule | Decision needed |
|---|---|---|
| BR-29 | An accepted inbound record is not silently lost during normal service restart or transient persistence failure. | Approve at-least-once delivery and duplicate-safe outcome. |
| BR-30 | If the Stats Input service or RabbitMQ is unavailable, the business must define whether Heimdal retries/replays and what data-loss window is acceptable. | Approve upstream retry/replay expectation and recovery target. |
| BR-31 | A tenant may see only statistics belonging to that tenant. | Approve the token claim that identifies the tenant and handling of tenantless records. |
| BR-32 | Data records, logs, DLQs, and backups may contain device identifiers and potentially IMSI. | Approve classification, access, retention, deletion, and audit requirements. |
| BR-33 | All records beyond the approved retention period are automatically removed. | Approve whether 90 days is a default or hard maximum, the timestamp basis, and per-type overrides. |
| BR-34 | A record that cannot be processed remains visible for operational follow-up. | Approve DLQ ownership, review cadence, redrive authority, and resolution target. |
| BR-35 | The service must remain useful as data volume grows. | Approve capacity envelope, end-to-end delay target, API response target, and backlog/replay objective. |

## 5. Business acceptance criteria

| Capability | Business acceptance criterion |
|---|---|
| Valid ingestion | A valid record from Heimdal is accepted, appears in the correct PostgreSQL stat-type table, and becomes available through the API within the approved end-to-end delay. |
| Invalid ingestion | An invalid record is rejected with an actionable safe error and is observable to operations; it does not enter the queue or database. |
| Isolation | A tenant-authenticated user/service can retrieve its own records but cannot retrieve or infer another tenant’s records. |
| Type isolation | A fault, high load, or poison message in one stat type does not block normal processing of another approved type. |
| Reliable recovery | A simulated database outage causes visible queue growth and controlled retries/DLQ handling, not silent loss; recovery drains the backlog within the approved target. |
| Retention | Expired records are removed automatically according to the approved type policy, while unexpired records remain readable. |
| Operability | Operations can view per-type throughput, errors, latency, main queue depth, DLQ depth, and service readiness; alerts and runbooks are tested. |
| API usability | Named consumers can use the approved OpenAPI document to authenticate, retrieve data with filters/pagination, and handle defined errors without engineering support. |

## 6. Stakeholder decisions and ownership

| Decision | Required approver(s) | Target date | Status |
|---|---|---|---|
| V1 type scope and `netstats` nested-field rules | Product owner + Heimdal owner | — | **DECIDED 18 Aug 2026 (Q4a).** All five types; `eth0`/`wwan` mandatory, other interfaces allowed, `apipa` optional, counters digit-only strings. Open: IPv6 (Q4b), which widens one schema `$def`. |
| Inbound delivery/replay/data-loss acceptance | Product owner + Heimdal owner + operations | PENDING | PENDING APPROVAL |
| API consumer/use-case catalogue | Product owner + consumer representatives | PENDING | PENDING APPROVAL |
| Retention default/max/per-type policy | Product owner + data owner + compliance | PENDING | PENDING APPROVAL |
| Tenantless and privileged-access policy | Product owner + security | PENDING | PENDING APPROVAL |
| Capacity/SLO targets | Product owner + operations + engineering | PENDING | PENDING APPROVAL — baseline 160,000 gateways every 10 minutes; 23.04m/type/day |
| DLQ operational ownership | Operations + product owner | PENDING | PENDING APPROVAL |
| Data classification/retention/access | Security/compliance + data owner | PENDING | PENDING APPROVAL |

## 7. Volume and delivery expectation

The current business answer is approximately 160,000 gateways reporting every 10 minutes for every stat type. The corrected average is 23,040,000 records per type per day, 92,160,000 for four types, and 115,200,000 for five types. The release plan must validate the synchronized reporting burst, PostgreSQL table/partition/index design, RabbitMQ backlog, and end-to-end persistence delay at that scale.

Heimdal owns dead-letter/retry behavior when it receives a non-2xx response from the Input Module. The Stats App must return non-2xx when RabbitMQ is unavailable and must not add a local persistent buffer.

## 8. Release decision

V1 business acceptance is not complete until all listed acceptance criteria are demonstrated in a controlled environment using representative data and the named API consumers have approved the published OpenAPI contract. A feature may be deferred, but it must be declared explicitly; it must not be silently excluded from delivery.
