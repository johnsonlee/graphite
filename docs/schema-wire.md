# Universal Schema Contract and Binary Codec

English | [简体中文](schema-wire.zh.md)

Status: implemented structural foundation, `GSCHEMA/1` interchange codec, and
`GSCHEMA/2` indexed mapped reader. This contract implements the value, registry,
reference, interchange, and mapped record access portions of the
[schema proposal](graph-schema.md).
The implementation is the independent [`graphite-schema`](../backend/schema/README.md)
Rust crate. It does not change v1–v3 graph storage, allocate a graph v4, or connect the
new records to the production indexer, Cypher engine, or JVM frontend.

## Contract boundaries

The same codec must decode, validate, inspect, remap, merge, and re-encode records
whose names and language profiles were unknown when it was compiled. A new language
adds definitions and records, never descriptor tags or language branches. Unknown
physical tags or wire versions are rejected; unknown semantic names are preserved.

Wire version 1 carries a complete logical document for in-memory exchange. Wire
version 2 stores the same model with persisted directories for mapped, on-demand
record and string access. Neither is a new production graph version. Production
graph indexing, compressed adjacency integration, large-corpus streaming, JVM IR
emission, and legacy import remain later work; no 100M-node performance claim is made.

Production graph readers must retain Graphite's existing mmap access convention.
Use the indexed reader for mapped records: mapping the input to the version-1
full-Document decoder does not avoid heap materialization. Production loader/query
integration and real-corpus acceptance remain required beyond this library.

## Logical document

All local IDs are unsigned 32-bit integers; zero is a valid ID. IDs may be sparse.
Each ID space is separate: strings, definitions (layouts), tables, and rows within a
table. IDs identify locations, not semantic declarations. ID exhaustion fails instead
of wrapping. List position, explicit ordinal fields, and reference identity preserve
order; numeric table row order is not semantic order and may change on remapping.

A document contains:

- A string dictionary, keyed by StringId. Equal strings at different IDs are allowed.
- A definition registry, keyed by LayoutId.
- An ordered list of language profiles, each with a qualified name and revision.
- Named tables keyed by TableId, containing rows keyed by RowId.

A qualified name is two separate, nonempty UTF-8 strings: namespace and local name.
The representation never guesses the split from punctuation. Revisions are nonempty
UTF-8 strings; no numeric or semantic-version interpretation is required. Namespace
ownership and semantic trust are profile concerns, not instructions to download code.

A definition contains its qualified name, revision, category, and an ordered field
list. A field contains its qualified name, descriptor, required flag, nullable flag,
and optional qualified role. Categories and roles are open names. Field names must be
unique within a definition. Definition (name, revision) pairs, table names, and profile
(name, revision) pairs must each be unique within a document. Different revisions may
coexist. Published definition content is immutable; incompatible changes require a
new revision. Merge compares complete definitions after translating target table IDs
into the shared table namespace and rejects conflicting content for the same identity.

A record contains a layout ID and exactly one slot per field in layout order. Each
slot is absent, explicitly null, or a typed value. Required means present; nullable
controls explicit null independently. An absent field is never replaced with a
language's `unknown`, `any`, `never`, or a default. Null list elements are represented
by a Null element descriptor, or by a record wrapper with nullable fields when mixed
with other values. A heterogeneous union uses Record wrappers, not new primitive tags.

| Descriptor | Rust value | Meaning |
| --- | --- | --- |
| Null | Null | Null literal; a direct field also requires nullable=true |
| Bool | Bool | Boolean |
| Int64 / UInt64 | Int64 / UInt64 | Exact signed / unsigned 64-bit integer |
| Float64 | Float64(u64) | IEEE 754 bits; preserve signed zero and NaN payloads |
| String | String(StringId) | Reference to the document string dictionary |
| Bytes | Bytes | Raw bytes without local graph references |
| Ref(Some(table)) | Ref { table, row } | Reference constrained to an existing table |
| Ref(None) | Ref { table, row } | Explicit reference to any existing table |
| List(element) | List | Ordered, possibly repeated values matching one descriptor |
| Record | Record | Inline record carrying its own registered layout |

