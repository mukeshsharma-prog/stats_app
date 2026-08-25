# Stats App Schema Catalogue and Fixture Standard

**Status:** `ACTIVE` — schemas are strict and CI-gated. Reconciled 24 August 2026.  
**Purpose:** Provide machine-testable message contracts for Input validation, contract tests, Writer mapping, and API response references.

## 1. Required catalogue layout

```text
docs/schemas/
├── envelope-v1.schema.json
├── lorastats-v1.schema.json
├── ltestats-v1.schema.json
├── sysstats-v1.schema.json
├── omsstats-v1.schema.json
├── netstats-v1.schema.json              # strict since Q4a, 18 Aug 2026 — not a draft
├── validate-fixtures.py                 # CI gate: 71 assertions, exits non-zero on any mismatch
└── fixtures/
    ├── MANIFEST.md                      # what each fixture proves
    ├── valid/                           # must validate
    ├── invalid/                         # must fail validation
    ├── edge/                            # must validate, and still needs a decision
    └── malformed/                       # not schema-testable; exercises the parser layer
```

Run the gate from `docs/schemas/`:

```bash
python3 validate-fixtures.py     # 71 assertions checked, 0 failed
```

A schema document is an executable contract, not sample documentation. The Input Module must select the schema by `type`, validate the envelope and payload, and return the approved error response without publishing invalid records.

## 2. Common envelope rules

| Field | Rule |
|---|---|
| `uid` | Required non-empty string. UUID format may be enforced only if Heimdal guarantees it for all records. |
| `tenant` | Required non-empty string; canonical format/allowed character set pending tenant-policy approval. |
| `maintenant` | Required non-empty string; deliberately test a value different from `tenant`. |
| `gateway` | Required 12 hexadecimal characters. Canonical upper/lower case must be selected and normalized/validated consistently. |
| `type` | Required literal enumeration; must match schema, routing key, queue, consumer, and PostgreSQL table. |
| `ts.processor` | Required integer Unix epoch seconds. |
| `ts.device` | Required integer Unix epoch seconds. |
| `data` | Required object, validated by selected schema. |

The unexpected-field policy is **D14**: accept and preserve unknown `data` fields, reject invalid envelope fields. It is a deliberate decision, and it must never be settled silently by a Jackson annotation or deserialiser default. One asymmetry is deliberate — `ts` tolerates unknown members but `ts.converter` specifically fails, because its presence means the sender believes in a normalisation step this system does not perform (ADR-004).

## 3. Type schemas required from SRS v1.0

| Type | Required `data` fields |
|---|---|
| `sysstats` | `memfree` integer, `uptime` number, `idletime` number, `load1` number, `load5` number, `load15` number, `runable` string. |
| `lorastats` | `rx_nr_pkts`, `rx_crc_missing`, `rx_crc_bad`, `tx_success`, `tx_failed`, `tx_peek`, `tx_peek_index_zero`, `tx_override`, `tx_busy`, `tx_dequeue_error`, `tx_type_beacon`, `chip_comm_error`, `util_fopen_failed`—all integers. |
| `ltestats` | `rssi` integer, `rsrp` integer, `rsrq` integer, `access_technology` string, `operator` string, `plmn` integer, `imsi` string, `lac` string, `cid` string. |
| `omsstats` | `rx_nr_pkts` integer, `rx_valid_nr_pkts` integer, `rx_crc_bad` integer, `device_count` integer, `timespan` integer. OMS timestamp handling follows ADR-004. |
| `netstats` | **Strict since Q4a (18 Aug 2026).** `eth0` and `wwan` mandatory; additional interfaces allowed via `patternProperties`; `apipa` optional; `gw`/`ip`/`mask`/`dns[]` IPv4 dotted-quad; `tx`/`rx` digit-only strings → `BIGINT`; `timespan` integer seconds ≥ 0. **The Writer must range-check counters** against the signed `BIGINT` maximum. |

## 4. Netstats — answered, with one residual

Q4a was answered on 18 August 2026 and `netstats-v1.schema.json` is strict. The residual is not a schema question: the counter pattern caps at 19 digits (`9999999999999999999`), while signed `BIGINT` stops at `9223372036854775807`. **That gap is not expressible as a regex, so the Writer MUST range-check before conversion** — an unchecked overflow corrupts the value with no error anywhere, the failure mode with no observable behaviour. Fixture: `fixtures/invalid/netstats-counter-overflow.json`.

