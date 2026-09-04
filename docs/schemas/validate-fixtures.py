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
                    marker the lorastats dialect fixtures would sit in invalid/
                    asserting a 400 that will never happen.

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
EXPECTED_ASSERTIONS = 88


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
            if marker not in ("input-reject", "writer-dlq"):
                print(f"FAIL {path}: unknown $expect value {marker!r}")
                failures += 1
            elif not doc.get("$expectCode"):
                print(f"FAIL {path}: $expect declared without a $expectCode")
                failures += 1
            elif marker == "input-reject" and not expect_valid:
                print(f"FAIL {path}: $expect input-reject belongs on a schema-VALID "
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
