# graphite-schema

English | [简体中文](README.zh.md)

`graphite-schema` is Graphite's Rust library for **describing and exchanging graph
records without hardcoding a programming language's node or type system**. It
provides the shared data model, structural validation, and binary encoding/decoding.

A frontend can describe a new node kind or type operator in the data it emits. A
reader built before that definition existed can still inspect its fields, follow
its references, merge it with another document, and save it without losing the
unknown information. This is the foundation for adding languages without changing
the common codec for each language.

## Where it fits

The intended pipeline is:

```text
Language frontend → schema records + definitions → Rust indexer → production graph
                            ↑
                     graphite-schema
```

This crate implements the schema records and their standalone interchange codec.
Frontend emission and production indexer integration are still pending. The current
JVM build, v1–v3 graph loader, and Cypher engine do not use this crate yet.

For example, JVM `List<User>` and Swift `Array<User>` can both be represented as a
type application with explicit references to their own declarations and arguments.
A future language can add a different operator by registering another definition.
The codec preserves those structures; a language frontend or analyzer supplies
the rules that determine whether two types are compatible.

## What it provides

| API | Purpose |
| --- | --- |
| `Document`, `Definition`, `Field`, `Descriptor` | Define named layouts, fields, tables, profiles, and a string dictionary |
| `Record`, `Value`, `Reference` | Represent values and explicit references, including nested records and cycles through references |
| `Document::validate` | Check layouts, field types, required/null rules, reference targets, and resource limits |
| `encode` / `decode` | Write/read a deterministic, checksummed `GSCHEMA` wire-version-1 container |
| `Document::record`, `field`, `records_of` | Inspect records structurally, even when their semantic names are unfamiliar |
| `Document::remap` | Rewrite every local ID, including references inside nested lists and records |
| `Document::merge` | Combine documents with overlapping IDs while retaining distinct rows and rejecting conflicting definitions |

Definitions have qualified names and revisions. Node kinds, operators, predicates,
and roles are data, not language-specific Rust enums. The finite `Descriptor` enum
covers physical value forms such as integers, strings, lists, records, and references.

The codec preserves absent fields separately from explicit nulls, exact floating-point
bits, ordered/repeated list entries, and separately addressed parallel relations.
Local IDs are addresses, not semantic identity: merging two declarations both named
`T` does not merge their rows or scopes.

## Quick start

This crate is currently developed in the Graphite workspace. A sibling crate under
`backend/` can depend on it with the following entry in its `Cargo.toml`; adjust the
path for other locations. This example does not assume a crates.io release.

```toml
[dependencies]
graphite-schema = { path = "../schema" }
```

The following complete example creates a previously unknown type operator, encodes
and decodes it, then reads its field without installing a language plugin. The schema
definition and string dictionary travel with the record.

```rust
use graphite_schema::*;
use std::collections::BTreeMap;

fn main() -> Result<()> {
    let name = |local| QualifiedName::new("example.future", local);
    let document = Document {
        strings: BTreeMap::from([(0, "region-local".into())]),
        definitions: BTreeMap::from([(7, Definition {
            name: name("region-type"),
            revision: "1".into(),
            category: QualifiedName::new("core", "expression"),
            fields: vec![Field {
                name: name("mode"),
                descriptor: Descriptor::String,
                required: true,
                nullable: false,
                role: None,
            }],
        })]),
        tables: BTreeMap::from([(3, Table {
            name: name("types"),
            rows: BTreeMap::from([(42, Record {
                layout: 7,
                fields: vec![Some(Value::String(0))],
            })]),
        })]),
        ..Document::default()
    };

    let limits = Limits::default();
    let bytes = encode(&document, &limits)?;
    let restored = decode(&bytes, &limits)?;
    let record = restored.record(Reference { table: 3, row: 42 }).unwrap();
    let Some(Value::String(id)) = restored.field(record, &name("mode")) else {
        panic!("mode must reference the string dictionary");
    };
    assert_eq!(restored.strings[id], "region-local");
    assert_eq!(restored, document);
    Ok(())
}
```

Use `IdMap` when IDs need to change, rather than editing numeric values by hand.
`remap` requires complete, collision-free mappings for strings, layouts, tables, and
rows; `merge` allocates the mappings for a structural union. All local references
must be explicit `Reference` values. Hiding IDs inside strings or byte blobs prevents
generic remapping and violates the producer contract.

## Validate changes

Run these commands from the repository root:

```bash
cargo test -p graphite-schema --locked
cargo clippy -p graphite-schema --all-targets --locked -- -D warnings
cargo doc -p graphite-schema --no-deps --open
```

This README is also the crate's Rust API introduction, so its Rust example is compiled
and executed by `cargo test`. The integration suites cover:

- [Unknown extensions](tests/unknown_extensions.rs): 17 unfamiliar node kinds,
  257 predicates, scoped bindings, nested/cyclic references, full ID remapping, and
  merges with overlapping IDs. Assertions check logical targets and complete values.
- [Wire validation](tests/wire_validation.rs): independently assembled binary fixtures,
  exact value encodings, checksummed malformed data, truncation, and resource limits.
- [Model tests](src/model.rs): schema conflicts, invalid references, mapping errors,
  sparse ID allocation, and matching encode/decode budget boundaries.

## Current limits

This is an in-memory logical-document codec, not a graph database, parser, type solver,
Cypher engine, or the final mmap/columnar graph storage format. `GSCHEMA` wire version 1
is independent of persisted graph versions; these bytes cannot be passed to the
existing graph loader. Cross-language implementations can follow the same contract,
but cross-language interoperability tests have not yet been added.

Default limits are 64 MiB of encoded bytes, 1,000,000 aggregate items, and inline depth
64; the hard maximum depth is 256. Reference cycles are allowed without recursive
expansion. The byte budget does not bound total RSS to the same number. Synthetic
tests establish correctness, not throughput or 100M-node capacity. Production-scale
measurements require subsequent frontend/indexer integration with real corpora.

See the [binary contract](../../docs/schema-wire.md) for exact encoding, validation,
and versioning rules, the [universal schema proposal](../../docs/graph-schema.md) for
the broader architecture, and the [JVM migration plan](../../docs/jvm-generic-types.md)
for the later frontend and corpus work.
