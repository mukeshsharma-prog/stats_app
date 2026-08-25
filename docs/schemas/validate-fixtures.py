#!/usr/bin/env python3
"""Assert every fixture behaves as its directory claims.

    valid/   and edge/   -> MUST validate against the schema for its stat type
    invalid/             -> MUST fail validation
    malformed/           -> not schema-testable; exercised by the Input endpoint's parser tests

Run from this directory:  python3 validate-fixtures.py
Exit code is non-zero on any mismatch, so CI can gate on it.

Requires: pip install jsonschema
"""
import glob
import json
import sys

from jsonschema import Draft202012Validator
from referencing import Registry, Resource

TYPES = ("sysstats", "lorastats", "ltestats", "omsstats", "netstats")


def load_registry():
    registry, schemas = Registry(), {}
    for path in glob.glob("*.schema.json"):
        schema = json.load(open(path))
        schemas[path] = schema
        resource = Resource.from_contents(schema)
        registry = registry.with_resource(uri=schema["$id"], resource=resource)
        registry = registry.with_resource(uri=path, resource=resource)
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
    for path in sorted(glob.glob("fixtures/**/*.json", recursive=True)):
        if "/malformed/" in path:
            continue
        doc = json.load(open(path))
        expect_valid = ("/valid/" in path or "/edge/" in path) and not path.endswith(
            "validation-failures.json"
        )
        for label, record in records(path, doc):
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

    print(f"\n{checked} assertions checked, {failures} failed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
