#!/usr/bin/env python3
"""Fail when OpenAPI's per-type data schemas drift from validation schemas.

The JSON Schema catalogue describes the accepted ingest wire format. OpenAPI
describes the data returned by the read API. Three types map directly. Lorastats
maps three accepted wire dialects onto one canonical response shape. Netstats
uses an explicit extension to retain the authoritative interface-name pattern
while representing patternProperties with broadly supported OpenAPI keywords.
"""

import argparse
import copy
import json
import sys
from pathlib import Path

import yaml


CONTRACT_DIR = Path(__file__).resolve().parent
DEFAULT_OPENAPI = CONTRACT_DIR / "openapi.yaml"
DEFAULT_SCHEMA_DIR = CONTRACT_DIR.parent / "schemas"

TYPE_COMPONENTS = {
    "lorastats": "LorastatsData",
    "ltestats": "LtestatsData",
    "sysstats": "SysstatsData",
    "omsstats": "OmsstatsData",
    "netstats": "NetstatsData",
}

IGNORED_SCHEMA_KEYS = {
    "$comment",
    "default",
    "deprecated",
    "description",
    "example",
    "examples",
    "format",
    "readOnly",
    "title",
    "writeOnly",
}

NETSTATS_PATTERN_EXTENSION = "x-interface-name-pattern"


def load_documents(openapi_path=DEFAULT_OPENAPI, schema_dir=DEFAULT_SCHEMA_DIR):
    with Path(openapi_path).open(encoding="utf-8") as stream:
        openapi = yaml.safe_load(stream)
    schemas = {}
    for stat_type in TYPE_COMPONENTS:
        path = Path(schema_dir) / f"{stat_type}-v1.schema.json"
        with path.open(encoding="utf-8") as stream:
            schemas[stat_type] = json.load(stream)
    return openapi, schemas


def resolve_pointer(document, reference):
    if not reference.startswith("#/"):
        raise ValueError(f"only local references can be compared, found {reference!r}")
    value = document
    for token in reference[2:].split("/"):
        token = token.replace("~1", "/").replace("~0", "~")
        value = value[token]
    return value


def normalize_schema(schema, document, references=()):
    """Resolve local refs and retain only validation-relevant schema keywords."""
    if isinstance(schema, list):
        normalized = [normalize_schema(value, document, references) for value in schema]
        return sorted(normalized, key=lambda value: json.dumps(value, sort_keys=True))
    if not isinstance(schema, dict):
        return schema

    if "$ref" in schema:
        reference = schema["$ref"]
        if reference in references:
            raise ValueError(f"cyclic local reference {reference!r}")
        validation_siblings = {
            key: value
            for key, value in schema.items()
            if key != "$ref" and key not in IGNORED_SCHEMA_KEYS
        }
        if validation_siblings:
            raise ValueError(
                f"reference {reference!r} has validation siblings that require an explicit rule"
            )
        return normalize_schema(
            resolve_pointer(document, reference), document, references + (reference,)
        )

    meaningful = {
        key: value
        for key, value in schema.items()
        if key not in IGNORED_SCHEMA_KEYS
        and key != NETSTATS_PATTERN_EXTENSION
        and not key.startswith("x-")
    }
    if set(meaningful) == {"allOf"} and len(meaningful["allOf"]) == 1:
        return normalize_schema(meaningful["allOf"][0], document, references)

    normalized = {}
    for key, value in meaningful.items():
        if key in {"required", "enum"} and isinstance(value, list):
            normalized[key] = sorted(value)
        elif key in {"properties", "patternProperties", "$defs"}:
            normalized[key] = {
                name: normalize_schema(child, document, references)
                for name, child in sorted(value.items())
            }
        else:
            normalized[key] = normalize_schema(value, document, references)
    return dict(sorted(normalized.items()))


def source_data_schema(schema):
    try:
        return schema["allOf"][1]["properties"]["data"]
    except (KeyError, IndexError, TypeError) as failure:
        raise ValueError("per-type schema does not expose allOf[1].properties.data") from failure


