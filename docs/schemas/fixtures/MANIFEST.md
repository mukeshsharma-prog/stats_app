# Fixture catalogue

Built 18 August 2026 in answer to Q11a. **Rebuilt on the production wire format 29 August 2026
(ADR-007).** 87 fixture files — 85 individual fixtures plus the two combined bundles
(`valid/approved-types.json` and `invalid/validation-failures.json`), which are kept so nothing that
already references them breaks.

Run `python3 ../validate-fixtures.py` from `docs/schemas/` to assert the whole set. It checks 107
assertions and exits non-zero on any mismatch, so CI can gate on it.

Those 107 are **92 schema cases plus 15 `$expect` markers** — 5 `input-reject`, 2 `writer-dlq`,
3 `writer-range-reject` and 5 `writer-skip`. The Java parity test (`SchemaFixtureParityTest`)
mirrors the 92 schema cases only, so the two numbers differing is correct and deliberate — do not
"fix" one to match the other.

**+1 on 21 September 2026 — the third type (T-036, DL-153).** `valid/omsstats-counter-overflow.json`
is the third `writer-range-reject`, and the first whose overflow arrives as a JSON **integer** rather
than a digit string: `rx_nr_pkts` at `9223372036854775808` on a type whose schema types every member
as `integer` and states no maximum. JSON Schema's `integer` has no width, so the record is schema-valid;
the column is `bigint`, so the Writer refuses it before the narrowing conversion and writes nothing.
It exists because the two earlier overflow fixtures both go through a *string* parser, and a type with
no string coercion at all still has this line to hold. Markers **14 → 15**, assertions **105 → 107**,
files **86 → 87**.

**+1 on 19 September 2026 — the second type (T-035, DL-148).** `valid/sysstats-timespan-overflow.json`
is to `sysstats` what `lorastats-dialect-string-overflow.json` is to `lorastats`: schema-valid, nineteen
digits, one above the signed `BIGINT` maximum, refused by the Writer's range check *before* conversion
and never zeroed or truncated. It exists because `sysstats` is the one type where `timespan` is a
**string** on the wire, and a string that parses is the place a silent overflow hides. Markers
**13 → 14**, assertions **103 → 105**, files **85 → 86**.

**+6 on 18 September 2026 — the first look at live data (DL-146).** 1,200 records from the
Datahub's `datastore-stats` aggregate endpoint were run through the same validators CI uses,
and six shapes that no document had seen became fixtures, hand-authored from the observed shape
with `uid`, `clsbox` and `imsi` synthesised (the live samples carry subscriber identifiers and
stayed outside the repo). Two `ltestats` records the 29 Aug schema **dead-lettered** — a 2G modem
with no `rsrp`/`rsrq`, and a never-registered modem sending only `etxt` — are now `valid/`; the
half-and-half record the widened schema must still refuse is `invalid/` with `writer-dlq`; and
three passthrough shapes — `meta`/`ts.deviceOrig`, `expiresOn` as `{"$date": …}`, and a
dialect-B `lorastats` record with an integer extra member — pin behaviour that was correct by
accident. `invalid/ltestats-missing-required.json` changed the field it omits, because the one it
used to omit (`rsrp`) stopped being required. Markers **12 → 13**, assertions **96 → 103**.

**+1 on 5 September 2026:** `valid/lorastats-clsbox-lowercase.json` (DL-115). Every `clsbox` in
this set was uppercase, so nothing here could catch a device splitting across two index keys.

**+5 markers on 10 September 2026 by T-026 (DL-133).** The five `<type>-late-arrival.json`
fixtures gained `$expect: "writer-skip"` with `$expectCode: "expired_on_arrival"`, and their
`$comment`s were corrected. They were wrong in two ways at once: they said the record is
`DLQ`'d without retry — the opposite of the signed policy, which never dead-letters it — and they
said the policy was "still pending product sign-off", which stopped being true on 4 Sep 2026
(D5b → DL-112). Carrying **no marker** was the third and quietest problem: an unmarked `edge/`
fixture asserts only "the schema accepts this", so a signed, deliberate data-loss path had no
assertion anywhere in this set. That is exactly the gap `input-reject` was invented to close for
the clock-skew fixtures. The marker count moves **7 → 12** and the assertion count **91 → 96**;
no fixture was added, removed or moved.

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

