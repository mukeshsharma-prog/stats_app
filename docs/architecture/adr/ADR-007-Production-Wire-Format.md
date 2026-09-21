# ADR-007 — The production wire format is the contract

**Status:** `ACCEPTED` — **§4 amended 4 September 2026**; **§6 and the envelope amended 18 September 2026** (see the two *Amendment* sections below)
**Date:** 29 August 2026
**Decision owner:** Naman Vashishtha (engineering), on production data supplied by Hasan Dogan
**Requirements:** STA-01A, STA-02, STA-03A, STA-14A, STA-21A, STA-23B
**Supersedes:** the envelope field names in SRS v1.0 and every document derived from them
**Amends:** ADR-004 (§2 below), ADR-003 (column names)
**Amended by:** the *Amendment — 4 September 2026* section below, which answers Q15a–Q15d and **reverses §4**; and the *Amendment — 18 September 2026* section, which extends §6 to `ltestats` and widens the envelope on live data (DL-146). §1 carries a one-paragraph application to URL path parameters (21 Sep 2026, DL-151)

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

*Applied 21 September 2026 (DL-151):* the same rule governs **URL path parameters**.
Management asked for a read path "by MAC address"; the contract's parameter is
`{clsbox}` — `/v1/stats/{type}/{clsbox}/{from}[/{to}]` — not `{mac}`. The URL bytes are
the same either way, and a second name for the device field in the API is the rename
layer this section removed. "MAC address" survives in the operation's description, as
*gateway* does in prose.

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

> **AMENDED 4 September 2026 — this section is reversed.** Q15a was answered:
> *"make it work for all the known dialects."* All three are now accepted and
> normalised to one typed row, under four rules that keep the enumeration closed.
> See **Amendment — 4 September 2026 §A1** and **DL-105**. Two findings below
> survive unchanged and are still binding: `version` is never a parser
> discriminator, and a union that is allowed to grow silently is a contract nobody
> understands. The text below is kept as the record of what was decided on
> 29 August and why.

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
* **Added 18 September 2026 (Amendment B1):** `ltestats` `rsrp`/`rsrq` become
  **optional** — a modem on 2G reports neither — and a modem that never registered
  sends `{"etxt": "unknown technology"}` alone, which is a second, closed shape.
  Same rule, same reasoning as the `netstats` `wwan` line above.

## Consequences

**What this costs.** 102 files changed: the whole schema catalogue, all 66 fixtures
plus 9 new production-derived ones, the OpenAPI contract, the Java envelope model,
and ADR-003's DDL. It is affordable only because `stats-input`, `stats-writer` and
`stats-api` are still empty application shells — this is a schema-and-docs change,
not a rewrite. Had it been found after Phase 2, it would have been a migration.

**The DLQ volume problem — resolved 4 Sep 2026 by the Amendment.** The paragraph
below described the cost of §4 as it stood on 29 August. With all three dialects
accepted, the ~15M messages/day stops; the paragraph is retained because it is why
Q15a was escalated as urgent rather than routine, and because DLQ depth alerting
(T-060) is still owed for the ordinary reasons.

**§4 as originally decided.** §4 dead-letters an
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

## Open questions this raises — **all four answered 4 September 2026**

| Ref | Question | Answer (4 Sep 2026) | Decision |
|---|---|---|---|
| Q15a | **Which `lorastats` dialect is canonical**, and are the other two being retired? | *"Make it work for all the known dialects."* All three are legitimate production traffic. **The second half — retirement — is still unanswered**, and is carried as a residual: it decides only whether a parser can ever be deleted. | **DL-105**, §A1 below |
| Q15b | Is `ts.device` genuinely device-assigned? | **Yes** — device-assigned, and sent to Heimdal as soon as the device records it. The 14-of-15 match with `ts.server` is healthy gateways sending immediately, not an arrival stamp. | **DL-106** |
| Q15c | Does Heimdal POST `_id`? | **No.** Heimdal stores in its own MongoDB and the sample was dumped straight out of it. `_id` is an export artifact and never reaches the wire. | **DL-107** |
| Q15d | Is `status` ever anything other than `["conversion ok"]`? | **Yes — it can report a failed conversion.** | **DL-108** |

## Amendment — 4 September 2026

**Status:** `ACCEPTED` · **Approver:** Naman Vashishtha · **Source:** ZC / Hasan Dogan
**Amends:** §4 (reversed), the Consequences DLQ-volume paragraph (resolved)

### A1. All three known `lorastats` dialects are accepted and normalised to one row

§4's assumption is replaced by the answer. The schema accepts all three:

