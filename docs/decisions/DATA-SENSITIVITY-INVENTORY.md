# Field-level data sensitivity inventory

**4 September 2026 · Engineering · Input document for D-12 · No decision is taken here.**

D-12 (*data handling: classification, masking, log/DLQ retention, access controls,
encryption, audit*) has sat `PARTIAL` since 26 August because the meeting has no input
document. Security and compliance cannot classify data nobody has shown them. This is
that document: **every field in all six schemas, where it comes to rest, and what kind
of identifier it is.**

What this is **not**: a classification, a masking policy, or a retention decision. Those
are D-12's remaining five items and they are not engineering's to take. This narrows them
from open-ended policy to a one-page review with five specific questions (§6).

Derived from `docs/schemas/*.schema.json`, `docs/contracts/openapi.yaml`,
`stats-writer/src/main/resources/db/migration/`, and the logging rules in
`claude/DEVELOPMENT.md` §7. Those files are the source of truth; if this drifts from
them, they win.

---

## 1. Why this is time-critical, and what the deadline is

The deadline is a task, not a date.

**IMSI exists in exactly one place — `ltestats.data.imsi` — and `ltestats` is not built
yet.** `T-034 (fan out: ltestats)` is `TODO`, and `lorastats` is the only type with a
table. So today there is no IMSI, no cell ID and no IP address anywhere in the running
system: not in PostgreSQL, not in a log, not in a dead-letter queue.

Every item below is therefore still a free configuration choice. That stops being true
the moment T-034 lands, because of an interaction nobody has written down:

> **ADR-002 §12 forbids the dead-letter queue from ever expiring — "no TTL, ever".**
> That decision is correct and is what stops silent record loss. It is also what makes an
> unmasked identifier **permanent**. A dead-lettered `ltestats` record holds its IMSI, its
> serving-cell ID and its device ID in the broker indefinitely, and ADR-002 §12 forbids the
> obvious cleanup.

Before T-034, masking is a property. After it, masking is a remediation project across a
partitioned table, an API contract, a log store and a queue that is not allowed to forget.

**There is currently no `T-nnn` task for any part of D-12.** Nothing in the execution
tracker will surface this. That is the same defect the 29 August pass caught for Q14a.

---

## 2. Where a field can come to rest

Six sinks. A field's exposure is the union of the sinks it reaches, and they have very
different lifetimes.

| Sink | What it is | Lifetime | Access control |
|---|---|---|---|
| **Column** | A typed PostgreSQL column in the stat type's table | 90 days, partition detach-and-drop on `ts_device` (STA-23) | `stats` / `stats_ro` roles (ADR-006) |
| **`passthrough`** | One nullable `jsonb` column holding `ts.datastore`, `ts.converter`, `ts.expiresOn`, `status` (DL-094) | as Column | as Column |
| **`data_extra`** | One nullable `jsonb` column holding every `data` member the type's schema does not name, verbatim (D14b, DL-095) | as Column | as Column |
| **API** | Returned by `GET /v1/stats/{type}` | as Column | Keycloak, `stats.readOnly`, tenant-scoped (ADR-006) |
| **Log** | Structured JSON to stdout → Docker logs | **UNDEFINED — this is D-12** | **UNDEFINED — this is D-12** |
| **DLQ** | The whole record, byte-for-byte, in `app_statsstore.<type>.dlq` | **INDEFINITE by decision — ADR-002 §12** | Broker credentials; redrive is an authorised ops role |

Two sinks take **every field of a record regardless of this table**: the DLQ takes the
record verbatim, and `data_extra` takes anything the schema did not name. Neither can be
narrowed field by field.

> **`data_extra` is a standing hole in any field-level policy.** It exists precisely to
> preserve members we did not anticipate (D14/D14b). A masking rule written against known
> field names does not cover a *new* identifier Heimdal starts sending — that lands in
> `data_extra` verbatim and is metered only as `records_unmapped_data_field{type}`, which
> counts occurrences and does not name the member outside a `DEBUG` line.

---

## 3. Envelope — present on every record of every type

