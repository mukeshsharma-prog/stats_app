# Fixture catalogue

Built 18 August 2026 in answer to Q11a. **Rebuilt on the production wire format 29 August 2026
(ADR-007).** 79 fixture files — 77 individual fixtures plus the two combined bundles
(`valid/approved-types.json` and `invalid/validation-failures.json`), which are kept so nothing that
already references them breaks.

Run `python3 ../validate-fixtures.py` from `docs/schemas/` to assert the whole set. It checks 91
assertions and exits non-zero on any mismatch, so CI can gate on it.

Those 91 are **84 schema cases plus 7 `$expect` markers** — 5 `input-reject`, 1 `writer-dlq` and
1 `writer-range-reject`. The Java parity test (`SchemaFixtureParityTest`) mirrors the 84 schema
cases only, so the two numbers differing is correct and deliberate — do not "fix" one to match the
other.

**+1 on 5 September 2026:** `valid/lorastats-clsbox-lowercase.json` (DL-115). Every `clsbox` in
this set was uppercase, so nothing here could catch a device splitting across two index keys.

**Reshaped on 8 September 2026 by T-063 and T-064 (DL-127, DL-128).** The two production dialect
fixtures **moved `invalid/` → `valid/`** and lost their `writer-dlq` markers when all three
dialects became valid, and three fixtures were added:
`invalid/lorastats-dialect-unknown.json` (the fourth spelling, which must still dead-letter),
`valid/lorastats-dialect-string-overflow.json` (schema-valid, refused by the Writer's `BIGINT`
range check) and `valid/lorastats-status-not-ok.json` (a failed upstream conversion, stored and
metered). The marker **count** stayed at seven while its composition changed completely — worth
knowing, because a stable total is exactly what makes this kind of change look like no change.

## What changed on 29 August 2026

Every fixture was migrated to the production envelope: `gateway` → `clsbox`,
`ts.processor` → `ts.server`. Beyond the rename, four assertions **inverted** and are worth
knowing about, because each was previously asserting the opposite of the truth:

| Fixture | Was | Is |
|---|---|---|
| `omsstats-ts-converter-present.json` | in `invalid/` — `ts.converter` made a record invalid | in `valid/` — production sends it on 100% of records (ADR-007 §2). Now also carries `ts.datastore`, `ts.expiresOn`, `version` and `status`, making it the regression guard for the whole envelope |
| `ltestats-wrong-type.json` | asserted `plmn` as a **string** was the wrong type | production sends every ltestats numeric as a string, so the assertion inverted. Retargeted onto `rssi` carrying an integer |
| `netstats-missing-required.json` | removed `wwan` entirely, duplicating `netstats-missing-wwan.json` | asserts the surviving interface requirement — a fully addressed `wwan` with no `tx`/`rx` |
| `envelope-clsbox-not-hex.json`, `envelope-clsbox-too-short.json` | named `envelope-gateway-*` | renamed with the field |

## `$expect: "writer-range-reject"` — valid to the catalogue, refused by the Writer

New with T-063 (DL-105 rule 3). A dialect-B string counter is capped at **nineteen digits** by
pattern, and the signed `BIGINT` maximum `9223372036854775807` is also nineteen digits — the gap
between the two is not expressible as a regex. So the record is genuinely schema-**valid** and the
only place the line can be held is the Writer, before conversion. Neither existing marker fits:
`writer-dlq` means "fails its per-type schema", and this one does not. It is to the Writer what
`input-reject` is to Input — both mark a rule that is arithmetic or cross-field rather than
structural. `valid/lorastats-dialect-string-overflow.json` is the case it exists for, and the
failure it guards against is the silent kind: a coercion that catches the overflow and stores `0`
produces a row that is **wrong** rather than missing.

## `$expect: "writer-dlq"` — a fixture that is invalid but must NOT be 400'd

New with ADR-007 §5. The two gates have different consequences: Input validates the envelope
(failure → 400), the Writer validates the payload (failure → dead-letter). So a fixture in
`invalid/` no longer implies "expect a 400". A `writer-dlq` marker asserts both halves — the
record **passes** the envelope, and **fails** its per-type schema — and the validator checks the
envelope half explicitly. Without it, `invalid/lorastats-dialect-unknown.json` would sit in
`invalid/` asserting a 400 that must never happen: Heimdal retries only on a non-2xx, so a 400
there is unrecoverable data loss where a dead-letter is a redrive.

## Conventions

