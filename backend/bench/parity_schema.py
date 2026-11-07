"""Exact reviewed Rust-only OpenAPI additions for the legacy parity check.

This registry applies only to schema documents. It never filters query responses.
Every descriptor must match before any addition is removed; the caller still compares
all remaining schema content with the legacy document.
"""
import copy
import json

DIALECT_PATHS = (
    "/api/graphs/{graphId}/cypher",
    "/api/cypher/graphs",
    "/api/cypher",
)
DIALECT_SCHEMA = {
    "type": "string",
    "enum": ["graphite", "opencypher-m23"],
    "default": "graphite",
}
DIALECT_PARAMETER = {
    "name": "dialect",
    "in": "query",
    "description": (
        "Query compatibility mode. Graphite preserves established formatting and "
        "UNWIND rebinding extensions; opencypher-m23 selects M23 contracts. "
        "A JSON dialect field takes precedence."
    ),
    "schema": DIALECT_SCHEMA,
}


def load_schema(raw):
    """Duplicate JSON keys are invalid evidence, including duplicate properties."""
    def unique_object(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"duplicate OpenAPI key: {key}")
            result[key] = value
        return result

    document = json.loads(raw, object_pairs_hook=unique_object)
    if not isinstance(document, dict):
        raise ValueError("OpenAPI document must be an object")
    return document


def _dialect_locations(value, path=()):
    locations = set()
    if isinstance(value, dict):
        if value.get("name") == "dialect":
            locations.add(("parameter", path))
        for key, child in value.items():
            if key == "dialect":
                locations.add(("property", path + (key,)))
            locations.update(_dialect_locations(child, path + (key,)))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            locations.update(_dialect_locations(child, path + (index,)))
    return locations


def without_reviewed_dialect(document):
    """Verify all six parameters and three body fields, then remove only those nine."""
    normalized = copy.deepcopy(document)
    reviewed = set()
    parameters_to_remove = []
    properties_to_remove = []
    try:
        for path in DIALECT_PATHS:
            for method in ("get", "post"):
                operation = normalized["paths"][path][method]
                parameters = operation["parameters"]
                if not isinstance(parameters, list):
                    raise ValueError(f"{method} {path}: parameters must be an array")
                dialects = [
                    (index, parameter)
                    for index, parameter in enumerate(parameters)
                    if isinstance(parameter, dict) and parameter.get("name") == "dialect"
                ]
                if len(dialects) != 1 or dialects[0][1] != DIALECT_PARAMETER:
                    raise ValueError(f"{method} {path}: reviewed dialect parameter differs")
                index, _ = dialects[0]
                reviewed.add(("parameter", ("paths", path, method, "parameters", index)))
                parameters_to_remove.append((parameters, index))
                if method == "post":
                    schema_path = ("paths", path, method, "requestBody", "content", "application/json", "schema")
                    schema = operation["requestBody"]["content"]["application/json"]["schema"]
                    properties = schema["properties"]
                    if properties.get("dialect") != DIALECT_SCHEMA:
                        raise ValueError(f"{method} {path}: reviewed dialect body property differs")
                    if "dialect" in schema.get("required", []):
                        raise ValueError(f"{method} {path}: dialect must remain optional")
                    reviewed.add(("property", schema_path + ("properties", "dialect")))
                    properties_to_remove.append(properties)
    except (KeyError, TypeError, AttributeError) as error:
        raise ValueError(f"reviewed dialect schema is missing or malformed: {error}") from error
    if len(reviewed) != 9 or _dialect_locations(normalized) != reviewed:
        raise ValueError("OpenAPI dialect additions differ from the exact six parameters and three body properties")
    for parameters, index in parameters_to_remove:
        del parameters[index]
    for properties in properties_to_remove:
        del properties["dialect"]
    return normalized


def legacy_schema_pair(legacy_raw, rust_raw, rust_only_paths):
    """Only build version and separately checked Rust-only routes precede exact diff."""
    legacy = load_schema(legacy_raw)
    rust = without_reviewed_dialect(load_schema(rust_raw))
    for document in (legacy, rust):
        if isinstance(document.get("info"), dict) and "version" in document["info"]:
            if not isinstance(document["info"]["version"], str):
                raise ValueError("OpenAPI build version must remain a string")
            document["info"]["version"] = "<volatile>"
    for path in rust_only_paths:
        rust.get("paths", {}).pop(path, None)
    return legacy, rust
