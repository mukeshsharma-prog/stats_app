# Stats App Schema Catalogue and Fixture Standard

**Status:** `ACTIVE` — schemas are strict and CI-gated. **Re-derived from production 29 August 2026 (ADR-007).**  
**Purpose:** Provide machine-testable message contracts for Input validation, contract tests, Writer mapping, and API response references.

## 1. Required catalogue layout

```text
docs/schemas/
├── envelope-v1.schema.json
├── lorastats-v1.schema.json
├── ltestats-v1.schema.json
├── sysstats-v1.schema.json
├── omsstats-v1.schema.json
├── netstats-v1.schema.json              # strict since Q4a; interface required-set cut to tx/rx by ADR-007
├── validate-fixtures.py                 # CI gate: 103 assertions, exits non-zero on any mismatch
└── fixtures/
    ├── MANIFEST.md                      # what each fixture proves
    ├── valid/                           # must validate
    ├── invalid/                         # must fail validation
    ├── edge/                            # must validate, and still needs a decision
    └── malformed/                       # not schema-testable; exercises the parser layer
```

Run the gate from `docs/schemas/`:

```bash
python3 validate-fixtures.py     # 103 assertions checked, 0 failed
```

A schema document is an executable contract, not sample documentation.

**There are two gates, and they are not the same gate (ADR-007 §5).** The Input Module selects by `type` and validates the **envelope only**; a failure is a 400 and nothing is published (STA-03A). The Writer validates the **envelope and the per-type payload**; a failure there is a **dead-letter**, because the record has already been accepted, publisher-confirmed and published, and Heimdal retries only on a non-2xx. In Java these are `StatsSchemaValidator.validateEnvelope()` and `.validate()` respectively. Calling the payload gate at Input converts a recoverable dead-letter into unrecoverable data loss.

## 2. Common envelope rules

| Field | Rule |
|---|---|
| `uid` | Required non-empty string. UUID format may be enforced only if Heimdal guarantees it for all records. |
| `tenant` | Required non-empty string; canonical format/allowed character set pending tenant-policy approval. |
| `maintenant` | Required non-empty string; deliberately test a value different from `tenant`. |
| `clsbox` | Required 12 hexadecimal characters. Canonical upper/lower case must be selected and normalized/validated consistently. |
| `type` | Required literal enumeration; must match schema, routing key, queue, consumer, and PostgreSQL table. |
| `ts.server` | Required integer Unix epoch seconds. **The canonical arrival clock** — what the SRS called `ts.processor` (ADR-007 §3). |
| `ts.device` | Required integer Unix epoch seconds. Retention keys on this field. |
| `ts.datastore` | Optional integer Unix epoch seconds. Preserved, never read — the legacy Mongo write clock. |
| `ts.converter` | Optional integer Unix epoch seconds. Preserved, never read, metered as `records_converter_ts_present{type}`. **Present on 100% of production records** (ADR-007 §2). |
| `ts.expiresOn` | Optional ISO-8601 instant as a **string** *or* as `{"$date": "<string>"}` — both spellings have been seen (DL-146, Q15e); an integer is invalid. Preserved in whichever form arrived, never read — the legacy Mongo TTL key. |
| `meta`, `ts.deviceOrig` | Optional. Sent when Heimdal has **rewritten `ts.device`** to equal `ts.server`: `meta.tsDeviceCorrected: true` and the gateway's own clock in `ts.deviceOrig` (DL-146, Q15f). Accepted by `additionalProperties`, preserved in `passthrough`, never read. |
| `version` | Optional integer. Preserved, never read. **Not a payload discriminator** — all three `lorastats` dialects carry `version: 1`. |
| `status` | Optional array of strings, e.g. `["conversion ok"]`. Preserved, never interpreted. |
| `data` | Required object, validated by the selected schema **at the Writer**, not at Input. |

