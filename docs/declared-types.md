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

Version 1 uses big-endian integers. Strings are an int32 UTF-8 byte length followed
by those bytes; lists start with an int32 count. References are zero-based type row
IDs, with `-1` reserved for an absent optional reference.

1. Header `0x47545901` (`GTY`, version 1), then SHA-256 of `graph.metadata` (32 bytes).
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

Both readers validate the table binding, metadata digest, lengths, reference ranges, expression
shapes, duplicate declaration keys and expression cycles before exposing the table.
Nesting beyond 256 expression levels is rejected. Expanded projections are limited
to 100,000 expression nodes and 1,000,000 UTF-8 bytes of type text to reject small
DAGs whose recursive expansion would consume unbounded memory. A referenced but missing
or invalid table is a load error; an absent binding is the supported legacy case. This validation and
loading occur during graph loading, not during the first generic query.

## Verification

`DeclaredTypePersistenceTest` compiles a Java JAR, builds its graph, and verifies
ordinary and mapped loading plus Cypher projections. Set `GRAPHITE_TYPES_FIXTURE`
to retain that persisted graph for the native interoperability tests. Fixtures in
these tests establish correctness, not performance. Performance comparisons must
use the repository's representative real multi-graph workloads.

The declaration references also correct two historical type-name errors: generic
fields retain their erased bytecode class instead of a variable name such as `T`,
and arrays retain all dimensions. Existing collection type arguments remain available
in the in-memory analysis representation. The sample-corpus parity snapshot was
migrated through 84 exact type-text substitutions (39 field-erasure facts and 45
array-dimension facts): all 12,725 unique facts / 12,894 counted facts match after
those substitutions, with no collisions or topology/count changes. The parity test
continues to reject missing facts without normalization or relaxed assertions.
