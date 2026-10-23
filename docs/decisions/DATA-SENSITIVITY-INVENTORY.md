# Field-level data sensitivity inventory

**Engineering · Input document for the data-handling decision · No decision is taken here
except where marked ANSWERED or ADOPTED.**

The data-handling decision covers classification, masking, log and DLQ retention, access
controls, encryption and audit. Security and compliance cannot classify data nobody has
shown them, so this is that document: **every field in all six schemas, where it comes to
rest, and what kind of identifier it is.**

What this is **not**: a classification, a log-retention decision or an audit policy. Those
are not engineering's to take. This narrows them from open-ended policy to a one-page review
with specific questions (§6).

Derived from `docs/schemas/*.schema.json`, `docs/contracts/openapi.yaml`,
`stats-writer/src/main/resources/db/migration/`, and the logging rules in
`CONTRIBUTING.md`. Those files are the source of truth; if this drifts from them, they win.

---

## 1. Why the order of decisions matters

All five stat types are built and stored, including `ltestats` (IMSI, cell, carrier) and
`netstats` (network addresses). Two things make an unmasked identifier hard to take back:

> **The dead-letter queue never expires anything.** That is correct and is what stops silent
> record loss. It also makes an identifier in a dead-lettered record **permanent** until an
> operator disposes of it: a dead-lettered `ltestats` record holds its IMSI, serving-cell ID
> and device ID in the broker, and expiring the DLQ is not an available cleanup.

> **`data_extra` keeps anything the schema did not name.** A new identifier Heimdal starts
> sending is stored verbatim before any rule names it.

IMSI masking was therefore decided before `ltestats` storage merged (12 Oct 2026, §6 Q2).
The remaining open items (§6) do not have that kind of deadline, but each costs more once
production data accumulates.

---

## 2. Where a field can come to rest

Six sinks this project operates, plus one it does not (the URL path). A field's exposure is
the union of the sinks it reaches, and they have very different lifetimes.

| Sink | What it is | Lifetime | Access control |
|---|---|---|---|
| **Column** | A typed PostgreSQL column in the stat type's table | 90 days, partition detach-and-drop on `ts_device` | `stats` / `stats_ro` roles |
| **`passthrough`** | One nullable `jsonb` column holding `ts.datastore`, `ts.converter`, `ts.expiresOn`, `ts.deviceOrig`, `meta`, `status` | as Column | as Column |
| **`data_extra`** | One nullable `jsonb` column holding every `data` member the type's schema does not name, verbatim | as Column | as Column |
| **API** | Returned by `GET /v1/stats/{type}`, `GET /v1/stats/{type}/{clsbox}/{from}[/{to}]` and `GET /v1/stats/{type}/{uid}` | as Column | Keycloak, `stats.readOnly`, tenant-scoped |
| **URL path** | `clsbox` — the device identifier — and `uid` travel in the **request path** of the device-scoped and `uid` operations, so they reach every access log, proxy log and browser history between the consumer and `stats-api`, none of which this project controls or retains on a schedule | **UNDEFINED** — whoever runs the ingress. Same class of question as the Log sink | Whatever the ingress applies. Not a reason to move them into the body: a `GET` with a body is not a `GET` |
| **Log** | Structured JSON to stdout → Docker logs | **UNDEFINED** — §6 Q4 | **UNDEFINED** — §6 Q4 |
| **DLQ** | The whole record, byte-for-byte, in `app_statsstore.<type>.dlq` | **INDEFINITE by decision** — manual disposition within 14 days | Broker credentials; redrive is an authorised operations role |

The URL-path sink is narrow — two identifiers, both stable, one of them the device's MAC
address — and it is the one sink the API adds that this project does not operate. It is
listed so that the answer on `clsbox` covers it. (The ingest doors add nothing:
`POST /internal/stats` and `POST /external/stats` carry no identifier in the path.)

Two sinks take **every field of a record regardless of this table**: the DLQ takes the
record verbatim, and `data_extra` takes anything the schema did not name. Neither can be
narrowed field by field.

> **`data_extra` is a standing hole in any field-level policy.** It exists precisely to
> preserve members we did not anticipate. A masking rule written against known field names
> does not cover a *new* identifier Heimdal starts sending — that lands in `data_extra`
> verbatim and is metered only as `records_unmapped_data_field{type}`, which counts
> occurrences and does not name the member outside a `DEBUG` line.

---

## 3. Envelope — present on every record of every type

