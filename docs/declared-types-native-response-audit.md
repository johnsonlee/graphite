# Declared-type response migration audit

The new API deliberately adds `generic_type` and `type_info` to members with exact declaration bindings. Two of the 73 native fixture64 queries therefore have new complete responses. The benchmark comparator permits only the exact independently audited before/after SHA-256 pairs below, with their exact query and complete catalog identity, and only when explicitly enabled. It never strips properties, ignores arbitrary digest changes, relaxes timings, or substitutes a sampled response.

Source evidence is GitHub run [37779605564](https://github.com/johnsonlee/graphite/actions/runs/37779605564), base `4f2ccf33b969e684972e56b5e810034e6e67c1b3`, candidate `cfcfb191e3859550e05322223459f1a9b283e622`. Both native engines read the same candidate-built 64 real class-shard graphs. Shared fixture artifact11552907795 is SHA-256 `5d04ba0c6a4e3096f0830ee3eb1bbca579ae9ceecfb459e3b4dd244159bc53c4`; response artifact11553695221 is SHA-256 `377b96f6413a7b8245239d02793485649168b309ad44d34972c21368156b79b0`.

## Key histogram

`MATCH (n) UNWIND keys(n) AS k RETURN k, count(*) AS c ORDER BY c DESC LIMIT 50` retains every old row, value, order and graph provenance. Two rows are added, each with count3,485,013 and all64graphIDs in the existing order. `rowCount` and `total.value` consequently change26→28; other response metadata is identical. All five complete response pairs were checked.

The independent [wire counter](declared-types-native-response-audit/CountBindings.java) uses no Graphite code or query engine. It reads raw node offsets/records and full JVM descriptor keys from `graph.types`, and uses the original front-coded string library solely to deserialize `graph.strings`. It counts a parameter only when its method binding and parameter index exist. The [64 per-graph counts](declared-types-native-response-audit/binding-counts.json) give:

| Bound member | Count |
|---|---:|
| Fields | 559,338 |
| Parameters | 1,473,753 |
| Returns | 1,451,922 |
| Total per added key | 3,485,013 |

There are733,412field nodes overall:174,074unbound fields correctly receive neither new key. Counting all field nodes would produce a different, rejected answer. The decoder also checks unique binding keys, consumes the full table, validates node IDs at their offsets, and records each table SHA-256. This is an independent semantic audit, not a replacement for production integrity validation or a performance measurement.

## Node projection

`MATCH (n) RETURN n LIMIT 200` retains all200nodes in order, including their old properties and graph metadata. Exactly49nodes gain the two properties;151rows are identical. Every new value was independently matched to the original pinned Android classfile declaration: method names and descriptors were decoded directly from classfile constant pools, and the five fields were checked with `javap -p -s`. All49types are non-generic primitive/class declarations in this particular prefix; the API intentionally exposes known descriptor fallback too. The [complete descriptor/value audit](declared-types-native-response-audit/projection-audit.json) records each node, owner, member, descriptor, formatted type and structured value. It does not establish generic-signature coverage; separate Java/native interoperability tests cover parameterized types and scope.

## Original gate identities (historical)

The complete73query catalog is `776c005324983836facd486287fd9afde7b7054455dca22c1413d3957ed7e34c`. These query and response contracts were originally enforced by the comparator and [test fixture](../.github/scripts/fixtures/declared-types-native-responses.json); the audited current histogram contract is recorded below. Histogram response digests are `15da09067500c818f96a642fc123a7b706accda8604ca2242a2509863649b16c` → `ac4d9c3993e68b24ed38a75e32fa8263e3b8116bc69d55ab061f97521789fe9a`; node projection digests are `d970e9a364104e9424bd1db998d805a9c706a0747bee0b12032502e093c4a88a` → `c1738ba743cfc7a40ee52e2f5565f369ff0ade90bcd2dcac81f2b9dbcf14a09f`. Complete typed JSON, order and metadata are included in those digests.

Any change to either approved digest, row count, query or catalog still fails. The migration is not reversible and cannot admit another case. Reverse-order timing confirmation must retain the same response contract. The original run's8.55second key-histogram regression remains a failure after this semantic transition; response evolution cannot excuse its latency.

To replay the binding audit, compile `CountBindings.java` with JDK17 and the repository's unmodified dsiutils/fastutil dependencies, then run `java -Xmx4g -XX:ActiveProcessorCount=2 -cp <classes-and-dependencies> CountBindings <64 extracted graph directories in lexical order>`. The native response harness's `response_digest` computes the full canonical digests. The original local audit packet is `/tmp/graphite-pr174-native-schema-audit/`; all numerical results needed to review the transition are committed here.


## Current inherited-field histogram contract

The active histogram expectation now uses the already completed
[persisted-node-derived schema audit](../.github/scripts/fixtures/native-pressure-oracles/blobs/161aea949d79c25f384fb5d8f717f78547af46c5d0dfb4cdff1add9c60089b02.json)
and its [complete expected response](../.github/scripts/fixtures/native-pressure-oracles/blobs/a5786f911f2a1f456a918a736345c841148e41a711f4688bb014f8de476d728f.json).
That independent audit predates the current CI failure and counts 560,208 bound
fields, 1,473,753 parameters and 1,451,922 returns: 3,485,883 occurrences of each
new key. The older audit above remains historical evidence; its count and raw
artifacts have not been rewritten.

All 26 original rows, their values, order and graph provenance remain identical.
The current complete candidate response has 28 rows and canonical SHA-256
`ce4f62ac23534f10a947559830b356a29f1af4e75faaa3c6a4ef288b4fdaed41`.
The comparator, 73-case response fixture and 39-case pressure catalog now agree
with the existing portable oracle. Query identity, baseline digest, row counts,
exact full-body validation and performance limits are unchanged. The obsolete
candidate digest is rejected; this is not an additional allowed response.

All five retained candidate bodies from CI run 37986345288 match that complete
pre-existing expected response. This resolves a stale response-contract pin,
not the separate proof of whole-graph migration, loading/resource acceptance or
continuous-pressure eligibility. The recorded original latency failure remains
historical evidence, and no response update waives a performance regression.
