# Actual-main index lifecycle oracle

This is a correctness capture for the next native benchmark prerequisite, not
a native implementation or real64 replay. `IndexLifecycleOracle.java` invokes
the pinned main GraphStore, clear/preparation methods, and context-aware scoped
executor. All inputs are copied tiny persisted correctness fixtures.

Ten scenarios combine lazy/startup preparation with an existing index, missing
index, corrupt index, bad node with existing index, and bad node with missing
index. Each executes query/clear/query/prepare/clear/query/clear: 70 operations
and 30 complete public query responses. The query and parameter are unchanged.
Startup uses main's actual `graphite.webgraph.prepareCallSiteStringIndexOnLoad`
property. Preparation uses main's Kotlin default work consumer. No query result,
cache state, or graph error is substituted by an adapter.

Observed requirements for Go:

- Clearing resets retained and mapped indexes, raw match/projection caches and
  mapped range validation state while retaining usable graph ownership.
- Clearing a built trigram index can persist it first: three lazy scenarios
  write or repair the sidecar during the clear operation. The subsequent query
  loads that persisted index. Cold setup must preserve this file lifecycle.
- Startup preparation loads/builds trigrams and persists before exposing the
  graph, including replacing a corrupt sidecar. First-query state differs from
  merely opening a graph that already has a sidecar on disk.
- The two bad-node startup scenarios initially return the expected row. After
  clearing, the same query and parameter produce the original decode error.
  Repeated-query semantics and preparation policy therefore matter beyond
  checking whether a retained-index flag is true.

The capture has 20 successful query responses and 10 expected decode errors.
All 30 clears leave the inspected in-memory index/cache state empty. Exactly
six fixture index files are created/replaced; every other fixture file remains
byte-identical. `fixture-before.json` preserves original file hashes, including
the intentionally corrupted sidecar hash, and `verification.json` lists all
changes. The `fixtures` directory is the post-run state and must not be silently
reused as fresh input. Recreate it from the committed candidate-index fixtures
according to the scenario, removing the sidecar for missing variants and XORing
its final byte with 1 for corrupt variants, before rerunning under a new path.

Command arrays, class output and logs are retained. Run `python3 verify.py` in
this directory to independently verify the capture and fixture boundaries.
Native clear/startup APIs, equivalent cache ownership, and full real64
cold/warm/startup-prepared replay remain unfinished. No P95 is measured here.
