# ADR-004 — OMS Timestamp Normalisation

**Status:** `ACCEPTED`
**Decision owner:** Heimdal Core owner / Engineering lead
**Requirements:** STA-01A, STA-02, STA-03A, STA-04A, STA-21A, STA-23B

> **Numbering warning (G5).** `Datahub_Core_StatsApp_ARD_v1_0.pdf` also carries an
> ADR-004, and it means something entirely different — at-least-once delivery,
> which in this repository is ADR-005. When citing the ARD anywhere, write it as
> **`ARD-ADR-04`**. An unqualified "ADR-004" always means this file.

## Context

The v1.0 SRS describes an `omsstats`-specific `ts.converter` field and implies the
Stats App must normalise it into the common envelope. That description is out of
date. Heimdal already emits the canonical `ts.processor` for `omsstats`, exactly as
it does for every other stat type, so there is nothing left to normalise.

## Decision

The Stats App performs **no timestamp normalisation**, for `omsstats` or any other
type. The message published to RabbitMQ is byte-identical to the record received
over HTTP.

1. `omsstats` uses the same envelope as every other type: `ts.processor` and
   `ts.device`, both **Unix timestamps in seconds**.
2. There is no `ts.converter` handling in `stats-input`, `stats-writer`,
   `stats-api`, or `stats-common`. Not a mapper, not a fallback, not a tolerated
   alias.
3. A record carrying `ts.converter` is **invalid** and is rejected with HTTP 400
   like any other schema violation (STA-03A). Nothing is published.
4. Reopening this requires a new approved compatibility ADR. Adding
   `ts.converter` support as a "harmless" backward-compatibility gesture is
   explicitly out of scope.

## Why byte-identical publication matters beyond this ADR

This decision is load-bearing for ADR-005. Because the published message is
identical to the received record, a redelivered or replayed record carries the
same `ts_device`, lands in the same daily partition, and collides with the same
`(ts_device, uid)` primary key. If the Input Module ever rewrote a timestamp, the
per-partition duplicate suppression in ADR-003 would stop working and the failure
would be silent. Any future proposal to transform a record in flight must be
checked against that consequence first.

## Consequences

The `omsstats` section of `Datahub_Core_StatsApp_SRS_v1_0_EN.docx` is historical
context only and must not be implemented as written.

`docs/schemas/envelope-v1.schema.json` encodes this decision directly. `ts` keeps
`additionalProperties: true` but adds `"not": {"required": ["converter"]}`, so a
`ts.converter` member fails validation while other unknown `ts` members are still
tolerated under the D14 unknown-field policy. That asymmetry is deliberate:
`ts.converter` is not merely unrecognised, it is a field whose presence means the
sender believes in a normalisation step this system does not perform.

## Required tests

* An `omsstats` record carrying `ts.processor` is accepted and published
  unchanged — assert byte equality between the HTTP body and the AMQP payload,
  not merely field equivalence.
* An `omsstats` record carrying `ts.converter` is rejected with 400 and nothing is
  published. Fixture: `docs/schemas/fixtures/invalid/envelope-oms-ts-converter.json`.
* Timestamps are interpreted as seconds, not milliseconds. Fixture:
  `docs/schemas/fixtures/invalid/envelope-ts-in-milliseconds.json`. A
  millisecond value read as seconds silently produces a date far in the future;
  read the other way it produces 1970.
* No transformation occurs between HTTP input and AMQP publication for any type.

## Decisions register

| Decision | Resolution | Status |
|---|---|---|
| OMS timestamp normalisation | None — Heimdal sends canonical `ts.processor` | **DECIDED** |
| `ts.converter` handling | None; such a record is invalid (400) | **DECIDED** |
| Timestamp unit | Unix seconds for both `ts.processor` and `ts.device` | **DECIDED** |
| Publication fidelity | Byte-identical to the received record | **DECIDED** |
