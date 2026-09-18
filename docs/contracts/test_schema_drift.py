#!/usr/bin/env python3
"""Regression tests proving the OpenAPI schema drift gate can fail."""

import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import check_schema_drift as drift


class SchemaDriftTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.openapi, cls.schemas = drift.load_documents()

    def test_repository_contracts_match(self):
        self.assertEqual([], drift.check_documents(self.openapi, self.schemas))

    def test_every_per_type_component_is_guarded(self):
        for component_name in drift.TYPE_COMPONENTS.values():
            with self.subTest(component=component_name):
                changed = copy.deepcopy(self.openapi)
                changed["components"]["schemas"][component_name].setdefault(
                    "required", []
                ).append("field_added_only_to_openapi")
                errors = drift.check_documents(changed, self.schemas)
                self.assertTrue(
                    any(component_name in error for error in errors),
                    f"changing {component_name} did not fail the drift gate",
                )

    def test_authoritative_validation_change_is_guarded(self):
        changed = copy.deepcopy(self.schemas)
        data = drift.source_data_schema(changed["sysstats"])
        data["properties"]["runable"]["pattern"] = "^[0-9]+$"

        errors = drift.check_documents(self.openapi, changed)

        self.assertTrue(any("SysstatsData.properties.runable.pattern" in error for error in errors))

    def test_ltestats_shape_change_is_guarded(self):
        # Since DL-146 ltestats data is a closed oneOf of two shapes held in $defs, so
        # a validation change lands inside a branch, and the gate reports it at the
        # oneOf rather than at a property path. The point is that it is reported.
        changed = copy.deepcopy(self.schemas)
        changed["ltestats"]["$defs"]["shapeMeasured"]["properties"]["imsi"]["pattern"] = "^[0-9]{15}$"

        errors = drift.check_documents(self.openapi, changed)

        self.assertTrue(any("LtestatsData.oneOf" in error for error in errors))

        changed = copy.deepcopy(self.schemas)
        changed["ltestats"]["$defs"]["shapeUnknownTechnology"]["additionalProperties"] = True

        errors = drift.check_documents(self.openapi, changed)

        self.assertTrue(any("LtestatsData.oneOf" in error for error in errors))

    def test_lorastats_dialects_must_keep_one_canonical_property_set(self):
        changed = copy.deepcopy(self.schemas)
        changed["lorastats"]["$defs"]["dialectBareString"]["properties"][
            "string_dialect_only"
        ] = {"type": "string"}

        errors = drift.check_documents(self.openapi, changed)

        self.assertTrue(
            any("no longer projects to the same canonical property set" in error for error in errors)
        )

    def test_netstats_interface_name_pattern_is_guarded(self):
        changed = copy.deepcopy(self.openapi)
        changed["components"]["schemas"]["NetstatsData"][
            drift.NETSTATS_PATTERN_EXTENSION
        ] = "^different$"

        errors = drift.check_documents(changed, self.schemas)

        self.assertTrue(any(drift.NETSTATS_PATTERN_EXTENSION in error for error in errors))


if __name__ == "__main__":
    unittest.main()
