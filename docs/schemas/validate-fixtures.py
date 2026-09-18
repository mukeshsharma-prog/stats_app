#!/usr/bin/env python3
"""Assert every fixture behaves as its directory claims.

    valid/   and edge/   -> MUST validate against the schema for its stat type
    invalid/             -> MUST fail validation
    malformed/           -> not schema-testable; exercised by the Input endpoint's parser tests

A fixture may additionally declare "$expect" with a "$expectCode":

    "input-reject"  schema-valid, but rejected by stats-input on a rule the schema
                    cannot express (a cross-field comparison). Q14a's clock-skew
                    fixtures are the case this exists for -- ADR-003 D9 rejects a
                    ts.device more than the configured tolerance ahead of ts.server,
                    and JSON Schema 2020-12 cannot compare two instance locations.

    "writer-dlq"    ACCEPTED by the envelope (so stats-input returns 200 and
                    publishes) but REJECTED by the per-type payload schema (so
                    stats-writer dead-letters it). Added 29 Aug 2026 with ADR-007:
                    the envelope is Input's gate and the payload schema is the
                    Writer's, so "invalid" no longer implies "400". Without this
                    marker the unknown-dialect fixture would sit in invalid/
                    asserting a 400 that will never happen.

    "writer-range-reject"
                    schema-VALID, but dead-lettered by stats-writer on a rule the
                    schema cannot express -- the signed BIGINT range check applied
                    before conversion (DL-105 rule 3, STA-04C). Added 8 Sep 2026
                    with T-063. It is to the Writer what "input-reject" is to
                    Input: both mark a record the catalogue admits and the code
                    refuses, and both exist because the rule is arithmetic or
                    cross-field rather than structural. A string counter is capped
                    at 19 digits by pattern, the BIGINT maximum is
                    9223372036854775807, and no regex expresses the gap -- so the
                    only place the line can be held is the Writer, and this marker
                    is what says so out loud instead of leaving the fixture looking
                    like an ordinary accepted record.

    "writer-skip"   schema-VALID, accepted by Input, published, consumed -- and then
                    deliberately NOT STORED, with the delivery ACKNOWLEDGED. Added
                    10 Sep 2026 with T-026, and the only marker whose record never
                    reaches a queue anyone will look at. ts.device is past the
                    retention window, so no partition exists to hold it (STA-23B,
                    ADR-003 option B, signed as D5b -> DL-112): the Writer skips the
                    write, increments records_expired_on_arrival{type}, logs at WARN
                    and ACKs.

                    It is NOT "writer-dlq", and the distinction is the whole reason
                    this marker exists. The record is valid, the DLQ carries no TTL
                    (ADR-002 Section 12), and a redrive re-derives the identical
                    verdict forever because the record can only have got OLDER --
                    so a dead-letter here would be a message that is rejected on
                    every attempt until a human deletes it. Before this marker these
                    five fixtures carried no marker at all, which meant they
                    silently asserted "the schema accepts this" and said nothing
                    about the decided behaviour -- the same gap D9's clock-skew
                    fixtures had before "input-reject" existed.

Without a marker an edge/ fixture silently asserts "accepted", which is how an
undecided question ended up with a default answer baked into ten green assertions.

Run from this directory:  python3 validate-fixtures.py
Exit code is non-zero on any mismatch, so CI can gate on it.

Requires: pip install jsonschema
"""
import json
import sys
from pathlib import Path

from jsonschema import Draft202012Validator
from referencing import Registry, Resource

BASE_DIR = Path(__file__).resolve().parent

TYPES = ("sysstats", "lorastats", "ltestats", "omsstats", "netstats")

# The gate asserts the COUNT, not just that whatever it found happened to pass.
# Without this, deleting fixtures is invisible: `checked` simply drops and CI stays
# green, which would have hidden exactly the regression the 29 Aug 2026 production
# sample caught. Changing this number is a deliberate act that belongs in the same
# commit as the fixture change, with the reason in claude/DECISIONS.md.
EXPECTED_ASSERTIONS = 103


def load_registry():
    registry, schemas = Registry(), {}
    for path in sorted(BASE_DIR.glob("*.schema.json")):
        schema = json.load(open(path, encoding="utf-8"))
        name = path.name
        schemas[name] = schema
        resource = Resource.from_contents(schema)
        registry = registry.with_resource(uri=schema["$id"], resource=resource)
        registry = registry.with_resource(uri=name, resource=resource)
    return registry, schemas


def records(path, doc):
    """A fixture file is one record, a named bundle, or an isolation pair."""
    if path.endswith(("approved-types.json", "validation-failures.json")):
        return list(doc.items())
    if "tenant-isolation-pair" in path:
        return [("record_a", doc["record_a"]), ("record_b", doc["record_b"])]
    return [("", {k: v for k, v in doc.items() if not k.startswith("$")})]


