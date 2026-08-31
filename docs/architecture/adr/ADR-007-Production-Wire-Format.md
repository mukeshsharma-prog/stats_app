# ADR-007 — The production wire format is the contract

**Status:** `ACCEPTED`
**Date:** 29 August 2026
**Decision owner:** Naman Vashishtha (engineering), on production data supplied by Hasan Dogan
**Requirements:** STA-01A, STA-02, STA-03A, STA-14A, STA-21A, STA-23B
**Supersedes:** the envelope field names in SRS v1.0 and every document derived from them
**Amends:** ADR-004 (§2 below), ADR-003 (column names)

> **Numbering warning (G5).** `Datahub_Core_StatsApp_ARD_v1_1.pdf` carries its own
> ADR-007. Cite the ARD's as **`ARD-ADR-07`**. An unqualified "ADR-007" always means
> this file.

## Context

On 29 August 2026 Hasan supplied 15 real production records — three of each stat
type. Validated against the schema catalogue as it then stood:

```
AS SENT                                              0 / 15 records validate
BEST-CASE MAPPED (renaming fields by hand)           9 / 15 records validate
```

Nothing in the repository matched production. Every field name in the envelope came
from SRS v1.0 and **no production record has ever used any of them.** The schema
catalogue, the 66 fixtures, the OpenAPI contract, the Java model, and ADR-003's DDL
had all been derived, consistently and carefully, from a document that does not
describe the system.

The records are a `mongo` shell dump — they carry `_id`, `ts.datastore`,
`ts.expiresOn` and `status`, which are storage artifacts of the legacy datastore.
That left a genuine fork: is this the **wire format Heimdal will POST to us**, or
the **legacy store's internal shape**? The two readings imply very different work.
**The question was put to the decision owner and answered: reading (A) — this is
the wire format.** This ADR builds on that answer. If it is ever shown to be
wrong, this ADR is the single place to reverse.

## Decision

### 1. Production names win, end to end — no translation layer

The device identifier is **`clsbox`**, not `gateway`. The arrival clock is
**`ts.server`**, not `ts.processor`. These names are used identically on the wire,
in the JSON Schema catalogue, in the Java model, in the PostgreSQL column, and in
API responses.

*Rejected:* renaming at the Input boundary and keeping the SRS vocabulary
downstream. It produced a smaller diff, and it was rejected for two specific
reasons. First, ADR-004 requires the published message to be byte-identical to what
was received, and ADR-003's per-partition duplicate suppression depends on that —
a rename breaks it. Second, a field-rename layer sitting directly beside
`tenant`/`maintenant` is precisely the place `claude/CLAUDE.md` §6 identifies as
where a field gets silently swapped, and that failure passes every test written
against sample data where the two values are equal.

The word *gateway* survives in prose for the physical device — "160,000 gateways",
"a gateway with a bad clock". Only the **field** is renamed. Do not mechanically
rewrite the prose.

### 2. `ts.converter` is accepted, ignored, and metered — ADR-004 is amended

ADR-004 declared that "a record carrying `ts.converter` is invalid", and the
envelope schema enforced it with `not: {required: [converter]}`. **Production sends
`ts.converter` on 100% of records.** That rule rejected every record Heimdal sends.

The rejection clause is removed. `ts.converter` is accepted, stored, returned
verbatim, and never read. Presence is metered as
`records_converter_ts_present{type}` so we can see if Heimdal ever stops sending it
— read it from `Timestamps.converter()` being non-null.

**ADR-004's actual decision is unchanged and still binding: the Stats App performs
no timestamp normalisation.** What was wrong was never the decision, only the
enforcement clause bolted onto it. The distinction matters — the rule was written
to prevent a normalisation step, and it was implemented as a rejection of evidence
that one had happened upstream.

`ts.datastore` and `ts.expiresOn` are likewise accepted, preserved, and never read.
`ts.expiresOn` is typed as a **string**, not epoch seconds: production sends an
ISO-8601 instant there, and it is the legacy Mongo TTL key, not an expiry we honour.

### 3. `ts.server` is the canonical arrival clock

`ts.server`, `ts.datastore` and `ts.converter` are all present and identical in the
sample. `ts.server` is canonical: it is Heimdal's own receipt clock, the direct
analogue of what the SRS called `ts.processor`, and the only one of the three that
still means anything once the legacy Mongo store is retired.

Consequences: clock skew (ADR-003 D9, Q14a) compares `ts.device` against
`ts.server`. The API exposes `ts.server` as the processing time. **Retention still
keys on `ts.device`** (STA-23B) — unchanged.

### 4. One lorastats dialect is encoded; the others dead-letter

Three consecutive production `lorastats` records arrived in three mutually
incompatible dialects:

| | record 0 | record 1 | record 2 |
|---|---|---|---|
| field names | `rx_nr_pkts` | **`lora_rx_nr_pkts`** | `rx_nr_pkts` |
| value types | **strings** `"176"` | integers | integers |
| extras | `rx_type_arol` | `fsk_received`, `timespan` | `fsk_received`, `rx_short_pkt`, `timespan` |

**All three carry `version: 1`.** Nothing on the record discriminates them, so
`version` must never be used to select a parser.