| Field | Class | Column | `passthrough` | API | Log | DLQ |
|---|---|---|---|---|---|---|
| `uid` | Record identifier. Also **the log correlation ID** (D1), so it is on every log line by design | ✅ | — | ✅ | ✅ **always** | ✅ |
| `tenant` | **Customer identifier.** The isolation field; reveals which Zenner customer a record belongs to | ✅ | — | ✅ | ✅ **always** | ✅ |
| `maintenant` | **Customer identifier** — the managing tenant. Never used for isolation (trap 1) | ✅ | — | ✅ | — | ✅ |
| `clsbox` | **Device identifier** — 12 hex, the physical gateway. Stable for the life of the device | ✅ | — | ✅ | ✅ **always** | ✅ |
| `type` | Operational only | ✅ | — | ✅ | ✅ | ✅ |
| `version` | Operational only | ✅ `record_version` | — | ✅ | — | ✅ |
| `status` | Operational only — Heimdal converter annotation | — | ✅ | ✅ | — | ✅ |
| `ts.server` | Operational only — arrival clock | ✅ | — | ✅ | — | ✅ |
| `ts.device` | Operational only — measurement clock. **Behavioural**: the retention and partition key | ✅ | — | ✅ | — | ✅ |
| `ts.datastore` `ts.converter` `ts.expiresOn` | Operational only, never read | — | ✅ | ✅ | — | ✅ |

**`uid`, `tenant` and `clsbox` are on every log line on the success path**, by deliberate
design (DL-080, `DEVELOPMENT.md` §7). That is a stable device identifier and a customer
identifier in the Docker log store, at a volume of 115.2M records/day, under a retention
policy that does not exist. It is the largest-volume item in this document and it is not
what anyone has been discussing.

---

## 4. Payloads, by type

### `ltestats` — the only type carrying subscriber and location data

Not built yet (T-034). **This is the table the review is for.**

| Field | Class | Column (planned) | API | DLQ |
|---|---|---|---|---|
| **`imsi`** | 🔴 **Subscriber identifier.** Identifies a SIM and therefore a subscriber. Required by the schema; no length constraint on purpose | ✅ | ✅ *"SENSITIVE — masking policy pending review"* | ✅ |
| **`lac`** | 🟠 **Location.** Location Area Code | ✅ | ✅ | ✅ |
| **`cid`** | 🟠 **Location.** Serving Cell ID. With `lac` this resolves to an approximate geographic position | ✅ | ✅ | ✅ |
| `plmn` | 🟠 Carrier + country (MCC+MNC) | ✅ | ✅ | ✅ |
| `operator` | 🟠 Carrier name | ✅ | ✅ | ✅ |
| `rssi` `rsrp` `rsrq` | 🟡 Signal measurements. Not identifiers, but they refine a cell-level position | ✅ | ✅ | ✅ |
| `access_Technology` `timespan` | Operational only | ✅ | ✅ | ✅ |

> **The finding that changes the shape of D-12.** D-12 names **one** sensitive field —
> IMSI. The schema carries **three classes**: a subscriber identifier (`imsi`), location
> data (`lac`, `cid`, refined by `rssi`/`rsrp`/`rsrq`), and carrier/country
> (`plmn`, `operator`).
>
> They are on the same record, and every record also carries `clsbox`, which is stable
> across all of them. **A policy that masks only IMSI still leaves a per-device location
> trail keyed on `clsbox`, sampled every ten minutes for ninety days.** Re-identifying the
> subscriber from that trail does not require the IMSI. Masking IMSI alone is not a
> null-effect change, but it should not be mistaken for having addressed location data.

### `netstats` — network addresses

Not built yet (T-037). `eth0` and `wwan` are mandatory; any additional interface validates
against the same shape, so this list is open-ended by design (STA-14A).

