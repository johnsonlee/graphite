# graphite-schema

English | [简体中文](README.zh.md)

`graphite-schema` is Graphite's Rust library for **describing and exchanging graph
records without hardcoding a programming language's node or type system**. It
provides the shared data model, structural validation, binary encoding/decoding, and
a memory-mapped reader for indexed schema records.

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

This crate implements schema records, a standalone interchange codec, and an indexed
format with memory-mapped, on-demand reads. Frontend emission and production indexer
integration are still pending. The current
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
| `encode` / `decode` | Write/read a complete in-memory document using `GSCHEMA/1` |
| `encode_mapped` | Write indexed `GSCHEMA/2` bytes from a validated document |
| `MappedDocument::from_bytes` / `open` | Read indexed bytes or mmap a file without decoding all records and strings |
| `MappedDocument::record` / `string` | Decode one record or borrow one string by ID, checking it on access |
| `MappedDocument::string_ids` / `records` | Enumerate IDs and layouts directly from mapped directories |
| `MappedDocument::verify_all` | Explicitly validate every payload, one at a time |
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
and reads its field through the indexed reader without installing a language plugin.
The schema definition and string dictionary travel with the record.

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
    let bytes = encode_mapped(&document, &limits)?;
    let mapped = MappedDocument::from_bytes(bytes, MappedLimits::default())?;
    let record = mapped.record(Reference { table: 3, row: 42 })?.unwrap();
    let Some(Value::String(id)) = mapped.metadata().field(&record, &name("mode")) else {
        panic!("mode must reference the string dictionary");
    };
    assert_eq!(mapped.string(*id)?, Some("region-local"));
    assert!(mapped.metadata().strings.is_empty());
    assert!(mapped.metadata().tables[&3].rows.is_empty());
    mapped.verify_all()?;
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
- [Mapped access](tests/mapped_access.rs): independent indexed wire fixture, unknown layouts,
  sparse IDs, lazy integrity checks, and a Unix subprocess with inaccessible unrelated
  payload pages proving that open/selected reads do not touch those pages.
- [Storage integration](../storage/tests/schema_mapped_source.rs): directory and STORED ZIP
  entries use existing shared mappings without copying or extraction.
- [Model tests](src/model.rs): schema conflicts, invalid references, mapping errors,
  sparse ID allocation, and matching encode/decode budget boundaries.

## Mapped access and current limits

`MappedDocument<B>` retains any `B: AsRef<[u8]>` backing. `from_bytes` can use a
borrowed slice or an existing mapped range, including a range supplied by Graphite's
storage layer for a directory file or an uncompressed `.graphite` ZIP entry. It does
not copy the backing bytes. Passing a `Vec<u8>` as in the example exercises the same
indexed access API but does not itself create an OS memory mapping.

`unsafe MappedDocument::open(path, limits)` maps a standalone file with `memmap2`.
The caller must ensure that the file is not modified or truncated for the entire
lifetime of the mapping, including by other processes. This is the file-backed mmap
safety contract; schema validation cannot enforce it.

Opening decodes only bounded schema metadata and scans the persisted directories
for valid sorted IDs, layouts, and ranges. It does not decode or checksum the string
and record payloads or build a heap index proportional to the row count. `record`
binary-searches the persisted directory and decodes only the selected record under
its own budget; `string` checks the selected UTF-8 payload and returns a borrowed
`&str`. References are checked against directory entries without decoding their
targets. Unknown layouts use this same path. Corrupt unselected payloads are detected
when accessed or by an explicit `verify_all`, not necessarily when opening.

`metadata()` contains definitions, profiles, and named tables with empty row maps;
its string dictionary is empty. Use the mapped accessors for data. The writer still
accepts a complete in-memory `Document` and applies `Limits.max_bytes` to its entire
output (64 MiB by default); it is not a streaming corpus indexer.

`MappedLimits` separates metadata and individual-record budgets from total file,
string-count, and row-count limits. The default 64 MiB `Limits` byte budget is not
an aggregate ceiling on a mapped graph's payloads. Limits bound work and allocations,
not total process RSS. Reference cycles do not trigger recursive expansion.

The crate is not a graph database, parser, type solver, or Cypher engine. Both wire
versions are independent of persisted graph versions, and cannot be passed directly
to the existing graph loader. Production writer/loader/query integration and legacy
corpus migration remain pending. Cross-language interoperability tests and real-corpus
load RSS/query measurements are also pending; correctness fixtures do not establish
throughput or 100M-node capacity. See the
[required storage contract](../../docs/graph-schema.md#required-memory-mapped-access).

See the [binary contract](../../docs/schema-wire.md) for exact encoding, validation,
and versioning rules, the [universal schema proposal](../../docs/graph-schema.md) for
the broader architecture, and the [JVM migration plan](../../docs/jvm-generic-types.md)
for the later frontend and corpus work.
