# Heimdal Core -> Stats Input HTTP Contract v1 Draft

**Status:** `PENDING HEIMDAL AND SECURITY APPROVAL` — **record shape re-derived from production 29 August 2026 (ADR-007)**  
**Upstream owner:** ZC / Heimdal Core  
**Input service:** `stats-input`  
**Machine-readable twin:** `docs/contracts/openapi-input.yaml` — linted in CI and rendered with the read API onto one page, `stats-api-v1-draft.html`, by `docs/contracts/build-html.sh` (DL-145). This file is the narrative and the record of what is agreed; the YAML states what `stats-input` is built to do. Change both together.

## 0. Handover — what ZC configures in Heimdal

The values Heimdal needs to deliver to this endpoint, with what is built and what is still open. Added 18 September 2026 after the transport was confirmed as HTTP (DL-145); the rows marked **OPEN** are the questions to return with.

| Item | Our value | Status |
|---|---|---|
| Transport | HTTP `POST`, one record per request, `Content-Type: application/json` | **Confirmed 17 Sep 2026** — relayed to engineering by management; the ForwardRule question below is what turns this into a configuration |
| URL — **Heimdal** | `https://<ingress-host>/internal/stats` | Path **DRAFT** — confirm against the deployed Datahub convention (D-03). `stats.input.endpoint.internal-path`, so a prefix is a property change. **Two doors since 21 Sep 2026 (DL-151):** this one is Heimdal's; `/external/stats` is for callers that are not Heimdal and must not be configured in the ForwardRule |
| Success response | `200`, JSON `{"status":"accepted","traceId":"…"}`, `X-Trace-Id` header matching `traceId` | Built (DL-163, 6 Oct 2026). `202` is the only alternative the code admits (`stats.input.endpoint.success-status`); it returns the same body |
| Retry on | `503`, `429` (after `Retry-After: 1`), any other `5xx` | Built — §2 |
| Never retry on | `400`, `413` | Built — the verdict is deterministic; resending the same bytes gets the same answer |
| Body limit | 256 KB | Built, `PROVISIONAL` (DL-121) |
| Rate limit | 4,000 req/s **per instance** | Built, `PROVISIONAL` (DL-121). The only replay control we own |
| Replay after an outage | ≤ 2× nominal, 2,667 records/s | **Agreed by Heimdal 4 Sep 2026** (Q13c, DL-109); not enforceable by us |
| Credential | None today; `prod` refuses to start unauthenticated | **OPEN — Q8a-2.** mTLS or OAuth2 client credentials; our position is OAuth2 |
| Is it a ForwardRule? | Unknown | **OPEN — Q8a-3.** If yes: auth is basic-in-URL, which we have not built, and retry is bounded by `maxRetry` — tell us the value |
| `ts.expiresOn` spelling | Either accepted: `"2026-08-13T10:30:01Z"` or `{"$date": "2026-08-13T10:30:01Z"}` | **OPEN — Q15e.** The 29 Aug sample had the string; the live datastore returns the object. Which does the forward send? (DL-146) |
| `ts.device` correction | Accepted and preserved: `meta.tsDeviceCorrected`, `ts.deviceOrig` | **OPEN — Q15f.** When does Heimdal rewrite `ts.device` to `ts.server`? Live records show both corrected and uncorrected skews (DL-146) |
| `netstats` | Schema, queue and table ready | **OPEN — Q15g.** Zero `netstats` records exist in the datastore. Where do they go, and will they come down this path? (DL-146) |

## 1. Endpoint

| Item | Draft value |
|---|---|
| Method | `POST` |
| Path | `/internal/stats` for Heimdal Core; `/external/stats` for every other caller (confirm both against the deployed Datahub API convention, D-03) |
| Content type | `application/json` |
| Body | One canonical statistics record per request |
| Success meaning | The record passed validation and RabbitMQ publisher confirmation completed. |
| Correlation | `uid` from the record; platform trace header may be added if approved. |