The unexpected-field policy is **D14**: accept and preserve unknown `data` fields, reject invalid envelope fields. It is a deliberate decision, and it must never be settled silently by a Jackson annotation or deserialiser default.

**The `ts.converter` asymmetry is gone.** Until 29 August 2026 `ts` tolerated unknown members but failed specifically on `ts.converter`, on the reasoning that its presence meant the sender believed in a normalisation step this system does not perform. Production sends it on every record, so that rule rejected 100% of ingest. There is now **no member of `ts` whose presence makes a record invalid** (ADR-007 §2). ADR-004's underlying decision — the Stats App performs no timestamp normalisation — is unchanged.

## 3. Type schemas — re-derived from production, 29 August 2026

> **The table below was originally derived from SRS v1.0 and was wrong in four of five types.** It is kept as the record of what changed. Production is the authority (ADR-007); where this table and a `*-v1.schema.json` disagree, the schema wins.
>
> | Type | Corrected against production |
> |---|---|
> | `lorastats` | Three incompatible dialects observed, and **all three are accepted and encoded** — DL-105 reversed ADR-007 §4 on 4 Sep 2026 and T-063 built it on 8 Sep (DL-127). `data` is a **closed `oneOf` of three branches**: bare/integer, bare/string, and `lora_`-prefixed/integer. Detection is structural, never `version`; an unknown fourth dialect dead-letters. **Read `valid/lorastats-dialect-prefixed.json` before touching the third branch** — it is not "everything prefixed": fifteen counters carry the prefix while `fsk_received` and `timespan` arrive bare. Optional `rx_short_pkt`, `fsk_received`, `rx_type_arol`, `timespan` added |
> | `ltestats` | **Every numeric is a string** on the wire, and the field is **`access_Technology`** with a capital T. Either error alone dead-lettered 100% of ltestats. **18 Sep 2026 (DL-146):** `data` is a **closed `oneOf` of two shapes** — `shapeMeasured`, with **`rsrp`/`rsrq` optional** because a 2G modem has neither, and `shapeUnknownTechnology`, `{"etxt": "unknown technology"}` alone from a modem that never registered. 2% of live traffic was dead-lettering. `etxt` beside a partial measurement matches neither shape and still dead-letters |
> | `sysstats` | Correct as written. Optional `temp`, `rtt`, `wan_if`, `rebootcause`, `timespan` (a **string** here) added |
> | `omsstats` | **Correct as written** — the only type the SRS got right |
> | `netstats` | Interface required-set cut from `gw`/`tx`/`rx`/`ip`/`dns`/`mask` to **`tx`/`rx` only**: a down modem reports no address. Optional `driver`, `mtu`, `fail_count`, `plmn`, `plmn_list`, `need_init`, `last_forced_reset`, `last_powercycle` added |

| Type | Required `data` fields |
|---|---|
| `sysstats` | `memfree` integer, `uptime` number, `idletime` number, `load1` number, `load5` number, `load15` number, `runable` string. |
| `lorastats` | `rx_nr_pkts`, `rx_crc_missing`, `rx_crc_bad`, `tx_success`, `tx_failed`, `tx_peek`, `tx_peek_index_zero`, `tx_override`, `tx_busy`, `tx_dequeue_error`, `tx_type_beacon`, `chip_comm_error`, `util_fopen_failed`—all integers. |
| `ltestats` | *measured:* `rssi`, `access_Technology`, `operator`, `plmn`, `imsi`, `lac`, `cid` — all **strings**; `rsrp`, `rsrq`, `timespan` optional strings. *unknown-technology:* `etxt` string, nothing else. |
| `omsstats` | `rx_nr_pkts` integer, `rx_valid_nr_pkts` integer, `rx_crc_bad` integer, `device_count` integer, `timespan` integer. OMS timestamp handling follows ADR-004. |
| `netstats` | **Strict since Q4a (18 Aug 2026).** `eth0` and `wwan` mandatory; additional interfaces allowed via `patternProperties`; `apipa` optional; `gw`/`ip`/`mask`/`dns[]` IPv4 dotted-quad; `tx`/`rx` digit-only strings → `BIGINT`; `timespan` integer seconds ≥ 0. **The Writer must range-check counters** against the signed `BIGINT` maximum. |