Still open and narrow: **IPv6 (Q4b)**. The schema accepts IPv4 only, because IPv6 does not appear in the sample. `fixtures/invalid/netstats-ipv6-address.json` asserts rejection today; if ZC confirms IPv6 gateways exist, that fixture flips to `valid/` and the `ipv4` `$def` widens. One schema change, no code change.

## 5. Constraints still requiring approval

The SRS specifies field types and mandatory fields but usually not semantic ranges or maximum string lengths. Add a requirement or documented policy for each question below before enforcing it as a rejection condition.

| Topic | Decision required |
|---|---|
| Timestamp plausibility | **Partly closed:** `ts.processor` and `ts.device` are bounded to 2000-01-01 … 2100-01-01, which is what makes a millisecond timestamp a 400. Still open: whether a `ts.device` *ahead of* `ts.processor` is accepted into a future partition or rejected (**Q14a**). |
| Counter values | Whether negative values are invalid and whether counter reset/overflow is possible. |
| Gateway casing | Canonical casing and whether mixed case is normalized or rejected. |
| IMSI protection | Validation format, redaction/masking in fixtures/logs/errors, storage encryption requirement. |
| `runable` field | Exact regular expression and whether spelling is intentionally legacy. |
| String lengths | Maximum size for tenant, operator, cell IDs, etc. |
| Additional fields | Strict rejection, preservation, or schema-version-based acceptance. |
| Type/schema version | Header/property/field convention and compatibility rollout. |

## 6. The fixture set — built, not aspirational

68 files, built 18 August 2026 in answer to Q11a. `fixtures/MANIFEST.md` records
what each one proves; the names below are the real ones.

| Fixture | What it proves |
|---|---|
| `valid/<type>-valid.json` | Every mandatory field in the canonical shape |
| `valid/<type>-boundary.json` | The edges of each numeric range — for `lorastats`, `tx_success` at the signed 64-bit maximum, which a 32-bit column wraps silently |
| `valid/<type>-tenant-isolation-pair.json` | **The highest-value fixture in the set.** Two records on one gateway, with `record_b.maintenant` deliberately set to `tenant-alpha`. Filter on `maintenant` and tenant-alpha sees both records — a leak. Filter on `tenant` and it sees exactly one. |
| `invalid/<type>-missing-required.json`, `-wrong-type.json` | 400, and nothing published |
| `invalid/envelope-unknown-type.json` | An unrecognised `type` is rejected before any table name is derived |
| `invalid/envelope-ts-in-milliseconds.json` | A millisecond timestamp is a *valid integer*. Nothing fails loudly; the record lands ~53,000 years in the future where retention never reaches it. The envelope bounds `ts` to 2000–2100, which is what turns this into a 400. |
| `invalid/envelope-oms-ts-converter.json` | ADR-004: a record carrying `ts.converter` is invalid |
| `invalid/netstats-counter-overflow.json` | 20 digits. **The Writer must range-check** — the schema cannot express the gap between 19 digits and the signed `BIGINT` maximum. |
| `edge/<type>-late-arrival.json`, `-retention-boundary-inside.json` | Retention keyed on `ts.device`, either side of the window |
| `edge/<type>-replay-duplicate.json` | `ON CONFLICT (ts_device, uid) DO NOTHING` absorbs it, and the suppression is metered |
| `edge/<type>-clock-skew-future.json` | **Has no answer yet (Q14a):** accept into a future partition, or reject? Filed under `edge/` and flagged rather than guessed. |
| `malformed/*` | Eight non-schema inputs — the parser layer, before validation runs |

Three of these found real schema gaps on first run: `ts.converter`, millisecond
timestamps, and the counter ceiling all validated clean before the schemas were
tightened. That is the fixtures earning their keep before a line of Java exists.

Fixtures must contain fictional or non-sensitive values. Never put production
IMSI, tenant, token, device-secret, or gateway data into the repository.

**Every fixture sets `tenant` and `maintenant` to different values**, with one
deliberate exception: `valid/netstats-supplied-sample.json` is kept verbatim as ZC
sent it, and there the two are equal. **Never use that file as an isolation
fixture.**

## 7. Schema change procedure

1. Propose a versioned JSON Schema change with examples and compatibility assessment.
2. Obtain Heimdal and consumer approval when inbound/API behavior changes.
3. Add positive, negative, compatibility, and migration fixtures.
4. Update the schema registry, Input validation, OpenAPI references, and contract tests in one traceable change.
5. Add an ADR if the change affects message routing, storage, retention, API compatibility, or security.