**Two doors, one behaviour (DL-151, 21 Sep 2026).** Management asked for one ingest endpoint for internal calls — Heimdal Core — and one for external calls. Both paths are bound to the same handler: the same envelope gate, the same publisher-confirm rule, the same 256 KB / 4,000 req/s bounds (one shared rate bucket per instance), the same authentication filter. The ingress routes and restricts them separately, and every Input metric carries `source="internal"` or `source="external"` (every log line `ingestSource`), so what external callers send is visible apart from what Heimdal sends. **What the split does not do:** it is not a second authentication mechanism, and it does not check that an external caller is entitled to the `tenant` in the record — until Q8a-2 gives the filter an identity, restricting who reaches `/external/stats` is the ingress's job. This document is Heimdal's contract; `/external/stats` is described in `openapi-input.yaml` beside it.

The Input Module validates the received message and publishes it byte-identically/canonically as answered. Stats App does not transform `omsstats` timestamps because Heimdal already sends `ts.server`.

**Input validates the ENVELOPE only (ADR-007 §5).** It does not validate `data` against the per-type payload schema — that is the Writer's gate, and a payload failure is a dead-letter rather than a 400. The reason is asymmetric cost: Heimdal retries only on a non-2xx, so a 400 issued for a payload disagreement is **unrecoverable data loss**, while a dead-letter is recoverable by redrive. This matters in practice today: production sends `lorastats` in three mutually incompatible payload dialects, and rejecting the two we do not encode at the HTTP boundary would destroy them.

## 2. Response matrix

| Condition | Draft status | Heimdal behavior |
|---|---:|---|
| Valid record and publisher confirm | 200 or 202, exact code pending | Treat as accepted; do not redeliver. |
| Invalid JSON, **invalid envelope**, or unknown type | 400 | Do not retry unchanged record; fix sender/schema. |
| Valid envelope, **payload fails its per-type schema** | **200/202 — accepted and published**, then dead-lettered by the Writer | Nothing. Heimdal must NOT redeliver. Disposition is a Stats App operations concern (ADR-002 §12, ADR-007 §5). |
| `ts.device` ahead of `ts.server` beyond the tolerance | 400, field code `clock_skew_exceeded` | Do not retry unchanged; **fix the gateway clock**. See §4. |
| Missing/invalid inbound credentials | 401 | Configuration/credential issue; do not retry blindly. |
| Authenticated but forbidden | 403 | Configuration/identity issue. |
| RabbitMQ unavailable, confirm timeout, **or an unroutable message** | 503 | Heimdal dead-letter/retry handling applies because response is non-2xx. |
| Request too large | 413 `payload_too_large` | Do not retry without correcting payload. |
| Rate limited | 429 `too_many_requests`, with `Retry-After: 1` | Retry using approved backoff. |
| Unexpected Input failure | 5xx | Heimdal dead-letter/retry handling applies. |

**Success body (DL-163, 6 October 2026).** Both ingest paths return
`Content-Type: application/json` with `{"status":"accepted","traceId":"<X-Trace-Id>"}`
after RabbitMQ confirms publication. The body and header carry the same per-request
identifier used as the AMQP correlation ID. `accepted` describes the broker handoff;
it does not guarantee a PostgreSQL commit. Read through the API after Writer completes,
or inspect Writer metrics and the DLQ if the record is not visible. Invalid envelopes
and unconfirmed publishes still return Problem Details, never this acknowledgement.
The immutable v3 archive predates this change and returns an empty success body.

The exact successful status, retryable status list, retry schedule, and maximum retry/retention behavior must be approved with ZC.

**Implemented values, 5 September 2026 (T-014 … T-018).** These are engineering
defaults, not ZC's answer, and each is a property so that closing D-03 is a
configuration change:

| Bound | Property | Value | On breach |
|---|---|---|---|
| Body size | `stats.input.limits.max-request-bytes` | 256 KB | 413 `payload_too_large`, `requests_too_large` |
| Ingest rate, **per instance** | `stats.input.limits.max-requests-per-second` | 4,000 | 429 `too_many_requests`, `requests_rate_limited` |
| Confirm wait | `stats.amqp.confirm-timeout` | 5 s | 503, `records_publish_failed{type}` |
| Read timeout | `server.tomcat.connection-timeout` | 10 s | Connection closed by the container |

**An unroutable message is a 503, not a 200.** Input publishes with `mandatory=true`;
a message the broker acks and no queue is bound to take is a *failed* acceptance —
`records_unroutable{type}` and 503, so Heimdal resends (ADR-002 §9, DL-118).