## 4. Netstats — answered, with one residual

Q4a was answered on 18 August 2026 and `netstats-v1.schema.json` is strict. The residual is not a schema question: the counter pattern caps at 19 digits (`9999999999999999999`), while signed `BIGINT` stops at `9223372036854775807`. **That gap is not expressible as a regex, so the Writer MUST range-check before conversion** — an unchecked overflow corrupts the value with no error anywhere, the failure mode with no observable behaviour. Fixture: `fixtures/invalid/netstats-counter-overflow.json`.

Still open and narrow: **IPv6 (Q4b)**. The schema accepts IPv4 only, because IPv6 does not appear in the sample. `fixtures/invalid/netstats-ipv6-address.json` asserts rejection today; if ZC confirms IPv6 gateways exist, that fixture flips to `valid/` and the `ipv4` `$def` widens. One schema change, no code change.

## 5. Constraints still requiring approval

The SRS specifies field types and mandatory fields but usually not semantic ranges or maximum string lengths. Add a requirement or documented policy for each question below before enforcing it as a rejection condition.

| Topic | Decision required |
|---|---|
| Timestamp plausibility | **Closed.** `ts.server` and `ts.device` are bounded to 2000-01-01 … 2100-01-01, which is what makes a millisecond timestamp a 400. **Q14a was answered on 29 Aug 2026 (ADR-003 D9): a `ts.device` more than `stats.input.validation.max-clock-skew` (default 5 min) ahead of `ts.server` is rejected at Input with 400 `clock_skew_exceeded`.** That comparison is cross-field, so it is a code check in Input, not a schema rule — JSON Schema 2020-12 cannot compare two instance locations. |
| Counter values | Whether negative values are invalid and whether counter reset/overflow is possible. |
| Gateway casing | Canonical casing and whether mixed case is normalized or rejected. |
| IMSI protection | Validation format, redaction/masking in fixtures/logs/errors, storage encryption requirement. |
| `runable` field | Exact regular expression and whether spelling is intentionally legacy. |
| String lengths | Maximum size for tenant, operator, cell IDs, etc. |
| Additional fields | Strict rejection, preservation, or schema-version-based acceptance. |
| Type/schema version | Header/property/field convention and compatibility rollout. |

## 6. The fixture set — built, not aspirational

85 files — built 18 August 2026 in answer to Q11a, rebuilt on the production wire
format 29 August 2026 (ADR-007), extended by T-063/T-064 on 8 September and by the
first look at live data on 18 September 2026 (DL-146). `fixtures/MANIFEST.md` records
what each one proves; the names below are the real ones.

