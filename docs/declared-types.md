# Declared JVM types

JVM graphs store declaration types in a separate, structurally deduplicated table.
Fields, method parameters and method returns reference that table. Class and method
type parameters retain their scope and bounds; expression rows represent classes,
primitives, arrays, variables and wildcards, including generic enclosing classes.
The erased descriptors used for graph identity and method matching remain separate.

## Query properties

| Source | Property | Value |
| --- | --- | --- |
| `FieldNode`, `ParameterNode`, `ReturnNode` | `generic_type` | Formatted declared type, or null |
| Same | `type_info` | Structured declared type, or null |
| `Method` | `generic_return_type` | Formatted return type, or null |
| `Method` | `generic_parameter_types` | Formatted parameter types in JVM descriptor order, or null |
| `Method` | `return_type_info` | Structured return type, or null |
| `Method` | `parameter_type_info` | Structured parameter types in JVM descriptor order, or null |
| `Method` | `type_parameters` | Method type parameters with `name`, `scope`, `bounds` and `bound_info`, or null |

```cypher
MATCH (m:Method)
WHERE m.name = 'getUsers'
RETURN m.return_type, m.generic_return_type, m.return_type_info
```

For `List<User> getUsers()`, `return_type` is `java.util.List` and
`generic_return_type` is `java.util.List<com.example.User>`.
The structured projection includes the applicable type attributes:

```json
{
  "kind": "class",
  "name": "java.util.List",
  "arguments": [
    {
      "kind": "class", "name": "com.example.User", "arguments": []
    }
  ]
}
```

`owner` represents a generic enclosing class. `component` represents an array
element or a wildcard bound. `variance` is `extends`, `super` or `unbounded` for a
wildcard. Every expression includes `kind` and `arguments`; inapplicable attributes
are absent from both query maps and JSON. Variables reference scopes such as `class:com.example.Holder` or
`method:com.example.Holder#identity(Ljava/lang/Object;)Ljava/lang/Object;`.
Missing enclosing declarations produce an explicit `unresolved:` scope.
Recursive bounds such as `T extends Comparable<T>` reference a variable instead
of recursively expanding its bounds.

These properties describe declarations. They do not infer the concrete `T` at
every call site. `ReturnNode.actual_type` retains its separate analysis meaning.
When a declaration has no generic Signature, the descriptor supplies its type;
information stripped from bytecode cannot be recovered this way. Missing declaration
metadata yields null, distinct from a known non-generic declaration.

An observed inherited field reference may name a child class rather than the class
that declares the field. During construction, its symbolic owner, name and erased
descriptor are bound to the existing declaration's type ID, following JVM field
resolution through interfaces and superclasses. The original field node identity
and type-variable scope remain unchanged: accessing `Parent<T>.value` through
`Child extends Parent<String>` still describes the declaration as `T`. Unknown or
cyclic hierarchy branches do not justify guessing a later declaration.

`keys()` and property maps expose the new keys only when that member has a declaration
binding. Legacy graphs and unbound external members keep their existing keys; direct
access to a missing declaration property returns null. This preserves negative dynamic
property predicates on legacy graphs. The native schema endpoint describes the added properties and lists the
virtual `Method` source under `virtual_nodes`; its count is not added to stored-node
counts. Graph-local type IDs are internal and are expanded for query results.

## Storage and compatibility

`graph.types` is an optional binary table with its own version. It does not change
the main node/edge format. Old graphs load without it; rebuild from the original
JAR to obtain declaration metadata. New saves preserve the table, or remove an
obsolete table when the source graph has no declaration metadata. Native container
packing includes the file. The authoritative `forward.properties` file contains
`graphite.declaredTypes.sha256`, the SHA-256 digest of the complete `graph.types` file.
Readers require a matching table when this key is present. Without the key, readers
treat the graph as legacy and ignore any orphan table. Rewriting a graph with an older
writer therefore cannot accidentally reuse stale generic declarations, even when its
erased metadata is unchanged.

All versions use signed big-endian int32 words. Lists start with an int32 count.
Type references are zero-based type row IDs, with `-1` reserved for an absent
optional reference. Writers emit version 3; JVM and native readers also accept
versions 1 and 2. The main node/edge format does not change.

1. Header `0x47545903` (`GTY`, version 3), then SHA-256 of `graph.metadata` (32 bytes)
   and SHA-256 of the complete serialized `graph.strings` file (32 bytes).
   There is no string count or local dictionary in this version.
2. Type rows: `kind`, `name`, `scope`, optional `owner`, optional `component`,
   `variance`, argument reference list.
3. Field bindings: owner, name, full JVM field descriptor, type reference.
4. Method bindings: owner, name, full JVM method descriptor, parameter reference
   list, return reference, type parameter list.
5. Class declarations: class name, type parameter list, optional superclass
   reference, interface reference list.

Each of sections 2–5 starts with its row count. A type parameter consists of its
name, scope and bound reference list. The full method descriptor includes the
return type, keeping bridge methods distinct. Identical type expressions share
rows, but same-named variables from different scopes do not.