| Field (per interface) | Class | Storage | API | DLQ |
|---|---|---|---|---|
| `ip` `gw` `mask` `apipa` | 🟠 **Network identifier.** IPv4. On `wwan` this is carrier-assigned and may be personal data when linkable | `jsonb` interface map (ADR-003 D2) | ✅ | ✅ |
| `dns` | 🟠 Resolver configuration — reveals network topology | `jsonb` | ✅ | ✅ |
| `plmn` `plmn_list` | 🟠 Carrier + country; `plmn_list` is the set the modem may attach to | `jsonb` | ✅ | ✅ |
| `tx` `rx` `mtu` `fail_count` `driver` `need_init` `last_forced_reset` `last_powercycle` `timespan` | Operational only | `jsonb` | ✅ | ✅ |

`netstats` is the `jsonb` hybrid, so **field-level masking here means editing a JSON
document, not nulling a column.** That is a different implementation from `ltestats` and
should not be assumed to be the same work.

### `lorastats`, `omsstats`, `sysstats` — operational only

No subscriber, location or network identifier in any of the three. Sensitivity for these
types is carried **entirely by the envelope** (§3).

| Type | Fields | Note |
|---|---|---|
| `lorastats` | `rx_nr_pkts`, `rx_crc_missing`, `rx_crc_bad`, `rx_short_pkt`, `tx_success`, `tx_failed`, `tx_peek`, `tx_peek_index_zero`, `tx_override`, `tx_busy`, `tx_dequeue_error`, `tx_type_beacon`, `chip_comm_error`, `util_fopen_failed`, `fsk_received`, `rx_type_arol`, `timespan` | Radio counters. **BUILT** — the only type with a table today |
| `omsstats` | `rx_nr_pkts`, `rx_valid_nr_pkts`, `rx_crc_bad`, `device_count`, `timespan` | `device_count` is the number of metering devices behind the gateway — commercially meaningful, not personal |
| `sysstats` | `memfree`, `uptime`, `idletime`, `load1`, `load5`, `load15`, `runable`, `temp`, `rtt`, `wan_if`, `rebootcause`, `timespan` | Host health. `rebootcause` is free-form text — a low-likelihood leak vector, worth a look but not a classification question |

---

## 5. Exposure paths no current rule covers

Three gaps found while compiling this. All three are engineering's to fix; none needs a
signature; none is fixed yet.

**1. Stack traces are unclassified.** `DEVELOPMENT.md` §7 forbids *full payloads* above
`DEBUG`. A stack trace is not a payload, and `LorastatsBatchWriter` logs the exception at
`ERROR` on a failed write. A PostgreSQL constraint violation puts the offending key values
in its `DETAIL:` line — so once `ltestats` exists, an insert failure can print an IMSI at
`ERROR`, which is on in production. `openapi.yaml:970` already forbids echoing a SQL error
or stack trace in a *response*; nothing says it about a *log*.

**2. `data_extra` accepts identifiers nobody named.** §2. A new sensitive member Heimdal
starts sending lands there verbatim, and no field-level rule reaches it.

**3. Log retention is undefined, and the log is sometimes the only copy.** `DEVELOPMENT.md`
§7 is explicit that a rejected message exists nowhere else — the log line is the only
record it ever arrived. So log retention cannot simply be minimised for privacy; it trades
directly against the ability to investigate a rejection. That tension is real and belongs
in the D-12 conversation rather than being resolved by whoever configures the log driver.

**Current state today (verified, 4 Sep 2026):** no module logs a payload. `stats-input`
logs no record content; `stats-writer` logs `uid` plus unmapped member **names** at
`DEBUG`, never values. The exposure above is prospective, not present — which is exactly
why now is the cheap moment.

---

## 6. What we need from security and compliance

Five questions. Each is answerable from the tables above.

1. **Classification.** Given §3 and §4, what classification applies to (a) a record with
   operational payload only, (b) an `ltestats` record, (c) a `netstats` record? One label
   each is enough.
2. **IMSI.** Masked or raw — and if masked, **where**: at the Writer on the way in
   (irreversible, and destroys carrier correlation for ops), or at the API on the way out
   (preserves the raw value in the table, the DLQ and any backup)? These are materially
   different systems.
3. **Location.** Do `lac`/`cid` require the same treatment as `imsi`? See the box in §4 —
   this is the question D-12's current wording does not ask.