| Fixture | What it proves |
|---|---|
| `valid/<type>-valid.json` | Every mandatory field in the canonical shape |
| `valid/<type>-boundary.json` | The edges of each numeric range — for `lorastats`, `tx_success` at the signed 64-bit maximum, which a 32-bit column wraps silently |
| `valid/<type>-tenant-isolation-pair.json` | **The highest-value fixture in the set.** Two records on one gateway, with `record_b.maintenant` deliberately set to `tenant-alpha`. Filter on `maintenant` and tenant-alpha sees both records — a leak. Filter on `tenant` and it sees exactly one. |
| `invalid/<type>-missing-required.json`, `-wrong-type.json` | 400, and nothing published |
| `invalid/envelope-unknown-type.json` | An unrecognised `type` is rejected before any table name is derived |
| `invalid/envelope-ts-in-milliseconds.json` | A millisecond timestamp is a *valid integer*. Nothing fails loudly; the record lands ~53,000 years in the future where retention never reaches it. The envelope bounds `ts` to 2000–2100, which is what turns this into a 400. |
| `valid/omsstats-ts-converter-present.json` | **Moved from `invalid/` on 29 Aug 2026 (ADR-007 §2).** `ts.converter` is accepted, not rejected — production sends it on 100% of records. Now carries the full production `ts` block plus `version` and `status`, making it the envelope regression guard |
| `invalid/netstats-counter-overflow.json` | 20 digits. **The Writer must range-check** — the schema cannot express the gap between 19 digits and the signed `BIGINT` maximum. |
| `edge/<type>-late-arrival.json`, `-retention-boundary-inside.json` | Retention keyed on `ts.device`, either side of the window |
| `edge/<type>-replay-duplicate.json` | `ON CONFLICT (ts_device, uid) DO NOTHING` absorbs it, and the suppression is metered |
| `edge/<type>-clock-skew-future.json` | **Answered 29 Aug 2026 (ADR-003 D9):** rejected at Input with 400 `clock_skew_exceeded`. The files stay in `edge/` and stay **schema-valid**, because the check is cross-field and unexpressible in JSON Schema; each now carries `$expect: "input-reject"` and `$expectCode: "clock_skew_exceeded"`, which `validate-fixtures.py` asserts. Before the decision, both oracles read every `edge/` fixture as expected-valid — so the open question already had *accept* baked into ten green assertions. |
| `valid/ltestats-production-2g-no-rsrp.json`, `-unknown-technology.json` | **DL-146.** The two live shapes the 29 Aug schema dead-lettered: a 2G modem with no `rsrp`/`rsrq`, and a never-registered modem sending only `etxt`. 2% of live `ltestats` |
| `invalid/ltestats-shape-mixed.json` | `etxt` beside a partial measurement — matches neither closed shape, `$expect: "writer-dlq"`. The `ltestats` counterpart of `lorastats-dialect-unknown.json` |
| `valid/sysstats-production-ts-device-corrected.json` | `meta.tsDeviceCorrected` and `ts.deviceOrig` — Heimdal rewrote `ts.device`. Must round-trip through the typed view losslessly |
| `valid/omsstats-expires-on-extended-json.json` | `ts.expiresOn` as `{"$date": …}`. Accepted, stored in that form |
| `valid/lorastats-dialect-string-util-restarted.json` | Dialect B with an **integer** `util_restarted` among strings and no `rx_type_arol` — valid, extra member lands in `data_extra` |
| `malformed/*` | Eight non-schema inputs — the parser layer, before validation runs |

Three of these found real schema gaps on first run: `ts.converter`, millisecond
timestamps, and the counter ceiling all validated clean before the schemas were
tightened. That is the fixtures earning their keep before a line of Java exists.

Fixtures must contain fictional or non-sensitive values. Never put production
IMSI, tenant, token, device-secret, or gateway data into the repository. The
`*-production-*` fixtures of 18 Sep 2026 are shaped from live records with `uid`,
`clsbox` and `imsi` synthesised — the live samples themselves stayed outside the repo.

**Every fixture sets `tenant` and `maintenant` to different values**, with the
deliberate exception of the byte-faithful production records (`netstats-supplied-sample`,
`ltestats-production`, and the like), where the two are equal as production sends them.
**Never use those files as isolation fixtures.** Three of the 18 Sep fixtures carry the
real minol pattern — `tenant: minol-gateway`, `maintenant: minol` — which is the one
tenant where live data differs (DL-146).

## 7. Schema change procedure

1. Propose a versioned JSON Schema change with examples and compatibility assessment.
2. Obtain Heimdal and consumer approval when inbound/API behavior changes.
3. Add positive, negative, compatibility, and migration fixtures.
4. Update the schema registry, Input validation, OpenAPI references, and contract tests in one traceable change.
5. Add an ADR if the change affects message routing, storage, retention, API compatibility, or security.
