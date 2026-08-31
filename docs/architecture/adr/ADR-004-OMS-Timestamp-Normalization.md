# ADR-004 — OMS Timestamp Normalisation

**Status:** `ACCEPTED — amended 29 August 2026 by ADR-007 §2`
**Decision owner:** Heimdal Core owner / Engineering lead
**Requirements:** STA-01A, STA-02, STA-03A, STA-04A, STA-21A, STA-23B

> **Numbering warning (G5).** `Datahub_Core_StatsApp_ARD_v1_0.pdf` also carries an
> ADR-004, and it means something entirely different — at-least-once delivery,
> which in this repository is ADR-005. When citing the ARD anywhere, write it as
> **`ARD-ADR-04`**. An unqualified "ADR-004" always means this file.

> **Amendment, 29 August 2026 (ADR-007 §2). Read this before the Decision below.**
> Point 3 as originally written — "a record carrying `ts.converter` is **invalid**"
> — **is withdrawn.** Production sends `ts.converter` on 100% of records, so that
> clause rejected every record Heimdal sends. The field is now accepted, preserved,
> returned verbatim, never read, and metered as `records_converter_ts_present{type}`.
>
> **Everything else in this ADR stands, and the core decision is unchanged: the
> Stats App performs no timestamp normalisation.** What was wrong was never the
> decision — only the enforcement clause bolted onto it. The rule was written to
> prevent us performing a normalisation step, and it was implemented as a rejection
> of *evidence that one had happened upstream*. Those are not the same thing, and
> conflating them cost 100% of ingest.

## Context

The v1.0 SRS describes an `omsstats`-specific `ts.converter` field and implies the
Stats App must normalise it into the common envelope. That description is out of
date. Heimdal already emits the canonical `ts.server` for `omsstats`, exactly as
it does for every other stat type, so there is nothing left to normalise.

## Decision

The Stats App performs **no timestamp normalisation**, for `omsstats` or any other
type. The message published to RabbitMQ is byte-identical to the record received
over HTTP.

1. `omsstats` uses the same envelope as every other type: `ts.server` and
   `ts.device`, both **Unix timestamps in seconds**.
2. There is no `ts.converter` handling in `stats-input`, `stats-writer`,
   `stats-api`, or `stats-common`. Not a mapper, not a fallback, not a tolerated
   alias.
3. ~~A record carrying `ts.converter` is **invalid** and is rejected with HTTP 400
   like any other schema violation (STA-03A). Nothing is published.~~
   **WITHDRAWN 29 Aug 2026 (ADR-007 §2).** A record carrying `ts.converter` is
   **valid**. The member is stored and returned verbatim and is never read. Its
   presence is metered as `records_converter_ts_present{type}`.
4. Reopening the *normalisation* decision requires a new approved compatibility
   ADR. Adding a `ts.converter`-driven transformation as a "harmless"
   backward-compatibility gesture is still explicitly out of scope — accepting the
   field is not the same as acting on it.

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
`additionalProperties: true`. It **used to** carry `"not": {"required":
["converter"]}` so that a `ts.converter` member failed validation while other
unknown members passed; that asymmetry was removed on 29 Aug 2026 (ADR-007 §2)
because production sends the field on every record. `converter`, `datastore` and
`expiresOn` are now declared members, typed, documented as never-read, and
accepted.

The reasoning that produced the withdrawn rule is worth keeping, because it is the
generalisable lesson: `ts.converter` was treated as evidence that the sender
believes in a normalisation step this system does not perform. That reading was
correct about the *sender* and wrong about the *remedy*. Heimdal does run a
converter — `status: ["conversion ok"]` says so on every record — and the Stats App
still performs no normalisation of its own. Rejecting the record punished us for
upstream behaviour we had already decided to ignore.

## Required tests

* An `omsstats` record carrying `ts.server` is accepted and published
  unchanged — assert byte equality between the HTTP body and the AMQP payload,
  not merely field equivalence.
* An `omsstats` record carrying `ts.converter` is **accepted**, and the member
  survives a round trip through the typed view unchanged. Fixture:
  `docs/schemas/fixtures/valid/omsstats-ts-converter-present.json` — which moved
  from `invalid/` to `valid/` on 29 Aug 2026 and now also carries `ts.datastore`,
  `ts.expiresOn`, `version` and `status`, making it the regression guard for the
  whole envelope shape. Guarded in Java by
  `StatsSchemaValidatorTest.acceptsTheLegacyConverterTimestampThatUsedToBeRejected`.
* Timestamps are interpreted as seconds, not milliseconds. Fixture:
  `docs/schemas/fixtures/invalid/envelope-ts-in-milliseconds.json`. A
  millisecond value read as seconds silently produces a date far in the future;
  read the other way it produces 1970.
* No transformation occurs between HTTP input and AMQP publication for any type.

## Decisions register

| Decision | Resolution | Status |
|---|---|---|
| OMS timestamp normalisation | None — Heimdal sends canonical `ts.server` | **DECIDED** |
| `ts.converter` handling | Accepted, preserved, never read, metered | **DECIDED — amended 29 Aug 2026 (ADR-007 §2)** |
| `ts.datastore` / `ts.expiresOn` handling | Accepted, preserved, never read | **DECIDED 29 Aug 2026 (ADR-007 §2)** |
| Timestamp unit | Unix seconds for both `ts.server` and `ts.device` | **DECIDED** |
| Publication fidelity | Byte-identical to the received record | **DECIDED** |
