# Declared JVM types

JVM graphs store declaration types in a separate, structurally deduplicated table.
Fields, method parameters and method returns reference that table. Class and method
type parameters retain their scope and bounds; expression rows represent classes,
primitives, arrays, variables and wildcards, including generic enclosing classes.
Erased member identity remains separate from the declared generic type; version 5
represents its descriptors with references to raw type rows and signature rows.

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

Counts and references use signed big-endian int32 words. Lists start with an
int32 count. Type references are zero-based type row IDs, with `-1` reserved for
an absent optional reference. The structural writer uses version 5 when scopes
and erased member keys can be represented without loss. Tables without the
required erased type rows retain version 4; opaque legacy scopes retain version 3.
Readers accept versions 1–5. The main node/edge format does not change.

Version 5 has this layout:

1. Header `0x47545905` (`GTY`, version 5), then SHA-256 of `graph.metadata` (32 bytes)
   and SHA-256 of the complete serialized `graph.strings` file (32 bytes).
2. Type rows: four bytes (`kind`, `variance`, `scopeTag`, reserved zero), then
   int32 `nameStringId`, `scopeTarget`, optional `owner`, optional `component`,
   and the argument reference list. `nameStringId = -1` means absent text;
   other names reference the shared string table. The fixed row is 24 bytes.
3. Erased signatures: parameter type reference list, then return type reference.
   Identical signatures share a row; parameter order and return type both matter.
4. Field bindings: owner string ID, name string ID, erased type reference,
   declared type reference.
5. Method bindings: owner string ID, name string ID, erased signature reference,
   declared parameter reference list, declared return reference, type parameter list.
6. Class declarations: class name string ID, type parameter list, optional superclass
   reference, interface reference list.

Each of sections 2–6 starts with its row count. Kind codes are `class=0`,
`primitive=1`, `array=2`, `variable=3`, `wildcard=4`. Variance codes are
`none=0`, `extends=1`, `super=2`, `unbounded=3`. Scope tags are `none=0`,
`class=1`, `method=2`, `unresolved-class=3`, `unresolved-method=4`.
An absent scope requires target `-1`; other targets reference the zero-based
class or method declaration row, including declarations without graph nodes.
Readers reject unknown codes, nonzero reserved bytes and invalid references.
Scope references can point forward and must be checked after declaration indexing.

A version 4/5 type parameter contains int32 name string ID, one scope-tag byte,
three reserved zero bytes, int32 scope target, and the bound reference list.
The full method descriptor includes the return type, keeping bridge methods
distinct. Identical type expressions share rows, but same-named variables from
different scopes do not. Scope text is reconstructed only when requested; the
loaded table retains the declaration reference.

Erased key references must resolve to raw classes, primitives or arrays of those
types, with at most 255 array dimensions. Variables, wildcards and parameterized
classes cannot be erased key rows;
`void` is allowed only as a non-array method return. Readers validate these shapes
and cycles before rendering descriptors or scopes, and reject semantically
identical signatures and member keys even when they use different type IDs.
Descriptor lengths are checked without expanding their text. Both UTF-16 length
and UTF-8 byte length must fit a signed int32; scopes used by type expressions
must also satisfy the existing projection text limit before allocation.

Version 4 (`0x47545904`) uses the same type rows, scope references and digests,
but has no erased signature section. Its field and method keys store the complete
JVM descriptor as a shared string ID. Their binding payloads and class rows are
otherwise unchanged. Saving does not silently append missing erased type rows to
a caller's table: nonrepresentable keys preserve this older representation.

Version 3 (`0x47545903`) has the same digests and declaration sections, but its
type rows contain `kind`, `name`, `scope`, optional `owner`, optional `component`,
`variance`, and the argument reference list. All text, including kind, variance
and scope, uses shared string IDs; an empty value has an ordinary string entry.
Its type parameters contain name, scope and the bound reference list.

Before saving, declaration names and member identity text are collected with
node and metadata strings into one sorted, deduplicated string table. Version 5
adds only atomic declaration names that are not already present: no enum labels,
composed scopes, descriptors or formatted generic types such as `List<User>`.
Existing node and metadata text is unchanged. Type IDs, erased signature IDs,
declaration row IDs, string IDs and Node IDs are distinct.

The versions 3–5 string binding covers the actual serialized bytes used to load
that immutable string-table instance. It is distinct from the semantic snapshot
in `graph.strings.identity`; a matching snapshot cannot authorize a changed
string file. Both readers require the verified serialized digest before exposing
versions 3–5 declarations and validate every referenced string ID and its text.
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

Saving a loaded graph remaps its declaration names against the newly built
`graph.strings`. Canonical scopes and erased keys use version 5 references where
representable; legacy tables retain version 4 or 3 when needed to preserve their
text. An empty declaration
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

The JVM reader retains mapped type rows and compact indexes; versions 3–5 resolve
text through the graph's already loaded string table. Its legacy version 2 reader
retains dictionary offsets and hashes. The native reader decodes declaration
values into graph-local shared storage, with no process-wide interning. Rendering
and structured projections are produced when requested.

During shared-string validation, the JVM reader also builds an immutable, bounded
ASCII substring summary for declaration text and generated `type_info` keys.
Dynamic text queries use this summary to rule out impossible matches without
scanning the type table or charging declaration rows against the query budget.
Possible matches still require the complete node predicate and normal node work
accounting. Legacy or mutable tables take the conservative node path. The summary
is an in-memory index and does not change the storage format. For version 5,
projection reachability starts at declared field, method and class bindings,
including formal bounds. Rows used only by erased keys are excluded from the
generic text summary and candidate scan; they cannot introduce false candidates
or consume extra generic-query row work merely by being present in the table.

## Verification

`DeclaredTypePersistenceTest` compiles a Java JAR, builds its graph, and verifies
ordinary and mapped loading plus Cypher projections. Set `GRAPHITE_TYPES_FIXTURE`
to retain the current structural graph, `GRAPHITE_TYPES_V1_FIXTURE` for version 1,
`GRAPHITE_TYPES_V2_FIXTURE` for version 2, `GRAPHITE_TYPES_V3_FIXTURE` for
version 3, and `GRAPHITE_TYPES_V4_FIXTURE` for version 4. The native interoperability
tests exercise all five as directories and packed containers; set
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
