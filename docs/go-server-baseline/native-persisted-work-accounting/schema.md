# Public retained-reader oracle schema

The input is `cases.json`: 52 named cases and 187 ordered operations (the initial 50-case design is preserved separately). Every case supplies 40 independent mapped sources through `sources: [{fixture, graphId}]`, context mode, and a decimal-string budget. Operations retain the preceding LeadingWorkOracle interface: `execute`, `newContext` (stores preserved), and `executePrelude` (a separately persisted one-node graph, same request context). Queries and parameters are recorded exactly in each operation. No private tracker consumption is used by this matrix.

`main.json` records the actual pinned JVM result. Each case retains its input spec, construction, ordered operation before/after snapshots, result columns/rows or full error class/message/stack, final snapshot after stores close, and byte manifests before loading and after closing. Existing request snapshots include all eight diagnostics, remaining work as a decimal string, cancellation state/reason, storage, and prelude loading state. Errors and runtime state are observations, not precomputed expectations.

Each storage row keeps the seven prior observations and adds `persisted`, observed by reading original private fields without preparing an index or consuming work:

- `retainPreference`: the original AtomicBoolean preference, independent of successful loading.
- `loadedFromPersistence` and `persistenceBudgetDenied`: original graph flags.
- `stringIdentityCached`: the StringTable's semantic/persisted identity exists.
- `matchingStringIdsCount`, `matchingNodeIdsCount`, `projectedRowsCount`: retained object's original cache map sizes; null when no retained object exists.

These additional fields are JVM observations. A native test must explicitly describe which ones its public/storage interface can compare, and keep the rest as raw evidence. Likewise, JVM private storage methods remain callable after Close; this does not imply the native public observer remains valid after Close.

The fixture generator reuses bytes from the immutable previous actual-main writer archive. `prepare.py` records that archive identity, original writer identity, exact byte mutations, and valid-sidecar layout/CRC cross-checks. It preserves both the original prepared fixtures and deliberately malformed variants. Runtime fixture copies are distinct per source. No dispatcher override or diagnostic FastThrow flag is set. Two executions use independent processes and directories with the original JVM configuration.

The six original prepared controls remain named L04/L05/L12 with their original budgets and query. A bridge report compares common observed fields to their prior captured execution records; newly added observers and changed harness stack frames are separate metadata, not assumed regressions.