- **`tenant` and `maintenant` differ in every fixture but one.** They are equal in most of the
  supplied sample data, and an implementation filtering on the wrong one passes every test written
  from those samples while leaking data in production. The single exception is
  `valid/netstats-supplied-sample.json`, which is kept byte-for-byte as ZC sent it and therefore has
  `tenant == maintenant`. **Never use that file as a tenant-isolation fixture** — it is the exact
  shape that makes a wrong implementation look correct. Use `<type>-tenant-isolation-pair.json`.
- **Timestamps are Unix seconds**, anchored to 2026-08-18T12:00:00Z. Ten digits, never thirteen.
  The exception is `ts.expiresOn`, which is an **ISO-8601 string** — that is what production sends.
- **The nine `*-production-*.json` and dialect fixtures are byte-faithful to real records** from
  29 Aug 2026 and are deliberately NOT edited to satisfy the conventions below. In particular they
  carry `tenant == maintenant == "mzc"`. They are production regression guards, never isolation
  tests.
- **`uid` is stable across regeneration**, so a fixture referenced in a test keeps its identity.
- Each file carries a `$comment` explaining what it proves and what the expected outcome is. The
  validator strips `$`-prefixed keys before validating.

## `valid/` — must be accepted

| File | Proves |
|---|---|
| `<type>-valid.json` | The plain happy path for each of the five types |
| `<type>-boundary.json` | The edges of each numeric range — zeroed counters, a freshly booted gateway, worst usable signal, and for `lorastats` a `tx_success` at the signed 64-bit maximum that a 32-bit column would silently wrap |
| `<type>-tenant-isolation-pair.json` | Two records on one gateway. `record_b.maintenant` is deliberately `tenant-alpha`: filtering on `maintenant` returns both and leaks `tenant-beta`; filtering on `tenant` returns exactly one. **The highest-value fixture in the set.** |
| `netstats-no-apipa.json` | `apipa` is optional — ZC's own sample omits it on `wwan` |
| `netstats-extra-interface.json` | A third interface (`eth1`) is accepted without a schema change (STA-14A) |
| `omsstats-ts-converter-present.json` | The full production `ts` block — `server`, `device`, `datastore`, `converter`, `expiresOn` — plus `version` and `status`, all accepted. **Moved from `invalid/` on 29 Aug 2026** |
| `lorastats-production-canonical.json` | Real production record. **Dialect A** — bare names, integer values, with `fsk_received`, `rx_short_pkt`, `timespan` |
| `lorastats-dialect-string-values.json` | Real production record. **Dialect B** — bare names, every counter a **string** (`"176"`), `rx_type_arol` present and no `timespan` at all. **Moved from `invalid/` on 8 Sep 2026** when T-063 built DL-105. The Writer coerces each counter with a `BIGINT` range check *before* conversion |
| `lorastats-dialect-prefixed.json` | Real production record. **Dialect C** — and **not** "everything prefixed": fifteen counters are `lora_`-prefixed while `fsk_received` and `timespan` arrive **bare**, and `rx_type_arol` is absent. **Moved from `invalid/` on 8 Sep 2026.** Read this file before writing anything against dialect C; a parser written from the prose rejects it |
| `lorastats-dialect-string-overflow.json` | **`$expect: writer-range-reject`.** Dialect B with `rx_nr_pkts` at `9223372036854775808` — one above the signed `BIGINT` maximum, and still nineteen digits, so the schema **admits** it. The Writer refuses it before conversion (DL-105 rule 3). Lives in `valid/` because that is what it is |
| `lorastats-status-not-ok.json` | `status` reports a **failed** upstream conversion (Q15d, DL-108). Valid everywhere: the Writer **stores** it, meters `records_upstream_status_not_ok{type}` and logs at `WARN`, and never dead-letters it — a redrive would repeat the same verdict forever. The literal value is an example; the comparison behind the counter is value-agnostic |
| `lorastats-clsbox-lowercase.json` | The same device as the record above (`04B64810652B`) written lowercase. **Schema-valid on purpose** — the envelope is `^[0-9A-Fa-f]{12}$` and stays that way, so a real measurement is never rejected over hex case. The Writer uppercases and a `CHECK` enforces it, so both spellings share one key in `(tenant, clsbox, …)` (DL-115) |
| `ltestats-production.json` | Real production record. Every numeric a **string**, and `access_Technology` with a capital T |
| `sysstats-production-minimal.json` | Real production record from a gateway with no temperature sensor — `temp`, `rtt`, `wan_if`, `rebootcause`, `timespan` all absent |
| `sysstats-production-full.json` | Real production record carrying every optional field, with `timespan` as the **string** `"300"` |
| `omsstats-production.json` | Real production record. The one type SRS v1.0 got right |
| `netstats-production-healthy.json` | Real production record, both interfaces up, with `driver`, `mtu`, `fail_count` |
| `netstats-production-wwan-down.json` | Real production record — a `wwan` that never came up: `need_init`, `fail_count`, `last_forced_reset`, and **no address at all**. The reason the interface required-set is now `tx`/`rx`. Also the only sampled record where `ts.device != ts.server` |
| `approved-types.json`, `netstats-supplied-sample.json` | Pre-existing bundles, retained |