**The rate limit is per instance**, so the cluster ceiling is the configured value
times the replica count. It is the only replay control the Stats App owns: Heimdal
agreed the 2x cap on 4 Sep 2026 (Q13c, DL-109) and an agreement is a commitment, not
a control.

## 2a. Record shape — as production actually sends it

Re-derived 29 August 2026 from 15 real records (ADR-007). **The names in SRS v1.0 were wrong**; these are authoritative.

```json
{
  "uid": "CLSCON-11d3ec4c-f6e1-403a-a9ef-b6f5fe909eee",
  "tenant": "mzc",
  "maintenant": "mzc",
  "clsbox": "04B64810652B",
  "type": "lorastats",
  "version": 1,
  "ts": {
    "server":    1787833739,
    "datastore": 1787833739,
    "converter": 1787833739,
    "device":    1787833739,
    "expiresOn": "2026-08-27T12:28:59.074Z"
  },
  "data": { },
  "status": ["conversion ok"]
}
```

| Member | Required | Read? | Note |
|---|---|---|---|
| `uid` | yes | yes | `CLSCON-`-prefixed in production. No format enforced — ZC guarantees uniqueness, not a shape |
| `tenant` | yes | yes | **The isolation field** |
| `maintenant` | yes | no | Stored and returned. Never used for isolation. Equal to `tenant` on every tenant but minol (DL-146) |
| `clsbox` | yes | yes | 12 hex characters. **Called `gateway` in SRS v1.0** — a name production has never used |
| `type` | yes | yes | Closed enumeration of five |
| `version` | no | no | **Not a payload discriminator.** All three `lorastats` dialects carry `1` |
| `ts.server` | yes | yes | **Canonical arrival clock.** Called `ts.processor` in SRS v1.0 |
| `ts.device` | yes | yes | Retention and `from`/`to` filtering key on this |
| `ts.datastore` | no | no | Legacy Mongo write clock |
| `ts.converter` | no | no | **On 100% of production records.** Metered as `records_converter_ts_present{type}` |
| `ts.expiresOn` | no | no | ISO-8601 instant as a **string** or as `{"$date": "…"}` — both accepted, never epoch seconds. Legacy Mongo TTL key (Q15e) |
| `ts.deviceOrig` | no | no | The gateway's own clock, present when Heimdal has rewritten `ts.device` (DL-146, Q15f) |
| `meta` | no | no | Heimdal annotations, e.g. `{"tsDeviceCorrected": true}`. Preserved verbatim |
| `data` | yes | at the Writer | Validated by the per-type schema — see §2 |
| `status` | no | no | e.g. `["conversion ok"]`. Upstream converter annotation |

Unknown members beyond these are accepted and preserved under D14.

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

**Q8a-3, added 12 September 2026 (DL-139), to be asked together with Q8a-2:** the Datahub API guide describes Heimdal as the platform's *forwarding* service, delivering to a **ForwardRule** — `{url, type, id, maxRetry}`, transports `AMQP | HTTP | AMQP+ELEMENT | MSSQL | OCHP | MQTT`, with *credentials in the URL*. If the stats push is a ForwardRule, the inbound auth is HTTP **basic** — a mode the property above does not offer — and `maxRetry` means Heimdal's retry on a non-2xx is **bounded**, not the indefinite retry §5 assumes. The guide documents sensor forwarding; whether gateway statistics use the same path is the question.

Credentials, tokens, private keys, and secrets are never stored in the repository or logged.

## 4. Validation contract

The message must satisfy the common envelope and the schema selected by `type`. The current V1 types are `lorastats`, `ltestats`, `sysstats`, `omsstats`, and `netstats`. All five schemas in `docs/schemas/` are strict and normative; `netstats` was finalised by Q4a on 18 August 2026 (`eth0`/`wwan` mandatory, other interfaces allowed, `apipa` optional, counters are digit-only strings).

**Clock skew — decided 29 August 2026 (ADR-003 D9, closes Q14a).** Input rejects a record whose `ts.device` exceeds `ts.server` by more than a configured tolerance, and accepts anything inside it:

```properties
stats.input.validation.max-clock-skew=PT5M   # starting value; externalised, never hard-coded
```