4. **Log retention and access.** A number, and who may read the log store. Note the
   trade in §5.3: shorter retention loses the only record of a rejected message.
5. **Audit.** What do you need evidence *of* — reads by tenant, redrives, schema changes,
   role grants? We will instrument whatever is named; we cannot guess the list.

**Two items currently filed under D-12 are not security and compliance's**, and moving
them takes them off a queue where they will otherwise sit:

- **Log retention (mechanism)** is a Docker log-driver setting — **platform**, the same
  owner as D-10. Only the *number* and the *access list* above are security's.
- **Encryption at rest** is disk and volume configuration on the managed PostgreSQL —
  **platform**, and it is the same conversation as **D5a** (backup, PITR, RPO/RTO), which
  is already open with product and operations. It should be answered there, once.

---

## 7. Engineering's proposed defaults

Offered so the meeting can accept or reject something concrete. The split is deliberate:
everything reversible is proposed for adoption now; everything irreversible waits for a
signature.

> **UPDATED 4 September 2026 (DL-114).** The three that need no signature are **ADOPTED**:
> **P1** and **P2** are now written into `claude/DEVELOPMENT.md` §7, which is the
> authoritative logging rule for the repository, and **P3** is recorded on **T-034** in the
> tracker as a blocking dependency rather than living only in this table. **P4 and P5 are
> unchanged and still need security's signature** — they are irreversible, and nothing in
> the adoption above pre-decides them. Adopting P1–P3 only narrows what the system does,
> which is why engineering could take them alone.

| # | Proposal | Reversible? | Needs a signature? |
|---|---|---|---|
| P1 | **Never log an `ltestats` or `netstats` payload at any level, including `DEBUG`.** Stricter than `DEVELOPMENT.md` §7 for these two types only | Yes — a config change | **No.** Adopt now; it closes the only pre-T-034 escape path at zero cost |
| P2 | **Log the exception *type*, never a message or stack, on a write failure.** Closes §5.1. **ADOPTED 4 Sep, ENFORCED 5 Sep (T-066, DL-124):** `SafeFailure.describe()` keeps the type chain plus `SQLState`; `WriterLogsCarryNoThrowableTest` fails the build on a throwable in a `stats-writer` log call. **Narrowed in implementation:** "type and message" is not implementable — for a `PSQLException` the `DETAIL:` line *is* part of the message, and an application message is no safer, so no message is logged at all | Yes | **No** |
| P3 | **T-034 does not merge until Q2 and Q3 in §6 are answered.** Not a technical constraint — it is the point after which the answer costs a migration, a DLQ purge ADR-002 §12 forbids, and an API contract change | n/a | **No** — it is a sequencing choice, and it is the whole reason this document exists now |
| P4 | If IMSI is masked: **mask at the Writer**, storing a stable keyed hash so records for one subscriber still correlate | **NO — irreversible** | **YES** |
| P5 | Treat `lac`/`cid` as location data and apply whatever IMSI gets | **NO — irreversible** | **YES** |

---

## 8. Sources

| Fact | Source |
|---|---|
| Field names, types, requiredness | `docs/schemas/{envelope,lorastats,ltestats,sysstats,netstats,omsstats}-v1.schema.json` |
| What the API returns | `docs/contracts/openapi.yaml` — `Envelope`, `LtestatsData`, `NetworkInterface` |
| Columns that exist today | `stats-writer/src/main/resources/db/migration/V1..V4` — `lorastats` only |
| `passthrough` / `data_extra` semantics | DL-094, DL-095, ADR-003 D2, D14b |
| Logging rules | `claude/DEVELOPMENT.md` §7; DL-080, DL-102 |
| DLQ has no TTL, ever | ADR-002 §12 |
| Retention is 90 days on `ts_device` | STA-23, STA-23B, ADR-003 |
| Database roles | ADR-006 |
| D-12's decided half and pending half | `claude/CODE_START_CHECKLIST.md` §A row D-12; `claude/OPEN_QUESTIONS_STATUS_29AUG2026.md` §5.2 |