## `invalid/` — must fail schema validation

**Not all of these are 400s.** Since ADR-007 §5 the envelope is Input's gate and the payload is
the Writer's, so a fixture here fails *a* schema; which gate it fails determines whether the
consequence is a 400 or a dead-letter. A fixture carrying `$expect: "writer-dlq"` passes the
envelope and must **not** be 400'd.

| File | Proves |
|---|---|
| `lorastats-dialect-unknown.json` | **`$expect: writer-dlq`.** A synthetic FOURTH spelling (`lorawan_`-prefixed), carrying `version: 1` like every other dialect so nothing announces that it is new. Passes the envelope → Input returns 200 and publishes; matches none of the three `oneOf` branches → the Writer dead-letters it. **This is the fixture that proves the enumeration is closed** (DL-105 rule 4). If it ever starts validating, the union has been reopened |
| `<type>-missing-required.json` | A mandatory payload field is absent → `missing` |
| `<type>-wrong-type.json` | Right field, wrong JSON type → `invalid_type`. The response must not echo the offending value |
| `envelope-missing-tenant.json` | `tenant` is never defaulted; its absence is a hard 400 |
| `envelope-clsbox-too-short.json`, `envelope-clsbox-not-hex.json` | The 12-hex-character `clsbox` rule. Renamed with the field on 29 Aug 2026 (ADR-007 §1) |
| `envelope-ts-in-milliseconds.json` | A millisecond timestamp is a *valid integer* — nothing fails loudly, the row just lands ~53,000 years out where retention never reaches it. The envelope's 2000–2100 bound is what makes this a 400 |
| `envelope-unknown-type.json` | `unknown_stat_type`, and no publish — there is no queue to route it to |
| ~~`envelope-oms-ts-converter.json`~~ | **Moved to `valid/omsstats-ts-converter-present.json` on 29 Aug 2026** — the assertion inverted (ADR-007 §2) |
| `netstats-missing-wwan.json` | Q4a: `eth0` and `wwan` are mandatory |
| `netstats-counter-not-numeric.json` | Counters are strings but digits-only, so this fails at ingest instead of failing a cast at query time |
| `netstats-counter-overflow.json` | 20 digits. **The Writer must range-check** — the schema cannot express the gap between 19 digits and the signed `BIGINT` maximum |
| `netstats-ipv6-address.json` | v1 is IPv4-only. Flips to valid if ZC confirms IPv6 gateways |

## `edge/` — schema-valid records that carry a behavioural decision

| File | Proves |
|---|---|
| `<type>-late-arrival.json` | `ts.device` 95 days old, `ts.server` current — a gateway back from a long outage. No live partition exists. `expired_on_arrival`: meter, DLQ without retry, ACK. **Engineering agreed 29 Aug 2026; still pending product sign-off (D5b)** |
| `<type>-retention-boundary-inside.json` | 89 days old — one day inside the window. **Must be stored.** Pairs with the above to test both sides |
| `<type>-replay-duplicate.json` | Byte-identical `uid` and `ts.device` to the valid fixture. Models a Heimdal replay or a crash between commit and ACK. `ON CONFLICT DO NOTHING` makes it a no-op that increments `duplicates_total` |
| `<type>-clock-skew-future.json` | `ts.device` an hour ahead of `ts.server`. **Decided 29 Aug 2026 (Q14a, ADR-003 D9): rejected at Input with 400 `clock_skew_exceeded`**, metered as `records_clock_skew_rejected`. Tolerance is `stats.input.validation.max-clock-skew`, default `PT5M`. The files stay here and stay **schema-valid** — the check compares two fields, which JSON Schema cannot do — and carry `$expect: "input-reject"` + `$expectCode`, which `validate-fixtures.py` asserts |

## `malformed/` — the parser layer, before validation runs

Eight inputs that are not valid JSON documents, and two that are not `.json` at all. See
[`malformed/README.md`](malformed/README.md) for the expected status code of each.