| Field | Class | Column | `passthrough` | API | Log | DLQ |
|---|---|---|---|---|---|---|
| `uid` | Record identifier. Also **the log correlation ID**, so it is on every log line by design — and in the **URL path** of `/{type}/{uid}` | ✅ | — | ✅ | ✅ **always** | ✅ |
| `tenant` | **Customer identifier.** The isolation field; reveals which Zenner customer a record belongs to | ✅ | — | ✅ | ✅ **always** | ✅ |
| `maintenant` | **Customer identifier** — the maintaining tenant, moved with `tenant` and equal to it on every tenant but minol, where it can be the parent name `minol` under `tenant: minol-gateway`. Never used for isolation | ✅ | — | ✅ | — | ✅ |
| `clsbox` | **Device identifier** — 12 hex, the physical gateway's MAC. Stable for the life of the device. Also in the **URL path** of `/{type}/{clsbox}/{from}[/{to}]` | ✅ | — | ✅ | ✅ **always** | ✅ |
| `type` | Operational only | ✅ | — | ✅ | ✅ | ✅ |
| `version` | Operational only | ✅ `record_version` | — | ✅ | — | ✅ |
| `status` | Operational only — Heimdal converter annotation | — | ✅ | ✅ | — | ✅ |
| `ts.server` | Operational only — arrival clock | ✅ | — | ✅ | — | ✅ |
| `ts.device` | Operational only — measurement clock. **Behavioural**: the retention and partition key | ✅ | — | ✅ | — | ✅ |
| `ts.datastore` `ts.converter` `ts.expiresOn` | Operational only, never read. `expiresOn` in either of its two spellings | — | ✅ | ✅ | — | ✅ |
| `ts.deviceOrig` `meta` | Operational only, never read — Heimdal's note that it rewrote `ts.device`, and the clock the gateway sent | — | ✅ | ✅ | — | ✅ |

**`uid`, `tenant` and `clsbox` are on every log line on the success path**, by deliberate
design: they are the correlation fields. That is a stable device identifier and a customer
identifier in the Docker log store, at a volume of 115.2M records/day, under a log-retention
policy that does not exist yet (§6 Q4). It is the largest-volume item in this document.

---

## 4. Payloads, by type

### `ltestats` — the only type carrying subscriber and location data

| Field | Class | Column | API | DLQ |
|---|---|---|---|---|
| **`imsi`** | 🔴 **Subscriber identifier.** Identifies a SIM and therefore a subscriber. Required on the *measured* shape; no length constraint on purpose | ✅ raw | ✅ **masked** — carrier prefix and at most the last four digits visible, length kept | ✅ raw |
| **`lac`** | 🟠 **Location.** Location Area Code | ✅ | ✅ as reported | ✅ |
| **`cid`** | 🟠 **Location.** Serving Cell ID. With `lac` this resolves to an approximate geographic position | ✅ | ✅ as reported | ✅ |
| `plmn` | 🟠 Carrier + country (MCC+MNC) | ✅ | ✅ | ✅ |
| `operator` | 🟠 Carrier name | ✅ | ✅ | ✅ |
| `rssi` `rsrp` `rsrq` | 🟡 Signal measurements. Not identifiers, but they refine a cell-level position. `rsrp`/`rsrq` are absent on 2G | ✅ | ✅ | ✅ |
| `access_Technology` `timespan` | Operational only | ✅ | ✅ | ✅ |
| `etxt` | Operational only — free-text reason a modem produced no measurement (`"unknown technology"`). The *unknown-technology* shape carries **none** of the fields above: a never-registered modem has no IMSI, no cell, no carrier | ✅ | ✅ | ✅ |

> **Three classes, not one.** The schema carries a subscriber identifier (`imsi`), location
> data (`lac`, `cid`, refined by `rssi`/`rsrp`/`rsrq`), and carrier/country (`plmn`,
> `operator`). They are on the same record, and every record also carries `clsbox`, which is
> stable across all of them. **Masking only IMSI still leaves a per-device location trail
> keyed on `clsbox`, sampled every ten minutes for ninety days.** Re-identifying the
> subscriber from that trail does not require the IMSI. The 12 Oct 2026 answer returns
> `lac`/`cid` as reported (§6 Q3); that is a decision about the API, not a classification.

### `netstats` — network addresses

`eth0` and `wwan` are mandatory; any additional interface validates against the same shape,
so this list is open-ended by design.

| Field (per interface) | Class | Storage | API | DLQ |
|---|---|---|---|---|
| `ip` `gw` `mask` `apipa` | 🟠 **Network identifier.** IPv4. On `wwan` this is carrier-assigned and may be personal data when linkable | `jsonb` interface map | ✅ | ✅ |
| `dns` | 🟠 Resolver configuration — reveals network topology | `jsonb` | ✅ | ✅ |
| `plmn` `plmn_list` | 🟠 Carrier + country; `plmn_list` is the set the modem may attach to | `jsonb` | ✅ | ✅ |
| `tx` `rx` `mtu` `fail_count` `driver` `need_init` `last_forced_reset` `last_powercycle` `timespan` | Operational only | `jsonb` (`timespan` typed) | ✅ | ✅ |

`netstats` stores interfaces as a `jsonb` map, so **field-level masking here means editing a
JSON document, not nulling a column.** That is a different implementation from `ltestats`
and should not be assumed to be the same work.

### `lorastats`, `omsstats`, `sysstats` — operational only

No subscriber, location or network identifier in any of the three. Sensitivity for these
types is carried **entirely by the envelope** (§3).

