# Heimdal Core -> Stats Input HTTP Contract v1 Draft

**Status:** `PENDING HEIMDAL AND SECURITY APPROVAL`  
**Upstream owner:** ZC / Heimdal Core  
**Input service:** `stats-input`

## 1. Endpoint

| Item | Draft value |
|---|---|
| Method | `POST` |
| Path | `/stats` (confirm against the deployed Datahub API convention) |
| Content type | `application/json` |
| Body | One canonical statistics record per request |
| Success meaning | The record passed validation and RabbitMQ publisher confirmation completed. |
| Correlation | `uid` from the record; platform trace header may be added if approved. |

The Input Module validates the received message and publishes it byte-identically/canonically as answered. Stats App does not transform `omsstats` timestamps because Heimdal already sends `ts.processor`.

## 2. Response matrix

| Condition | Draft status | Heimdal behavior |
|---|---:|---|
| Valid record and publisher confirm | 200 or 202, exact code pending | Treat as accepted; do not redeliver. |
| Invalid JSON/schema/unknown type | 400 | Do not retry unchanged record; fix sender/schema. |
| Missing/invalid inbound credentials | 401 | Configuration/credential issue; do not retry blindly. |
| Authenticated but forbidden | 403 | Configuration/identity issue. |
| RabbitMQ unavailable/confirm timeout | 503 | Heimdal dead-letter/retry handling applies because response is non-2xx. |
| Request too large | 413 | Do not retry without correcting payload. |
| Rate limited/temporary overload | 429 or 503, exact policy pending | Retry using approved backoff. |
| Unexpected Input failure | 5xx | Heimdal dead-letter/retry handling applies. |

The exact successful status, retryable status list, retry schedule, and maximum retry/retention behavior must be approved with ZC.

## 3. Authentication — DEFERRED BY DECISION (Q8a, 18 August 2026)

ZC's answer was: *build the endpoint against the schema now, and we will settle Heimdal auth later.* So authentication is a **configuration-selected filter**, not an assumption baked into the endpoint — the eventual answer is a filter implementation plus a config change, never a rewrite.

```properties
stats.input.auth.mode=none          # none | mtls | oauth2 — NO DEFAULT IN CODE
stats.input.auth.required-role=stats.ingest
```

Three guardrails, because "we'll add auth later" is how endpoints ship unauthenticated:

1. The property has **no default in code** — a deployment that omits it fails to start, so `none` has to be typed by a person.
2. `mode=none` logs a `WARN` on **every** startup, naming the endpoint as unauthenticated.
3. A test asserts the application **refuses to start under the `prod` profile** with `mode=none`. That is the one that actually stops it reaching production.

> **Risk accepted and recorded.** Until Q8a-2 closes, anything that can reach the Input port can write statistics. That is acceptable on a local dev stack and in no other environment. **This must be raised again before any shared deployment.**

**Q8a-2, still open with ZC:** whether it is mTLS (certificate identity, trust chain, rotation, revocation) or OAuth2 client credentials (issuer, audience, client identity, scopes, rotation). The `401`/`403` rows in the response matrix above apply once a mode other than `none` is selected.

Credentials, tokens, private keys, and secrets are never stored in the repository or logged.

## 4. Validation contract

The message must satisfy the common envelope and the schema selected by `type`. The current V1 types are `lorastats`, `ltestats`, `sysstats`, `omsstats`, and `netstats`. All five schemas in `docs/schemas/` are strict and normative; `netstats` was finalised by Q4a on 18 August 2026 (`eth0`/`wwan` mandatory, other interfaces allowed, `apipa` optional, counters are digit-only strings).

Invalid responses use the approved RFC 9457-compatible Problem Details shape. The response may identify field names, reason codes, stat type, and `uid`, but must not echo sensitive offending values or access credentials. The business answer permits payload logging in the Java logger/Docker logs; production data-classification and access policy still governs what is retained and who can see it.

## 5. Delivery/replay behavior

The Input Module is stateless and does not buffer locally. If RabbitMQ cannot accept/confirm the message, Input returns non-2xx and Heimdal owns dead-letter/retry handling.

PostgreSQL retains a uniqueness defence even though Heimdal states duplicates are not intentionally delivered: `PRIMARY KEY (ts_device, uid)` scoped per partition, with `ON CONFLICT DO NOTHING` and a `records_duplicate_suppressed{type}` counter (ADR-003, ADR-005). A replayed record carries its original `ts_device`, so it lands in the same partition and is absorbed.

**Replay rate — our proposal, not yet agreed (Q13c).** A 6-hour Heimdal outage is roughly 28.8M messages. We ask Heimdal to cap replay at **2× nominal (2,667/s)**, which bounds catch-up to about 3 hours while normal ingest continues, holding total ingest near 4,000/s. We cannot enforce this; a 503 under replay is the backpressure channel working as designed, not an incident. The ZC owner must confirm the cap and whether Heimdal replays the same `uid` after recovery.

## 6. Contract tests

The integration suite must verify valid and invalid samples for each type, response status and body, no publish on 400/auth failure, publisher-confirm timing, broker outage returning non-2xx, same `uid` correlation, and safe handling of a replay/redelivery.