The schema encodes **one** dialect — bare names, integer values — chosen because it
matches `omsstats`, which shares a converter family. The other two are
**dead-lettered**, not absorbed into a permissive union.

*Rejected:* a permissive union accepting every observed spelling and both value
types. It would have made all 15 records pass, and that is exactly the problem: it
silently ratifies a contract we do not understand, and the union only ever grows.
Dead-lettering makes the disagreement visible and recoverable.

**This is an assumption, not an answer.** It is the most urgent open question for
ZC (§6).

### 5. Two validation gates, with different consequences

| Gate | Schema | Module | Failure |
|---|---|---|---|
| Input | envelope only | `stats-input` | **400**, nothing published (STA-03A) |
| Writer | envelope + per-type payload | `stats-writer` | **dead-letter**, after publisher confirm |

`StatsSchemaValidator.validateEnvelope()` is the first; `validate()` is the second.
Calling the wrong one moves a failure across that boundary, and the direction that
hurts is validating the payload at Input: Heimdal retries only on a non-2xx, so a
400 for a dialect disagreement is **unrecoverable data loss**, while a dead-letter
is a redrive.

The corollary is that "invalid" no longer implies "400". The fixture gate carries a
`$expect: "writer-dlq"` marker for exactly this case, and asserts that such a
fixture *passes* the envelope.

### 6. Where a required field was wrong, it is corrected, not dead-lettered

A line has to be drawn, because §4 and this section point in opposite directions.
The distinction is:

* **Two incompatible encodings of the same thing** → encode one, dead-letter the
  rest. The lorastats dialects.
* **A field legitimately absent because of device state** → make it optional. This
  is not widening the contract; it is correcting a requirement that was wrong.

Under the second rule:

* `netstats` interfaces now require **only `tx`/`rx`**. Production sends a `wwan`
  that never came up — `need_init: "qmi"`, a `fail_count`, and no address at all.
  Requiring `gw`/`ip`/`mask`/`dns` dead-lettered exactly the record ops most needs
  to see. Addresses that *are* present must still be well-formed IPv4.
* `sysstats` gains optional `temp`, `rtt`, `wan_if`, `rebootcause`, `timespan` — a
  gateway with no temperature sensor omits them.
* `ltestats` numerics become **strings** and the field becomes
  **`access_Technology`** (capital T). Each of these alone dead-lettered 100% of
  ltestats traffic. This is a correction of a transcription error, not a widening.

## Consequences

**What this costs.** 102 files changed: the whole schema catalogue, all 66 fixtures
plus 9 new production-derived ones, the OpenAPI contract, the Java envelope model,
and ADR-003's DDL. It is affordable only because `stats-input`, `stats-writer` and
`stats-api` are still empty application shells — this is a schema-and-docs change,
not a rewrite. Had it been found after Phase 2, it would have been a migration.

**The DLQ volume problem — unresolved and operational.** §4 dead-letters an
estimated two thirds of `lorastats` traffic, and ADR-002 §12 says **the DLQ has no
TTL, ever.** At 115.2M records/day that is roughly 15M messages/day accumulating
with no expiry. The no-TTL rule is right — a TTL'd DLQ is silent data loss — but it
was written for a DLQ receiving exceptional traffic, not a third of a stat type.
Two things follow: the ZC dialect question is **urgent, not routine**, and DLQ depth
alerting must be in place before Phase 2 ships. Do not resolve this by quietly
widening the schema; that is the decision §4 already rejected.

**`version` is not a discriminator.** Anything that branches on it is wrong.

**The typed view must stay lossless.** `StatsEnvelope`/`Timestamps` model every
production member including `expiresOn`, with `@JsonInclude(NON_NULL)` so absent
members do not serialise as null. A lossy view would break ADR-004's byte-identical
publication without failing anything.

## Open questions this raises

| Ref | Question | Owner | Blocks |
|---|---|---|---|
| Q15a | **Which `lorastats` dialect is canonical**, and are the other two being retired? Three dialects in three consecutive records is either a migration in flight or three firmware generations in the field. | ZC / Hasan | Nothing today — the schema assumes one. But it dead-letters ~2/3 of lorastats until answered. **Most urgent open item.** |
| Q15b | Is `ts.device` genuinely device-assigned? It equals `ts.server` in 14 of 15 records, differing by 1s in the fifteenth. If it is stamped on arrival, then retention-on-`ts.device` is retention-on-arrival, and the offline-gateway backfill trap — plus the whole late-arrival policy (D5b, T-026) — rests on an assumption production does not show. | ZC / Hasan | Not blocking. Would simplify D5b if answered. |
| Q15c | Does Heimdal POST `_id`? It is in the dump and is a Mongo artifact. The envelope accepts it under D14 either way. | ZC / Hasan | Nothing. |
| Q15d | Is `status` ever anything other than `["conversion ok"]`? If it can report a *failed* conversion, we are storing records the upstream already knows are bad. | ZC / Hasan | Nothing today. Would justify a metric. |

## Verification

```
docs/schemas/validate-fixtures.py    87 assertions checked, 0 failed
./gradlew build                      195 tests, 0 failures
```

All 15 production records pass the Input gate. 13 of 15 pass the Writer gate; the
2 that do not are the lorastats dialects of §4, and both are checked in as
fixtures carrying `$expect: "writer-dlq"`.