The response is 400 with a `ts.device` field-level error carrying the reason code `clock_skew_exceeded`, and the rejection is metered as `records_clock_skew_rejected{type}`. The tolerance exists because a few seconds of NTP drift is normal and must not be an outage. This is a **cross-field** check comparing two instance locations, which JSON Schema 2020-12 cannot express, so it lives in Input's code rather than in `envelope-v1.schema.json` — the five `edge/<type>-clock-skew-future.json` fixtures stay schema-valid and are marked `$expect: "input-reject"` instead.

Invalid responses use the approved RFC 9457-compatible Problem Details shape. The response may identify field names, reason codes, stat type, and `uid`, but must not echo sensitive offending values or access credentials. The business answer permits payload logging in the Java logger/Docker logs **at `DEBUG` only, which is off in production** — never at `INFO` or above, on any path (DL-102; `claude/DEVELOPMENT.md` §7 is authoritative). Production data-classification and access policy still governs what is retained and who can see it, and neither is decided: see **D-12** and the field-level catalogue in `docs/decisions/DATA-SENSITIVITY-INVENTORY.md`.

## 5. Delivery/replay behavior

The Input Module is stateless and does not buffer locally. If RabbitMQ cannot accept/confirm the message, Input returns non-2xx and Heimdal owns dead-letter/retry handling. **How many times Heimdal retries is not known** — if the push is a ForwardRule its `maxRetry` bounds it (Q8a-3, DL-139), in which case a sustained 503 exhausts a budget on Heimdal's side that this contract cannot see.

PostgreSQL retains a uniqueness defence even though Heimdal states duplicates are not intentionally delivered: `PRIMARY KEY (ts_device, uid)` scoped per partition, with `ON CONFLICT DO NOTHING` and a `records_duplicate_suppressed{type}` counter (ADR-003, ADR-005). A replayed record carries its original `ts_device`, so it lands in the same partition and is absorbed.

**Replay rate — agreed by Heimdal on 4 September 2026 (Q13c, DL-109).** A 6-hour Heimdal outage is roughly 28.8M messages. Heimdal caps replay at **2× nominal (2,667/s)**, which bounds catch-up to about 3 hours while normal ingest continues, holding total ingest near 4,000/s. We cannot enforce this — an agreement is a commitment, not a control — so nothing is relaxed: the per-instance 429 limit, bounded Writer prefetch and 503 backpressure all stay, and a 503 under replay is the channel working as designed, not an incident. A replayed `uid` is absorbed by the primary key below.

## 5a. What Input observes on acceptance

Beyond the per-type counters in §2 (`records_accepted`, `records_validation_failed`,
`records_clock_skew_rejected`, `records_converter_ts_present`, `records_publish_failed`,
`records_unroutable`), two observations are metered **on acceptance** — after the publisher
confirm, so a record Heimdal resends after a 503 is counted once (T-050, DL-147).

**Since DL-151 every Input series except `records_unroutable` also carries
`source="internal"|"external"`** — which door the request used — and the two untyped
request counters (`requests_too_large`, `requests_rate_limited`) carry it too. Sum over
`source` for the totals a dashboard had before the split; `records_unroutable` is a fact
about the broker's bindings and is incremented where the door is not known. A request the
logging filter never saw is labelled `unknown`, never left unlabelled.

The histogram below carries the same label:

| Metric | Meaning |
|---|---|
| `records_ts_server_cycle_offset_seconds{type,source}` | `ts.server mod 600` per accepted record, as a histogram with twenty cumulative 30-second buckets. The Q13b measurement (DL-104): jitter fills the twenty slots evenly, a synchronised fleet fills one or two. Hardware is not sized down until 24 h of this say jitter. Query in `ops/prometheus/README.md` |
| `records_ts_device_corrected{type}` | Records carrying `meta.tsDeviceCorrected: true` — Heimdal rewrote `ts.device` to `ts.server` (DL-146). Read to be counted and for nothing else; the number to put beside Q15f |

Both live in Input rather than the Writer because Input sees every type from the first
day, while the Writer is deployed per type (ADR-003 D8). Neither is alerted on.

Every published message also carries the publish time as the AMQP `timestamp` property
(`amqp-topology-v1.md` §1), which is how the Writer measures the age of the oldest waiting
record. Nothing about the body changes.

## 6. Contract tests

The integration suite must verify valid and invalid samples for each type, response status and body, no publish on 400/auth failure, publisher-confirm timing, broker outage returning non-2xx, same `uid` correlation, and safe handling of a replay/redelivery.
