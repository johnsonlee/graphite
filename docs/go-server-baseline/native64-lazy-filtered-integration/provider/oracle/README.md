# Lazy filtered consumers: C / D / E

This fixture set tests native consumers against pinned main
`4e328b0109e13c896b74004823fb049fcb19251a`. It does not measure performance.
The underlying source implementation is the independently frozen
`main-candidate-review` source, not the older A6 materializing source.

The implementation handles necessary string candidates with residual predicates
(C), an unbounded generic filtered node projection (D), and bounded generic node
projection including DISTINCT (E). Projection follows filtering for each node;
scoped bounded consumers stop when full, whereas qualified DISTINCT completes
required source consumption and merges provenance. It retains source-wave policy
and owned node values. Direct conjunctions use main's own short-circuit matcher;
necessary candidates still evaluate the full WHERE. Unknown required properties
may produce an available empty source. The bounded generic scoped route ignores
source selection just as main's nonqualified nodeCandidates does; unbounded and
selected-source consumers have different routing positions.

The consumer declines pagination / ORDER (B), aggregates, optional patterns,
relationships, Method and unknown-label routes. This is a dispatch boundary, not
a claim that prior fallbacks have full main semantics. In the complete original
1,048 cases, 1,016 now match; B's 28 and F's 4 remain. All prior 580 DISTINCT and
122 ordinary eligible cases remain equal. C's 8, D's 8 and E's 6 all match.

New complete main response gates are 480 broad cases, 96 conjunction edges,
36 source-wave cases, and 8 independently discovered routing cases (620 total).
The source-wave oracle uses actual 16-CPU Java availability, with no
ActiveProcessorCount override; it covers 2, 9 and 40 source inputs. Parameter,
inline-property, matched/unmatched corruption, null, alias, nested / whole node,
source selection and exception order are compared without dropping failed cases.
Native protocol tests cover standard context cancellation, fresh owned values,
Close and negative planner admissions. They do not establish equivalence to all
Java GraphWork / Thread.interrupt histories or configurable global memory budgets.

## Oracle encoding boundary

The original `*-main.json` files preserve Java UTF16 semantic values. Exactly
12 rows in the original 480 contain an isolated high surrogate U+D800. Reading
that escaped surrogate with Go's JSON decoder yields U+FFFD; it is not the actual
HTTP result. A direct Java field/materialization probe recorded unit 55296, and
two actual tiny main HTTP requests independently emitted a single ASCII `?`
(byte 3f), via Java's UTF8 encoder. The `*-wire.json` files therefore use the
same Java UTF8 encoding boundary. Original semantic files and initial failed
comparisons are retained unchanged. No query, parameter, column, error or
production string implementation was changed to normalize the oracle.

Author evidence and exact commands are frozen separately under
`/tmp/graphite-go-lazy-cde-evidence/freeze`. Its manifest records the helper/JAR,
source input, fixture, original semantic and wire hashes. Java helpers here are
correctness oracles only; native runtime execution does not use Java.