def main():
    registry, schemas = load_registry()
    validators = {
        t: Draft202012Validator(schemas[f"{t}-v1.schema.json"], registry=registry) for t in TYPES
    }
    envelope = Draft202012Validator(schemas["envelope-v1.schema.json"], registry=registry)

    checked = failures = 0
    input_rejects = []
    writer_dlqs = []
    writer_range_rejects = []
    writer_skips = []
    for path in sorted(BASE_DIR.glob("fixtures/**/*.json")):
        path_str = str(path)
        if "/malformed/" in path_str:
            continue
        doc = json.load(open(path, encoding="utf-8"))
        expect_valid = ("/valid/" in path_str or "/edge/" in path_str) and not path.name.endswith(
            "validation-failures.json"
        )
        marker = doc.get("$expect") if isinstance(doc, dict) else None
        if marker is not None:
            checked += 1
            if marker not in ("input-reject", "writer-dlq", "writer-range-reject",
                              "writer-skip"):
                print(f"FAIL {path}: unknown $expect value {marker!r}")
                failures += 1
            elif not doc.get("$expectCode"):
                print(f"FAIL {path}: $expect declared without a $expectCode")
                failures += 1
            elif (marker in ("input-reject", "writer-range-reject", "writer-skip")
                  and not expect_valid):
                print(f"FAIL {path}: $expect {marker} belongs on a schema-VALID "
                      f"fixture; this one is expected to fail validation")
                failures += 1
            elif marker == "writer-dlq" and expect_valid:
                print(f"FAIL {path}: $expect writer-dlq belongs on a fixture that FAILS "
                      f"its per-type schema; this one is expected to validate")
                failures += 1
            elif marker == "writer-dlq":
                # The whole point of the marker: Input must ACCEPT it. If the envelope
                # rejects it too, the record 400s and never reaches a queue, so calling
                # it a dead-letter case would be a lie.
                envelope_errors = list(envelope.iter_errors(
                    {k: v for k, v in doc.items() if not k.startswith("$")}))
                if envelope_errors:
                    print(f"FAIL {path}: $expect writer-dlq requires the ENVELOPE to accept "
                          f"the record (Input returns 200 and publishes), but it does not:")
                    for e in envelope_errors[:3]:
                        print(f"       {'.'.join(map(str, e.path)) or '<root>'}: {e.message}")
                    failures += 1
                else:
                    writer_dlqs.append((path_str, doc["$expectCode"]))
            elif marker == "writer-range-reject":
                writer_range_rejects.append((path_str, doc["$expectCode"]))
            elif marker == "writer-skip":
                # Input must ACCEPT it, exactly as for writer-dlq: the record is
                # published and consumed, and only then skipped. If the envelope
                # rejected it the record would 400 and never reach the Writer at
                # all, and calling it a skip would be a lie.
                envelope_errors = list(envelope.iter_errors(
                    {k: v for k, v in doc.items() if not k.startswith("$")}))
                if envelope_errors:
                    print(f"FAIL {path}: $expect writer-skip requires the ENVELOPE to "
                          f"accept the record (Input returns 200 and publishes), but it "
                          f"does not:")
                    for e in envelope_errors[:3]:
                        print(f"       {'.'.join(map(str, e.path)) or '<root>'}: {e.message}")
                    failures += 1
                else:
                    writer_skips.append((path_str, doc["$expectCode"]))
            else:
                input_rejects.append((path_str, doc["$expectCode"]))
        for label, record in records(path_str, doc):
            stat_type = record.get("type")
            if stat_type not in TYPES:
                # Only legitimate in an invalid fixture testing the unknown type itself.
                # Assert it rather than assuming it: the envelope's `type` enum is what
                # makes an unrecognised type a 400 at validation instead of a surprise
                # further down the routing path. Counting this as a pass without
                # validating would leave the enum untested.
                checked += 1
                if expect_valid:
                    print(f"FAIL {path} {label}: unknown stat type {stat_type!r}")
                    failures += 1
                elif not list(envelope.iter_errors(record)):
                    print(f"FAIL {path} {label}: stat type {stat_type!r} passed the "
                          f"envelope; the `type` enum is not rejecting it")
                    failures += 1
                continue
            errors = list(validators[stat_type].iter_errors(record))
            checked += 1
            if bool(errors) == expect_valid:
                failures += 1
                want = "VALID" if expect_valid else "INVALID"
                print(f"FAIL {path} {label}: expected {want}")
                for e in errors[:3]:
                    print(f"       {'.'.join(map(str, e.path)) or '<root>'}: {e.message}")

    if input_rejects:
        print(f"\n{len(input_rejects)} fixture(s) are schema-valid but must be rejected "
              f"by stats-input:")
        for path, code in input_rejects:
            print(f"       {path}  ->  400 {code}")
    if writer_dlqs:
        print(f"\n{len(writer_dlqs)} fixture(s) pass the envelope but must be dead-lettered "
              f"by stats-writer (Input returns 200 and publishes them):")
        for path, code in writer_dlqs:
            print(f"       {path}  ->  DLQ {code}")
    if writer_range_rejects:
        print(f"\n{len(writer_range_rejects)} fixture(s) are schema-valid but must be "
              f"dead-lettered by stats-writer's range check, before conversion:")
        for path, code in writer_range_rejects:
            print(f"       {path}  ->  DLQ {code}")
    if writer_skips:
        print(f"\n{len(writer_skips)} fixture(s) are schema-valid and accepted by Input, "
              f"and must then be SKIPPED by stats-writer -- not stored, not "
              f"dead-lettered, and ACKED:")
        for path, code in writer_skips:
            print(f"       {path}  ->  skip {code}")
    print(f"\n{checked} assertions checked, {failures} failed")

    if checked != EXPECTED_ASSERTIONS:
        print(
            f"FAIL: expected {EXPECTED_ASSERTIONS} assertions but ran {checked}. "
            f"Fixtures were added or removed. If that was intended, update "
            f"EXPECTED_ASSERTIONS in this file and say why in the same commit; "
            f"if it was not, a fixture has been lost and the gate just caught it."
        )
        failures += 1

    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
