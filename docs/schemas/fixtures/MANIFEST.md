# Fixture catalogue

Built 18 August 2026 in answer to Q11a. **68 fixture files**, replacing the two combined bundles
(`valid/approved-types.json` and `invalid/validation-failures.json`), which are kept so nothing that
already references them breaks.

Run `python3 ../validate-fixtures.py` from `docs/schemas/` to assert the whole set. It checks 71
assertions and exits non-zero on any mismatch, so CI can gate on it.

## Conventions

- **`tenant` and `maintenant` differ in every fixture but one.** They are equal in most of the
  supplied sample data, and an implementation filtering on the wrong one passes every test written
  from those samples while leaking data in production. The single exception is
  `valid/netstats-supplied-sample.json`, which is kept byte-for-byte as ZC sent it and therefore has
  `tenant == maintenant`. **Never use that file as a tenant-isolation fixture** — it is the exact
  shape that makes a wrong implementation look correct. Use `<type>-tenant-isolation-pair.json`.
- **Timestamps are Unix seconds**, anchored to 2026-08-18T12:00:00Z. Ten digits, never thirteen.
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
| `approved-types.json`, `netstats-supplied-sample.json` | Pre-existing bundles, retained |

## `invalid/` — must be rejected with 400, and nothing published

| File | Proves |
|---|---|
| `<type>-missing-required.json` | A mandatory payload field is absent → `missing` |
| `<type>-wrong-type.json` | Right field, wrong JSON type → `invalid_type`. The response must not echo the offending value |
| `envelope-missing-tenant.json` | `tenant` is never defaulted; its absence is a hard 400 |
| `envelope-gateway-too-short.json`, `envelope-gateway-not-hex.json` | The 12-hex-character gateway rule |
| `envelope-ts-in-milliseconds.json` | A millisecond timestamp is a *valid integer* — nothing fails loudly, the row just lands ~53,000 years out where retention never reaches it. The envelope's 2000–2100 bound is what makes this a 400 |
| `envelope-unknown-type.json` | `unknown_stat_type`, and no publish — there is no queue to route it to |
| `envelope-oms-ts-converter.json` | ADR-004: a record carrying `ts.converter` is invalid |
| `netstats-missing-wwan.json` | Q4a: `eth0` and `wwan` are mandatory |
| `netstats-counter-not-numeric.json` | Counters are strings but digits-only, so this fails at ingest instead of failing a cast at query time |
| `netstats-counter-overflow.json` | 20 digits. **The Writer must range-check** — the schema cannot express the gap between 19 digits and the signed `BIGINT` maximum |
| `netstats-ipv6-address.json` | v1 is IPv4-only. Flips to valid if ZC confirms IPv6 gateways |

## `edge/` — valid records that still need a decision

| File | Proves |
|---|---|
| `<type>-late-arrival.json` | `ts.device` 95 days old, `ts.processor` current — a gateway back from a long outage. No live partition exists. `expired_on_arrival`: meter, DLQ without retry, ACK. Still pending product sign-off (D5b) |
| `<type>-retention-boundary-inside.json` | 89 days old — one day inside the window. **Must be stored.** Pairs with the above to test both sides |
| `<type>-replay-duplicate.json` | Byte-identical `uid` and `ts.device` to the valid fixture. Models a Heimdal replay or a crash between commit and ACK. `ON CONFLICT DO NOTHING` makes it a no-op that increments `duplicates_total` |
| `<type>-clock-skew-future.json` | `ts.device` an hour ahead of `ts.processor`. **No decision exists yet** — accept into a future partition, or reject? Flagged rather than guessed |

## `malformed/` — the parser layer, before validation runs

Eight inputs that are not valid JSON documents, and two that are not `.json` at all. See
[`malformed/README.md`](malformed/README.md) for the expected status code of each.