| Dialect | Field names | Value types | Example |
|---|---|---|---|
| **A** | bare | integer | `"rx_nr_pkts": 176` |
| **B** | bare | **string** | `"rx_nr_pkts": "176"` |
| **C** | **`lora_`-prefixed** | integer | `"lora_rx_nr_pkts": 176` |

Four rules bound the widening. Each one exists because of something §4 got right:

1. **Detection is structural, never `version`.** All three carry `version: 1`.
2. **All three normalise to the same typed columns** — one stored shape, one API
   shape. The dialect is a **metric label**, `records_lorastats_dialect{dialect}`,
   never a column: it answers *"how much of the fleet is on C?"* without pushing a
   three-way branch onto every consumer.
3. **String coercion is explicit and range-checked** against the signed `BIGINT`
   maximum *before* conversion — the rule Q4a set for `netstats` counters. A value
   that does not parse, or that overflows, **dead-letters**; it is never silently
   zeroed, truncated or defaulted.
4. **An unknown fourth dialect still dead-letters.** *"All the known dialects"* is
   exactly three. The schema is a **closed enumeration**, not an open union.

**Why the reversal is not the thing §4 warned against.** §4's Consequences say *"do
not resolve this by quietly widening the schema."* The operative word is *quietly*.
§4 rejected a union **while the dialects were unexplained** — accepting them then
would have ratified a contract nobody understood, and the union would only have
grown. Q15a explains them: three shapes are in the field simultaneously and none is
being retired on any timeline we have been given. Rules 1 and 4 keep the unbounded
half of the original objection impossible.

**What it cost, and what it stopped costing. BUILT 8 September 2026 — T-063,
DL-127.** `data` became a closed `oneOf` of three branches;
`lorastats-dialect-prefixed.json` and `lorastats-dialect-string-values.json` moved
into `valid/`; `invalid/lorastats-dialect-unknown.json` was added and must still
dead-letter — that fixture is the test that proves the enumeration is closed; and
`valid/lorastats-dialect-string-overflow.json` was added for rule 3, carrying the
new `writer-range-reject` marker because the record is schema-**valid** and it is
the Writer's `BIGINT` check that refuses it. What stopped is ~15M messages/day
accumulating in a DLQ that ADR-002 §12 forbids ever expiring.

**Two implementation facts worth carrying out of it.** Dialect C is **not**
"everything prefixed" — the production record mixes fifteen `lora_`-prefixed
counters with a bare `fsk_received` and a bare `timespan`, so a branch written from
the description above rejects the record it describes. And normalisation runs
**before** the column mapping, so `data_extra` is the residue of the canonical
payload; taken from the wire shape it would swallow every prefixed counter and leave
the typed columns null (DL-127).

### A2. Consequences of the other three answers

* **Q15b (DL-106).** Retention on `ts.device` is retention on *measurement* time.
  Every path that rested on it is real, not theoretical: the offline-backfill trap,
  the late-arrival policy (**D5b, signed 4 Sep 2026 — DL-112**), the redrive-age
  trap, and the clock-skew tolerance of ADR-003 D9.
* **Q15c (DL-107).** No `_id` handling is built. D14 is unchanged and remains the
  safety net. Fixtures derived from the 29 Aug dump must have `_id` stripped, or
  they test the exporter rather than the wire. **Heimdal's store being MongoDB is
  not permission to reintroduce Mongo here.**
* **Q15d (DL-108).** A `status` other than exactly `["conversion ok"]` is
  **stored**, **metered** as `records_upstream_status_not_ok{type}` and **logged at
  `WARN`** — never dead-lettered, because the record is valid and correctly
  delivered and a redrive would repeat forever. The comparison is value-agnostic:
  we do not have the vocabulary of failure strings, and a check that recognises
  only known values goes quiet on the ones it does not. T-064. **Built 8 September
  2026 — DL-128**, which also settles what this rule left open: an **absent**
  `status` is *not* counted, because "upstream did not say" and "upstream said it
  failed" are different facts and `status` is optional on every fixture written
  before 29 August.

### A3. What this amendment does not change

`clsbox`/`ts.server` end to end with no rename layer (§1); `ts.converter` accepted,
preserved and metered (§2, amending ADR-004); `ts.server` as the arrival clock
(§3); **the two validation gates and their asymmetric consequences (§5)**; and the
required-field corrections of §6.

## Amendment — 18 September 2026

**Trigger.** Hasan pointed engineering at the live `datastore-stats` aggregate
endpoint on 18 September 2026 — the first look at production beyond the fifteen
records of 29 August. 300 records per type plus one device's full 36-day history were
run through the same validators CI uses. Every `lorastats`, `sysstats` and `omsstats`
record passed both gates. Six findings did not fit the documents; the decision record
is **DL-146**. Nothing in §1–§5 or §A1–§A3 changes.