- **`tenant` and `maintenant` differ in every synthetic fixture.** They are equal on every live
  record of every tenant but one, and an implementation filtering on the wrong one passes every
  test written from real data while leaking in production. The exceptions are the byte-faithful
  production records (`valid/netstats-supplied-sample.json`, `ltestats-production.json` and the
  other `*-production*` files of 29 Aug), which have `tenant == maintenant` as production sends
  it. Three fixtures of 18 Sep 2026 carry the one real pattern where they *differ* —
  `tenant: minol-gateway`, `maintenant: minol` — which is the minol tenant only (DL-146); a
  filter on `maintenant` there drops rows rather than leaking them, a different wrong answer.
  **Never use a production-shaped file as a tenant-isolation fixture** — it is the exact
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
| `ltestats-production-2g-no-rsrp.json` | **DL-146.** Live shape: `access_Technology: "2G"` and **no `rsrp`/`rsrq`** — LTE measurements a 2G modem does not have. 5 of 300 live records; every one dead-lettered before `shapeMeasured` made the pair optional. Carries the real minol `tenant`/`maintenant` split |
| `ltestats-production-unknown-technology.json` | **DL-146.** Live shape: `data` is `{"etxt": "unknown technology"}` and nothing else — a modem that never registered. The second, closed shape of the `ltestats` `oneOf` (`shapeUnknownTechnology`). `status` is still `["conversion ok"]` |
| `lorastats-dialect-string-util-restarted.json` | **DL-146.** Live shape: dialect B with `util_restarted` as an **integer** among fourteen strings, and no `rx_type_arol`. Valid — detection reads only the thirteen required counters, the extra lands in `data_extra` and increments `records_unmapped_data_field`. Pinned so nobody tightens the mixed-type rule to reject it |
| `sysstats-production-ts-device-corrected.json` | **DL-146.** Live shape: `meta: {"tsDeviceCorrected": true}` and `ts.deviceOrig` — Heimdal rewrote `ts.device` to equal `ts.server` and kept the gateway's clock (12 s behind). Must round-trip through the typed view **losslessly**; before DL-146 both members were silently dropped |
| `omsstats-expires-on-extended-json.json` | **DL-146.** `ts.expiresOn` as `{"$date": "…"}`, the form the live datastore returns. Accepted and stored in that form; which form the HTTP forward sends is Q15e |
| `sysstats-production-minimal.json` | Real production record from a gateway with no temperature sensor — `temp`, `rtt`, `wan_if`, `rebootcause`, `timespan` all absent |
| `sysstats-production-full.json` | Real production record carrying every optional field, with `timespan` as the **string** `"300"` |
| `sysstats-timespan-overflow.json` | **`$expect: writer-range-reject`** (DL-148). The full production record with `timespan` at `"9223372036854775808"` — one above the signed `BIGINT` maximum, still nineteen digits, so the schema **admits** it. The Writer refuses it before conversion (STA-04C) and writes nothing. Lives in `valid/` because that is what it is |
| `omsstats-production.json` | Real production record. The one type SRS v1.0 got right |
| `omsstats-counter-overflow.json` | **`$expect: writer-range-reject`** (DL-153). The production record with `rx_nr_pkts` at the JSON integer `9223372036854775808` — one above the signed `BIGINT` maximum; the schema has no maximum and JSON Schema's `integer` has no width, so it **admits** it. The Writer refuses it before conversion (STA-04C) and writes nothing. Lives in `valid/` because that is what it is |
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
| `ltestats-shape-mixed.json` | **`$expect: writer-dlq`.** `etxt` beside a partial measurement (`rssi`, `plmn`, no `access_Technology`). Passes the envelope → 200 and published; matches **neither** closed `ltestats` shape → the Writer dead-letters it (DL-146). The `ltestats` counterpart of the row below: if it ever validates, one of the two shapes has been loosened |
| `lorastats-dialect-unknown.json` | **`$expect: writer-dlq`.** A synthetic FOURTH spelling (`lorawan_`-prefixed), carrying `version: 1` like every other dialect so nothing announces that it is new. Passes the envelope → Input returns 200 and publishes; matches none of the three `oneOf` branches → the Writer dead-letters it. **This is the fixture that proves the enumeration is closed** (DL-105 rule 4). If it ever starts validating, the union has been reopened |
| `<type>-missing-required.json` | A mandatory payload field is absent → `missing`. For `ltestats` the absent field is `access_Technology` since 18 Sep 2026 — it used to be `rsrp`, which DL-146 made optional |
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
| `<type>-late-arrival.json` | `ts.device` 95 days old, `ts.server` current — a gateway back from a long outage. No live partition exists, and none ever will: the record only gets older. `$expect: "writer-skip"`, `$expectCode: "expired_on_arrival"` — the Writer **skips** the write, meters `records_expired_on_arrival{type}`, logs at `WARN` and **ACKs**. **Never dead-lettered:** the record is valid, the DLQ never expires anything (ADR-002 §12), and a redrive would re-derive the same verdict forever. **SIGNED 4 Sep 2026 (D5b → DL-112), BUILT 10 Sep 2026 (T-026, DL-133)** |
| `<type>-retention-boundary-inside.json` | 89 days old — one day inside the window. **Must be stored.** Pairs with the above to test both sides |
| `<type>-replay-duplicate.json` | Byte-identical `uid` and `ts.device` to the valid fixture. Models a Heimdal replay or a crash between commit and ACK. `ON CONFLICT DO NOTHING` makes it a no-op that increments `duplicates_total` |
| `<type>-clock-skew-future.json` | `ts.device` an hour ahead of `ts.server`. **Decided 29 Aug 2026 (Q14a, ADR-003 D9): rejected at Input with 400 `clock_skew_exceeded`**, metered as `records_clock_skew_rejected`. Tolerance is `stats.input.validation.max-clock-skew`, default `PT5M`. The files stay here and stay **schema-valid** — the check compares two fields, which JSON Schema cannot do — and carry `$expect: "input-reject"` + `$expectCode`, which `validate-fixtures.py` asserts |

## `malformed/` — the parser layer, before validation runs

Eight inputs that are not valid JSON documents, and two that are not `.json` at all. See
[`malformed/README.md`](malformed/README.md) for the expected status code of each.