Every reference target must exist, including targets declared in field descriptors
of unused definitions. Inline records use the same registry as top-level rows.
Forward references and cycles through Ref are legal; recursive inline nesting is
bounded. Raw strings and Bytes must not hide local IDs that need remapping: structural
validation cannot infer hidden references, so this is a producer contract.

Maps use lists of entry records; a profile declares key uniqueness and ordering.
Symbols include module/profile and declaration identity; parameter bindings include
scope identity and ordinal. Matching display names never justify coalescing rows.
Declared, inferred, resolved, and observed types remain separate records or roles.

## Common vocabulary mapping

The domain records in the [proposal](graph-schema.md#common-domain-records) use the
same definition mechanism as unknown extensions. Module, Symbol, Scope, Parameter,
Expression, Constraint, Assertion, Node, Edge, and TypeUse are semantic categories,
not additional physical tags. A profile publishes the concrete revisioned layouts,
field roles, provenance, and extraction completeness for the records it emits.

Operators, predicates, and parameter categories are namespaced definitions or explicit
references to records describing those definitions. For example, a definition named
`("example.type", "region-qualified")` can carry category `("core", "expression")`
and a field with role `("core.type", "operand")`. The example in the proposal maps
to a layout whose base and region fields are typed references. A frontend supplies
the relevant target tables; the codec never interprets `region-qualified`.

The registry permits complete structural access without a semantic module. A shared
role does not prove subtype, assignability, or conformance. Languages with different
rules can share a structural layout while providing separate analyzers. This release
freezes the structural contract; it does not declare language mappings or inference
rules complete. Concrete JVM/Swift/TS profile revisions must be reviewed with their
frontends, without changing this wire format.

## Version 1: interchange envelope

All integers are little-endian. UTF-8 is strict. `text` means a u64 byte length followed
by those bytes. `name` means two texts (namespace, local name). Counts use u32. No
alignment padding, native pointers, timestamps, compression, or executable schema code
is present. The complete file has these fields in order:

| Offset | Width | Content |
| --- | --- | --- |
| 0 | 8 | ASCII `GSCHEMA` followed by a zero byte |
| 8 | 4 | Wire version, 1 |
| 12 | 8 | Payload byte length |
| 20 | 32 | SHA-256 of the entire payload |
| 52 | payload length | Directory followed by section bodies |

The payload starts with section count 4, followed by four directory entries in kind
order 1 through 4. Each entry has u32 kind, u64 length, and a 32-byte SHA-256 of its
section. Section bodies follow contiguously in that order. The directory occupies
180 bytes. Both per-section and whole-payload hashes must match. There are no gaps,
unknown envelope sections, or trailing bytes. The payload hash binds the directory,
registry, dictionaries, profiles, and tables together. It is an integrity check,
not authentication or a hash of semantic identity across ID remapping.

| Kind | Section body |
| --- | --- |
| 1: strings | count; repeated (u32 StringId, text) |
| 2: profiles | count; repeated (name, revision text) |
| 3: definitions | count; repeated (u32 LayoutId, name, revision text, category name, field count, fields) |
| 4: tables | count; repeated (u32 TableId, name, group count, groups) |

A field is (name, descriptor, required u8, nullable u8, role-present u8, optional
role name). Boolean flags accept only 0 and 1. Descriptor encoding is:

| Tag (u8) | Descriptor | Additional descriptor bytes |
| --- | --- | --- |
| 0 | Null | None |
| 1 | Bool | None |
| 2 | Int64 | None |
| 3 | UInt64 | None |
| 4 | Float64 | None |
| 5 | String | None |
| 6 | Bytes | None |
| 7 | Ref | target-present u8; u32 TableId when present |
| 8 | List | Nested element descriptor |
| 9 | Record | None |

A table group contains (u32 LayoutId, u32 row count, rows). A row contains (u32 RowId,
u64 record-body length, record body). Groups must be nonempty and strictly increasing
by layout ID; rows within a group must be strictly increasing by row ID. Row IDs are
unique across the entire table, including across groups. An empty table has no groups.
The encoder sorts dictionary, definition, and table IDs; decoders may accept their
unique IDs in other orders. Profiles and all field/list orders are preserved.

A record body starts with two bitmaps of `ceil(field_count / 8)` bytes each: presence,
then null. Bit i uses the least-significant-bit-first position for field i. Padding
bits are zero. A null bit requires its presence bit. Present, non-null fields then
encode their values in layout order, without per-value type tags or field names:

| Value | Bytes |
| --- | --- |
| Null | No bytes (direct field uses its null bitmap bit) |
| Bool | u8, exactly 0 or 1 |
| Int64 / UInt64 / Float64 | 8 bytes, preserving integer / float bits |
| String | u32 StringId |
| Bytes | u64 length followed by bytes |
| Homogeneous Ref | u32 RowId; the descriptor supplies the table |
| Heterogeneous Ref | u32 TableId then u32 RowId |
| List | u32 element count, then elements using the element descriptor |
| Inline Record | u32 LayoutId then record body using that layout |

A Null-descriptor direct field cannot set presence without null. Each row body and
section must be consumed exactly. Missing fields/tables/layouts/strings, invalid
references, conflicting identities, unsupported tags, malformed bitmaps, and extra
bytes are errors. No malformed record is silently dropped or replaced with an empty
object. Language additions change dictionary/definition/table contents only.

## Version 2: indexed mapped envelope

`encode_mapped(&Document, &Limits)` validates the complete input document and writes
this indexed format. `MappedDocument<B: AsRef<[u8]>>::from_bytes(B, MappedLimits)`
retains the backing bytes without copying them. `unsafe MappedDocument::open` uses
`memmap2` for standalone files; the caller must guarantee that the file remains
unmodified and untruncated for the mapping's lifetime. Existing mapped directory-file
or ZIP-entry ranges can use `from_bytes`, without a dependency on the storage crate.

The 80-byte header is followed by metadata, the string directory, the row directory,
all string payloads, then all record payloads. Integers are little-endian. Ranges are
contiguous, nonoverlapping, and consume the file exactly; reserved fields must be zero.
Directories remain in the backing bytes and are searched by binary search.

| Offset | Width | Content |
| --- | --- | --- |
| 0 | 8 | `GSCHEMA` followed by a zero byte |
| 8 | 4 | Wire version, 2 |
| 12 | 4 | Reserved, zero |
| 16 | 8 | Total file length |
| 24 | 8 | Metadata byte length |
| 32 | 8 | String count |
| 40 | 8 | Row count across all tables |
| 48 | 32 | Index SHA-256 |

Metadata is a version-1 envelope containing definitions, profiles, and named tables
with empty row maps and an empty string dictionary. `metadata()` exposes that bounded
`Document`; its empty data maps do not mean that the mapped file has no data.

Each 56-byte string-directory entry contains `(u32 StringId, u32 reserved, u64 offset,
u64 length, [u8; 32] SHA-256)`, in strictly increasing StringId order. The payload is
raw UTF-8 without a length prefix. Each 64-byte row-directory entry contains
`(u32 TableId, u32 RowId, u32 LayoutId, u32 reserved, u64 offset, u64 length,
[u8; 32] SHA-256)`, in strictly increasing `(TableId, RowId)` order. Offsets are
absolute from the start of this envelope. Record payloads use the version-1 record
body encoding above, with the layout supplied by their directory entry.

The index SHA-256 covers header bytes `0..48`, followed by metadata and both
directories (`80..payload_start`). It excludes the digest field and all string/row
bodies. It binds lookup IDs, layout IDs, ranges, and per-payload digests without
reading those payloads.

Opening validates the header, index hash and schema, and scans directories to
check ordering, layouts, table identities, and ranges. This is O(directory entries)
work with bounded heap use, not a claim of constant-time opening. Opening does not
checksum or decode string and record payloads and does not create a heap row index.

`record(Reference)` returns `Result<Option<Record>>`, decoding and checksumming only
the selected record under an independent per-record budget. Field structure and all
nested string/row references are validated against the persisted directories;
checking a reference does not read or decode its target. `string(StringId)` returns
`Result<Option<&str>>`, checking only that string's checksum and UTF-8 and borrowing
its bytes. Absent IDs return `None`; corrupt selected data returns an error. Unknown
layouts use the same schema-driven path, with no whole-document fallback.

`verify_all()` explicitly accesses every string and record, one at a time. An open
file has validated metadata and directory structure, not proof that every payload
is intact; untouched corruption is deferred until access or `verify_all`. Checksums
are integrity checks, not authentication. Production container integrity remains an
additional enclosing check.

`MappedLimits` has independent metadata and single-record `Limits`, plus total file,
string-count, and row-count limits. Default metadata and record byte budgets are
16 MiB and 64 MiB respectively, with no separate 64 MiB aggregate ceiling on mapped
payloads. The writer still takes a complete in-memory document, returns a byte vector,
and applies its `Limits.max_bytes` to the entire output (64 MiB by default). Streaming
output and production index construction are not implemented by this API.

The persisted directories cost 56 bytes per string and 64 bytes per row, before
payloads and metadata. For example, 100M rows require 6.4 GB of row-directory bytes
alone (decimal units); this is arithmetic, not a capacity benchmark. This indexed
library format does not implement the proposal's grouped/columnar production layout.

## In-memory operations and resource limits

`encode(&Document, &Limits)` validates before returning bytes. `decode(&[u8], &Limits)`
checks the envelope, section integrity, structure, and all references before returning
a document. Neither needs a language plugin. `record`, `field`, and `records_of`
provide structural inspection by explicit references and qualified names; they are
not a Cypher implementation. `records_of` returns top-level records across revisions.
`field` returns None for an absent or unknown field, and Some(Null) for explicit null.
Float equality uses raw bits for lossless preservation.

`remap(&IdMap, &Limits)` requires total, collision-free mappings with no unused keys
for strings, layouts, tables, and rows. It rewrites every nested reference, string ID,
layout ID, and target table descriptor. It retains profile names, raw bytes, absent
fields, nulls, list order, repeated references, and distinct rows.

`merge(&Document, &Limits)` shares tables by qualified name, shares identical
(name, revision) definitions after table-ID translation, and deduplicates dictionary
strings. Profiles are combined by (name, revision), preserving left order followed
by new right profiles. Rows are never coalesced; right-hand rows receive fresh IDs
before any values are copied, so forward references and cycles survive. The left
operand's IDs remain stable. Incompatible definitions or ID exhaustion fail without
mutating either input. Allocation appends IDs when possible and reuses lower holes
when a sparse namespace reaches u32::MAX. This is a structural union, not symbol
resolution or type canonicalization.

Default limits are 64 MiB of encoded bytes, 1,000,000 aggregate items, and inline depth
64. Callers may lower them or raise byte/item limits; the hard maximum depth is 256.
Items include dictionary/profile/table/definition entries, fields and descriptors,
layout groups, rows, record slots, list slots, and each non-null field value or list
value. Counts are checked before iteration/allocation driven by those counts. String
and byte lengths must fit the remaining input. Model validation also budgets aggregate
UTF-8 and blob bytes. Encoding includes framing overhead in the final byte limit.

Depth starts at zero for a top-level record or field descriptor. A list descriptor
adds one level. A field value adds one level to its record; list elements and inline
records add one level to their containing value. Ref traversal does not add inline
depth and validation does not recursively chase cycles. No untrusted count is used
for unchecked preallocation. The API holds a complete document and bounded buffers
in memory; max_bytes is a wire budget, not a promise of equal peak RSS.

## Verification and deferred integration

The test suite includes an unfamiliar profile with more than 16 node kinds and more
than 256 predicates, bit-exact scalar values, scoped/shadowed parameters, cyclic and
nested references, parallel relations, total re-ID, and merges with overlapping IDs.
It asserts logical reference targets and full field values after re-encoding, not
only counts. Independent binary fixtures and correctly checksummed malformed inputs
exercise decoder behavior beyond encoder/decoder agreement.

Run `cargo test -p graphite-schema --locked` and
`cargo clippy -p graphite-schema --all-targets --locked -- -D warnings`.
Synthetic fixtures are correctness evidence only. The repository's required real-data
benchmark gate checks existing runtime regressions; no throughput or capacity result
for the new codec is inferred from those fixtures.

JVM extraction and complete corpus migration follow #165 after its SootUp changes
and baselines settle. The importer must handle both earlier v3 graphs and the added
call-site ordinal/origin sidecar, GRB binding, and folding provenance. This crate does
not claim `graphite build --from` or a new production query path is available.