### B1. `ltestats` is a closed enumeration of two shapes — §6 extended

`data` is a `oneOf` of **`measured`** and **`unknown-technology`**:

* *measured*: the shape of 29 August with **`rsrp` and `rsrq` optional**. Both are
  LTE reference-signal measurements; a modem that has fallen back to **2G** reports
  `rssi`/`plmn`/`imsi`/`operator`/`lac`/`cid` and nothing else (5 of 300 live
  records), and every such record dead-lettered.
* *unknown-technology*: `{"etxt": "unknown technology"}` and no measurement — a
  modem that never registered (1 of 300). `additionalProperties: false`, so `etxt`
  beside a partial measurement matches **neither** shape and dead-letters: half a
  measurement is not a known shape. The enumeration is closed at two for the reason
  §A1 closed `lorastats` at three.

This is §6's second rule applied where it was missed: the field is absent because
the thing it measures is not there. A DLQ full of degraded modems is a DLQ full of
the records ops most needs, in a queue that never expires.

### B2. The envelope has two more members, and one member has two spellings

* **`meta.tsDeviceCorrected: true` and `ts.deviceOrig`.** Heimdal *rewrites*
  `ts.device` on some records — `ts.device` is set equal to `ts.server` and the clock
  the gateway sent is kept as `ts.deviceOrig` (5–12 s behind, on the records seen).
  Both are **passthrough members**: accepted, preserved verbatim in the `passthrough`
  column, never read. The consequence for STA-23B and DL-106 is that `ts.device` is
  *not always* device-assigned — retention and the D9 skew check see Heimdal's
  corrected value. When Heimdal applies the correction is **open (Q15f)**; other live
  records carry uncorrected skews of +3 s and −1 s.
* **`ts.expiresOn` is accepted as a string *or* as `{"$date": "<ISO-8601>"}`.** The
  29 August sample carried the string; the live datastore returns MongoDB Extended
  JSON. Which one the HTTP forward emits is **open (Q15e)**. The schema, the Java model
  (`JsonNode`) and the `passthrough` column all keep whichever form arrived, because
  the value is never read and a wrong guess would have cost 100% of ingest for
  nothing. An epoch integer is still invalid. **This is not a Mongo dependency** — it
  is a JSON object with one string member, and no Mongo type or library is involved.

### B3. What this amendment observes but does not decide

* **`maintenant ≠ tenant` on roughly one live record in ten — on the minol tenant
  only.** DL-139's "equal in production" was true of every tenant but that one
  (`tenant: minol-gateway`, `maintenant: minol`). §1's isolation field is unchanged;
  DL-146 corrects the trap's wording.
* **No `netstats` record exists in the datastore.** `type: "netstats"` returned
  nothing across the retained window. The schema, fixtures and queue stay; where
  those records go is a question for ZC (Q15g).
* **`lorastats` dialect B may carry `util_restarted` as an integer among strings, and
  omit `rx_type_arol`.** Valid today: detection reads only the required counters
  and the extra member lands in `data_extra`. Recorded so the counter is read as
  "Heimdal added a field", not as a defect.
* **One device's `ltestats` cadence is every ~29 minutes, not 10.** One data point,
  not a capacity decision; T-050's first-24-hours histogram is where it becomes one.

## Verification

As at 29 August 2026, when this ADR was accepted:

```
docs/schemas/validate-fixtures.py    88 assertions checked, 0 failed
./gradlew build                      195 tests, 0 failures
```

All 15 production records passed the Input gate. 13 of 15 passed the Writer gate;
the 2 that did not were the lorastats dialects of §4, checked in as fixtures
carrying `$expect: "writer-dlq"`.

Re-established after Amendment §A1 was built, 8 September 2026 (T-057, T-063, T-064):

```
docs/schemas/validate-fixtures.py    91 assertions checked, 0 failed
./gradlew build                      480 tests, 0 failures
```

**All 15 production records now pass both gates.** The two that used to fail are the
dialect fixtures, now in `valid/`, and their counters reach the same typed columns as
the canonical dialect — which is the assertion `allThreeDialectsLandAsOneIdenticalRow`
makes against a real PostgreSQL partition rather than against the schema.

Re-established after the 18 September 2026 amendment (DL-146):

```
docs/schemas/validate-fixtures.py    103 assertions checked, 0 failed
docs/contracts/check_schema_drift.py 5 per-type data schemas agree
./gradlew build                      593 tests, 0 failures (90 Java parity cases)
```

**1,200 live records — 300 per type for the four types that exist — pass both gates**,
including the six ltestats records the previous schema dead-lettered.