def canonical_lorastats_projection(schema):
    data = source_data_schema(schema)
    refs = data.get("oneOf", [])
    expected_refs = {
        "#/$defs/dialectBareInteger",
        "#/$defs/dialectBareString",
        "#/$defs/dialectPrefixedInteger",
    }
    actual_refs = {branch.get("$ref") for branch in refs if isinstance(branch, dict)}
    if actual_refs != expected_refs or len(refs) != 3:
        raise ValueError(
            "lorastats must remain a closed three-dialect enumeration; "
            f"expected {sorted(expected_refs)}, found {sorted(str(value) for value in actual_refs)}"
        )

    branches = {
        name: schema["$defs"][name]
        for name in ("dialectBareInteger", "dialectBareString", "dialectPrefixedInteger")
    }

    def canonical_name(name):
        return name.removeprefix("lora_")

    def names(branch, key):
        values = branch.get(key, {})
        if isinstance(values, list):
            return {canonical_name(value) for value in values}
        return {canonical_name(value) for value in values}

    expected_properties = names(branches["dialectBareInteger"], "properties")
    expected_required = names(branches["dialectBareInteger"], "required")
    for name, branch in branches.items():
        if names(branch, "properties") != expected_properties:
            raise ValueError(
                f"lorastats {name} no longer projects to the same canonical property set"
            )
        if names(branch, "required") != expected_required:
            raise ValueError(
                f"lorastats {name} no longer projects to the same canonical required set"
            )

    expected = copy.deepcopy(branches["dialectBareInteger"])
    expected.pop("title", None)
    return expected


def canonical_netstats_projection(schema, openapi_component):
    data = copy.deepcopy(source_data_schema(schema))
    patterns = data.pop("patternProperties", {})
    if len(patterns) != 1:
        raise ValueError(
            "netstats must expose exactly one authoritative interface pattern, "
            f"found {len(patterns)}"
        )
    if data.get("additionalProperties") is not True:
        raise ValueError(
            "netstats projection assumes unmatched data members remain accepted"
        )
    pattern, interface_schema = next(iter(patterns.items()))
    declared_pattern = openapi_component.get(NETSTATS_PATTERN_EXTENSION)
    if declared_pattern != pattern:
        raise ValueError(
            f"NetstatsData.{NETSTATS_PATTERN_EXTENSION} expected {pattern!r}, "
            f"found {declared_pattern!r}"
        )
    data["additionalProperties"] = interface_schema
    return data


def differences(expected, actual, path):
    errors = []
    if isinstance(expected, dict) and isinstance(actual, dict):
        for key in sorted(expected.keys() - actual.keys()):
            errors.append(f"{path}.{key}: missing from OpenAPI")
        for key in sorted(actual.keys() - expected.keys()):
            errors.append(f"{path}.{key}: present only in OpenAPI")
        for key in sorted(expected.keys() & actual.keys()):
            errors.extend(differences(expected[key], actual[key], f"{path}.{key}"))
        return errors
    if expected != actual:
        errors.append(f"{path}: expected {expected!r}, found {actual!r}")
    return errors


def check_documents(openapi, schemas):
    errors = []
    components = openapi.get("components", {}).get("schemas", {})
    expected_components = set(TYPE_COMPONENTS.values())
    missing_components = expected_components - components.keys()
    if missing_components:
        errors.append(
            "OpenAPI is missing per-type components: " + ", ".join(sorted(missing_components))
        )

    for stat_type, component_name in TYPE_COMPONENTS.items():
        if component_name not in components or stat_type not in schemas:
            continue
        source = schemas[stat_type]
        component = components[component_name]
        try:
            if stat_type == "lorastats":
                projected = canonical_lorastats_projection(source)
            elif stat_type == "netstats":
                projected = canonical_netstats_projection(source, component)
            else:
                projected = source_data_schema(source)
            expected = normalize_schema(projected, source)
            actual = normalize_schema(component, openapi)
            errors.extend(differences(expected, actual, component_name))
        except (KeyError, TypeError, ValueError) as failure:
            errors.append(f"{component_name}: {failure}")
    return errors


def parse_args(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--openapi", type=Path, default=DEFAULT_OPENAPI)
    parser.add_argument("--schema-dir", type=Path, default=DEFAULT_SCHEMA_DIR)
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    try:
        openapi, schemas = load_documents(args.openapi, args.schema_dir)
        errors = check_documents(openapi, schemas)
    except (OSError, json.JSONDecodeError, yaml.YAMLError, ValueError) as failure:
        print(f"OpenAPI schema drift check could not run: {failure}", file=sys.stderr)
        return 2

    if errors:
        print(f"OpenAPI schema drift check FAILED with {len(errors)} difference(s):")
        for error in errors:
            print(f"  - {error}")
        return 1

    print(
        f"OpenAPI schema drift check passed: {len(TYPE_COMPONENTS)} per-type data "
        "schemas agree with the authoritative validation catalogue"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
