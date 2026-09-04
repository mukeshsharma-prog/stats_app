# Fixture catalogue

Built 18 August 2026 in answer to Q11a. **Rebuilt on the production wire format 29 August 2026
(ADR-007).** 75 fixture files — 73 individual fixtures plus the two combined bundles
(`valid/approved-types.json` and `invalid/validation-failures.json`), which are kept so nothing that
already references them breaks.

Run `python3 ../validate-fixtures.py` from `docs/schemas/` to assert the whole set. It checks 87
assertions and exits non-zero on any mismatch, so CI can gate on it.

Those 87 are **80 schema cases plus 7 `$expect` markers** — 5 `input-reject` and 2 `writer-dlq`.
The Java parity test (`SchemaFixtureParityTest`) mirrors the 80 schema cases only, so the two
numbers differing is correct and deliberate — do not "fix" one to match the other.

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

## `$expect: "writer-dlq"` — a fixture that is invalid but must NOT be 400'd

New with ADR-007 §5. The two gates have different consequences: Input validates the envelope
(failure → 400), the Writer validates the payload (failure → dead-letter). So a fixture in
`invalid/` no longer implies "expect a 400". A `writer-dlq` marker asserts both halves — the
record **passes** the envelope, and **fails** its per-type schema — and the validator checks the
envelope half explicitly. Without it, the lorastats dialect fixtures would sit in `invalid/`
asserting a 400 that must never happen: Heimdal retries only on a non-2xx, so a 400 there is
unrecoverable data loss where a dead-letter is a redrive.

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
| `lorastats-production-canonical.json` | Real production record. The canonical dialect: bare names, integer values, with `fsk_received`, `rx_short_pkt`, `timespan` |
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
| `lorastats-dialect-prefixed.json` | **`$expect: writer-dlq`.** Real production record using `lora_`-prefixed counters. Passes the envelope → Input returns 200 and publishes; fails the payload schema → the Writer dead-letters it (ADR-007 §4). Carries `version: 1`, same as the canonical dialect |
| `lorastats-dialect-string-values.json` | **`$expect: writer-dlq`.** Real production record with string-valued counters (`"176"`) and `rx_type_arol`. Same two-gate behaviour |
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
