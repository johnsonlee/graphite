"""The legacy schema diff accepts only the reviewed optional dialect additions."""
import copy
import importlib.util
import json
import pathlib
import unittest

HERE = pathlib.Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("parity_schema", HERE / "parity_schema.py")
schema = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(schema)


class ReviewedSchemaTest(unittest.TestCase):
    def setUp(self):
        self.rust = json.loads((HERE.parent / "explore/src/openapi.json").read_text())
        self.legacy = schema.without_reviewed_dialect(self.rust)
        self.extra_paths = {"/api/schema", "/api/graphs/{graphId}/schema"}
        for path in self.extra_paths:
            self.legacy["paths"].pop(path, None)

    def pair(self, rust=None):
        return schema.legacy_schema_pair(json.dumps(self.legacy),
                                         json.dumps(self.rust if rust is None else rust), self.extra_paths)

    def test_only_reviewed_additions_are_removed_without_changing_the_input(self):
        before = copy.deepcopy(self.rust)
        legacy, rust = self.pair()
        self.assertEqual(legacy, rust)
        self.assertEqual(self.rust, before)
        self.assertEqual(len(schema._dialect_locations(self.rust)), 9)
        self.assertFalse(schema._dialect_locations(rust))

    def test_missing_duplicate_moved_or_extra_parameters_are_rejected(self):
        path = schema.DIALECT_PATHS[0]
        for mutation in ("missing", "duplicate", "moved", "extra"):
            with self.subTest(mutation=mutation):
                rust = copy.deepcopy(self.rust)
                parameters = rust["paths"][path]["get"]["parameters"]
                parameter = next(p for p in parameters if p.get("name") == "dialect")
                if mutation == "missing":
                    parameters.remove(parameter)
                elif mutation == "duplicate":
                    parameters.append(copy.deepcopy(parameter))
                elif mutation == "moved":
                    parameters.remove(parameter)
                    rust["paths"][path]["delete"] = {"parameters": [parameter]}
                else:
                    rust["paths"]["/unreviewed"] = {"get": {"parameters": [copy.deepcopy(parameter)]}}
                with self.assertRaises(ValueError):
                    self.pair(rust)

    def test_additional_dialect_properties_outside_the_reviewed_locations_are_rejected(self):
        rust = copy.deepcopy(self.rust)
        rust.setdefault("components", {}).setdefault("schemas", {})["Unreviewed"] = {
            "properties": {"dialect": copy.deepcopy(schema.DIALECT_SCHEMA)}
        }
        with self.assertRaises(ValueError):
            self.pair(rust)
        rust = copy.deepcopy(self.rust)
        operation = rust["paths"][schema.DIALECT_PATHS[0]]["get"]
        next(p for p in operation["parameters"] if p.get("name") == "dialect")["description"] = "changed"
        with self.assertRaises(ValueError):
            self.pair(rust)

    def test_every_parameter_and_body_descriptor_is_exact_and_optional(self):
        for path in schema.DIALECT_PATHS:
            for method in ("get", "post"):
                for field, changed in (("type", "integer"), ("enum", ["graphite"]),
                                       ("default", "opencypher-m23")):
                    with self.subTest(path=path, method=method, field=field):
                        rust = copy.deepcopy(self.rust)
                        operation = rust["paths"][path][method]
                        next(p for p in operation["parameters"] if p.get("name") == "dialect")["schema"][field] = changed
                        with self.assertRaises(ValueError):
                            self.pair(rust)
            for field, changed in (("type", "integer"), ("enum", ["graphite"]),
                                   ("default", "opencypher-m23")):
                with self.subTest(body=path, field=field):
                    rust = copy.deepcopy(self.rust)
                    body = rust["paths"][path]["post"]["requestBody"]["content"]["application/json"]["schema"]
                    body["properties"]["dialect"][field] = changed
                    with self.assertRaises(ValueError):
                        self.pair(rust)
            for change in ("missing", "required"):
                with self.subTest(body=path, change=change):
                    rust = copy.deepcopy(self.rust)
                    body = rust["paths"][path]["post"]["requestBody"]["content"]["application/json"]["schema"]
                    if change == "missing":
                        del body["properties"]["dialect"]
                    else:
                        body["required"].append("dialect")
                    with self.assertRaises(ValueError):
                        self.pair(rust)
        rust = copy.deepcopy(self.rust)
        rust["paths"][path]["get"]["parameters"][-1]["required"] = False
        with self.assertRaises(ValueError):
            self.pair(rust)

    def test_unrelated_schema_changes_remain_visible_including_total_and_version_properties(self):
        for name in ("newProperty", "total", "version", "loadedAt"):
            with self.subTest(name=name):
                rust = copy.deepcopy(self.rust)
                body = rust["paths"][schema.DIALECT_PATHS[0]]["post"]["requestBody"]["content"]["application/json"]["schema"]
                body["properties"][name] = {"type": "string"}
                legacy, normalized = self.pair(rust)
                self.assertNotEqual(legacy, normalized)
        rust = copy.deepcopy(self.rust)
        rust["paths"]["/api/cypher"]["get"]["summary"] = "unreviewed change"
        self.assertNotEqual(*self.pair(rust))

    def test_build_version_is_the_only_schema_value_normalized(self):
        self.rust["info"]["version"] = "different-build"
        self.assertEqual(*self.pair())
        self.rust["info"]["version"] = {"changed": "type"}
        with self.assertRaises(ValueError):
            self.pair()

    def test_duplicate_json_keys_are_rejected_before_decoding_can_drop_them(self):
        for raw in ('{"dialect":{},"dialect":{}}',
                    '{"paths":{"/api/cypher":{},"/api/cypher":{}}}'):
            with self.assertRaisesRegex(ValueError, "duplicate OpenAPI key"):
                schema.load_schema(raw)


if __name__ == "__main__":
    unittest.main()