| Type | Fields | Note |
|---|---|---|
| `lorastats` | `rx_nr_pkts`, `rx_crc_missing`, `rx_crc_bad`, `rx_short_pkt`, `tx_success`, `tx_failed`, `tx_peek`, `tx_peek_index_zero`, `tx_override`, `tx_busy`, `tx_dequeue_error`, `tx_type_beacon`, `chip_comm_error`, `util_fopen_failed`, `fsk_received`, `rx_type_arol`, `timespan` | Radio counters |
| `omsstats` | `rx_nr_pkts`, `rx_valid_nr_pkts`, `rx_crc_bad`, `device_count`, `timespan` | `device_count` is the number of metering devices behind the gateway — commercially meaningful, not personal |
| `sysstats` | `memfree`, `uptime`, `idletime`, `load1`, `load5`, `load15`, `runable`, `temp`, `rtt`, `wan_if`, `rebootcause`, `timespan` | Host health. `rebootcause` and `wan_if` are stored as free `text` — a low-likelihood leak vector, worth a look but not a classification question |

---

## 5. Exposure paths and how they are closed

**1. Stack traces.** A PostgreSQL constraint violation puts the offending key values in its
`DETAIL:` line, so an exception logged at `ERROR` could print an IMSI. **Closed:** the Writer
logs failures only through `SafeFailure.describe()` — exception types and `SQLState`, no
message, no stack — and `WriterLogsCarryNoThrowableTest` fails the build otherwise.

**2. `data_extra` accepts identifiers nobody named.** §2. **Open by design**: it is the price
of not dropping unknown members. The counter says when it happens; the member name is only
at `DEBUG`.

**3. Log retention is undefined, and the log is sometimes the only copy.** A record rejected
at the envelope gate exists nowhere else — the log line is the only record it ever arrived.
So log retention cannot simply be minimised for privacy; it trades directly against the
ability to investigate a rejection. That tension belongs in §6 Q4 rather than being resolved
by whoever configures the log driver.

**Framework loggers.** At `TRACE`, Spring JDBC prints every bound SQL parameter and Spring
MVC every response body. **Closed:** those loggers are pinned in each module's
`application.properties`, and `LteNetstatsPayloadsAreNeverLoggedIntegrationTest` in each
module drives every outcome at `TRACE` and searches the output for planted values.

---

## 6. What we need from security and compliance

1. **Classification.** Given §3 and §4, what classification applies to (a) a record with
   operational payload only, (b) an `ltestats` record, (c) a `netstats` record? One label
   each is enough.
2. **IMSI — ANSWERED 12 Oct 2026: stored raw, masked at the API on the way out**, carrier
   prefix and at most the last four digits visible. The raw value remains in the table, the
   DLQ and backups, so their access and audit (Q4, Q5) cover it.
3. **Location — ANSWERED 12 Oct 2026: `lac`/`cid` are returned as reported.**
4. **Log retention and access.** A number, and who may read the log store. Note the trade in
   §5.3: shorter retention loses the only record of a rejected message.
5. **Audit.** What do you need evidence *of* — reads by tenant, redrives, schema changes,
   role grants? We will instrument whatever is named; we cannot guess the list.

**Two items are not security and compliance's**, and belong with the platform owner:

- **Log retention (mechanism)** is a Docker log-driver setting. Only the *number* and the
  *access list* above are security's.
- **Encryption at rest** is disk and volume configuration on the managed PostgreSQL, the same
  conversation as backup, PITR and RPO/RTO.

---

## 7. Engineering's defaults

Everything reversible was adopted without a signature; everything irreversible waits for one.

| # | Rule | Reversible? | Status |
|---|---|---|---|
| P1 | **Never log an `ltestats` or `netstats` payload at any level, including `DEBUG`.** Stricter than the general rule for these two types | Yes | **ADOPTED and enforced** by `LteNetstatsPayloadsAreNeverLoggedIntegrationTest` in every module |
| P2 | **Log the exception *type*, never a message or stack, on a write failure.** "Type and message" is not implementable — for a `PSQLException` the `DETAIL:` line *is* part of the message — so no message is logged at all | Yes | **ADOPTED and enforced** by `WriterLogsCarryNoThrowableTest` |
| P3 | `ltestats` storage does not merge until Q2 and Q3 are answered | n/a | **SATISFIED 12 Oct 2026** |
| P4 | If IMSI is ever masked at storage: **mask at the Writer**, storing a stable keyed hash so records for one subscriber still correlate | **NO — irreversible** | Not adopted; Q2 chose API masking |
| P5 | Treat `lac`/`cid` as location data and apply whatever IMSI gets | **NO — irreversible** | Not adopted; Q3 answered "as reported" |

---

## 8. Sources

| Fact | Source |
|---|---|
| Field names, types, requiredness | `docs/schemas/{envelope,lorastats,ltestats,sysstats,netstats,omsstats}-v1.schema.json` |
| What the API returns | `docs/contracts/openapi.yaml` — `Envelope`, `LtestatsData`, `NetworkInterface` |
| Columns | `stats-writer/src/main/resources/db/migration/` |
| `passthrough` / `data_extra`, retention, DLQ, database roles | `docs/ARCHITECTURE.md` |
| Logging rules | `CONTRIBUTING.md` — Logging |
| IMSI masking | `stats-api/.../query/ImsiMask.java` |