Every text field in sections 2–5 is a zero-based int32 ID into `graph.strings`,
including member keys, type kinds/names/scopes, variance and type parameter
names/scopes. Empty text has an ordinary string-table entry; text IDs have no
null sentinel. Type IDs remain local to `graph.types` and are separate from
string IDs and Node IDs. Before saving, the writer collects declaration strings
with node and metadata strings, then builds one sorted, deduplicated string table.
Formatted generic types such as `List<User>` are rendered on demand rather than
stored as additional strings.

The version 3 string binding covers the actual serialized bytes used to load
that immutable string-table instance. It is distinct from the semantic snapshot
in `graph.strings.identity`; a matching snapshot cannot authorize a changed
string file. Both readers require the verified serialized digest before exposing
version 3 declarations and validate every referenced string ID and its text.
Unpaired UTF-16 surrogates cannot be silently replaced in declared types.
The type table's metadata digest and authoritative `forward.properties` binding
remain required as well.

Version 2 (`0x47545902`) has the metadata digest followed by its own unique UTF-8
dictionary: an int32 string count, then an int32 byte length and bytes per string.
Its four declaration sections use IDs local to that dictionary, assigned in
first-occurrence order. Readers validate every entry, including unused entries,
and reject invalid UTF-8, duplicate text and out-of-range string IDs. Version 1
(`0x47545901`) has the metadata digest and the same declaration sections, but
each text field contains its own int32 UTF-8 byte length and bytes.

Saving a loaded graph with declaration metadata writes version 3 and remaps its
declaration strings against the newly built `graph.strings`. An empty declaration
table removes or omits `graph.types` and its binding. When saving into the same
directory, the source table continues to resolve text through its original immutable
string table while the replacement type file is written and published. Reusing old
string IDs with a newly built dictionary is not valid.

Both readers validate the table binding, metadata digest, lengths, reference ranges,
expression shapes, duplicate declaration keys and expression cycles before exposing
the table. Nesting beyond 256 expression levels is rejected. Expanded projections
are limited to 100,000 expression nodes and 1,000,000 UTF-8 bytes of type text to
reject small DAGs whose recursive expansion would consume unbounded memory. A
referenced but missing or invalid table is a load error; an absent binding is the
supported legacy case. All integrity validation occurs during graph loading,
before the first generic query.

Optional bytecode signatures use these same nesting and expansion limits while
their expressions are interned. A rejected signature rolls back its partial rows
and uses the erased descriptor; it cannot leave an over-budget table that fails
only at the end of graph construction. Shared expressions count once per occurrence
in the expanded projection, even when their type IDs are deduplicated.

The JVM reader retains mapped type rows and compact indexes; version 3 resolves
text through the graph's already loaded string table. Its legacy version 2 reader
retains dictionary offsets and hashes. The native reader decodes declaration
values into graph-local shared storage, with no process-wide interning. Rendering
and structured projections are produced when requested.

During version 3 validation, the JVM reader also builds an immutable, bounded
ASCII substring summary for declaration text and generated `type_info` keys.
Dynamic text queries use this summary to rule out impossible matches without
scanning the type table or charging declaration rows against the query budget.
Possible matches still require the complete node predicate and normal node work
accounting. Legacy or mutable tables take the conservative node path. The summary
is an in-memory index and does not change the storage format.

## Verification

`DeclaredTypePersistenceTest` compiles a Java JAR, builds its graph, and verifies
ordinary and mapped loading plus Cypher projections. Set `GRAPHITE_TYPES_FIXTURE`
to retain the current version 3 graph, `GRAPHITE_TYPES_V1_FIXTURE` for version 1,
and `GRAPHITE_TYPES_V2_FIXTURE` for version 2. The native interoperability tests
exercise all three as directories and packed containers; set
`GRAPHITE_REQUIRE_ALL_TYPES_FIXTURES=1` to require every fixture. These fixtures establish correctness, not performance. Performance comparisons must
use the repository's representative real multi-graph workloads.

`InheritedFieldPersistenceTest` checks both graph builders through save, reload and
resave. Set `GRAPHITE_INHERITED_TYPES_FIXTURE` to a fresh output directory to retain
its four graphs. The native `inherited_fields_interop` integration test checks all
four as directories and packed containers, including full type structures and
cross-graph provenance. Set `GRAPHITE_REQUIRE_INHERITED_TYPES_FIXTURE=1` together
with that directory to make a missing fixture fail the native gate.

The declaration references also correct two historical type-name errors: generic
fields retain their erased bytecode class instead of a variable name such as `T`,
and arrays retain all dimensions. Existing collection type arguments remain available
in the in-memory analysis representation. The sample-corpus parity snapshot was
migrated through 84 exact type-text substitutions (39 field-erasure facts and 45
array-dimension facts): all 12,725 unique facts / 12,894 counted facts match after
those substitutions, with no collisions or topology/count changes. The parity test
continues to reject missing facts without normalization or relaxed assertions.
The [large-corpus identity audit](declared-types-corpus-identity-audit.md) independently
verifies the recovered array overloads and preserved labeled-edge topology on Hive,
Tika and the Kotlin compiler.
