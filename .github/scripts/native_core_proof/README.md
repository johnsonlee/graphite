# Independent native core comparison

This package ports the retained source16 comparator and its172 tests. It does not
run comparisons on import, start a JVM, or turn a partial producer into an
accepted pressure fixture. Python dependencies are standard library only.

Use package imports (for example `from native_core_proof import core_semantics`)
from the scripts directory. Run the tiny tests with:

```
python3 -m unittest discover -s .github/scripts/native_core_proof \
  -t .github/scripts -p '*_test.py'
```

`core_semantics.prove` retains its strict default and all explicit correction
switches. Source-backed corrections now also require the `source_rule=` keyword,
an absolute `{path, sha256}` reference to the existing
`graphite.local-array-source-rule.v1` JSON shape. There is no co-located rule file
or default local path. The rule still contains `arms.C`, `arms.B`, and `diagnosis`.
Each arm includes `revision`, `manifest`/`manifestSha256`,
`folding`/`foldingSha256`, and `adapter`/`adapterSha256`.

B and C in this comparison API mean actual and reference sides. A CI comparison
of arm A against accepted C therefore passes A's actual fixture/source manifests
in the comparison's B slot. No revision is relabeled: each source-rule revision
must equal both the source manifest revision and that side's actual fixture
`writerRevision`. The fixture's `sourceManifestSha256` must also equal the source
manifest hash. The existing field authority independently checks per-graph roots,
provenance and exact input JARs. Complete producer, source closure and toolchain
audits remain the caller's responsibility.

The exact old/new formatter expressions, typed-slot projection, classfile lookup,
collision policies, source visitation checks, all non-exception payload checks,
index/ordinal verification and complete topology algorithm are retained. Binding
uses explicit per-call inputs; it does not modify module globals or `sys.path`.
New `source_bindings.py` adds actual writer/source linkage and pins itself in the
resulting authority closure. Source/fixture/rule/diagnostic mutations fail checks.

`VerifyTopology.java` is the actual execution-prep14 wrapper, not the obsolete
Java wrapper stored beside source16. Its first four arguments remain actual root,
reference root, field-bijection.tsv and output JSON. A required fifth argument is
the absolute package directory containing the pinned Python helpers. The complete
`topologyProof` algorithm is unchanged. Compile against the audited writer.jar
when the full runner is implemented; no Java compilation is claimed by the tiny
Python tests.

The original limitations remain. Source Local corrections do not independently
prove SootUp synthetic-local inference; their receipt keeps
`syntheticLocalInferenceOracleClaim=false` and `strictEquivalence=false`.
Historical unsupported bytecode-inference counts are not reclassified. Formatter
model, type identity/cache/persistence production tests and actual source/dependency
bindings remain separate prerequisites for approving that migration model.
Method CP references do not establish invocation selection, and synthetic collision
recovery does not independently recompute newly recovered fingerprints. Every
previous narrow negative case remains in the retained tests.

Remaining execution integration includes current GTY01–05 declaration validation,
actual dictionary exports, fresh source-rule/formatter test authority, portable
bootstrap-marker export receipts, the per-pair binder, owned all64 runner and
completed producer-contract adapter. No full semantic or performance acceptance
follows from packaging these sources. Preserve all raw failures when that work runs.


## Fresh dictionaries and current declaration tables

`export_native_core_strings.py` independently rechecks an actual CI producer
audit, compiles the unchanged dependency-only `ExportStrings.java` against that
writer's pinned JAR, then runs64 owned export phases sequentially. All Java work
uses4g/APC4 and runs outside construction timing and source/runtime/graph roots.
The independent raw audit checks every exact command, terminal process cleanup,
compiled helper closure, source/dependency/input hashes, complete GSO01 semantic
content, output closure and full ordered64 index. It retains failure evidence
and stops at the first failed phase. No server/query API is used for dictionary
contents and no composite generic strings are stored in production by this tool.

```
python3 .github/scripts/export_native_core_strings.py   --artifact-audit native-pressure-artifacts/B/artifact-audit.json   --source-inputs native-pressure-artifacts/source-inputs.json   --output native-pressure-artifacts/B/core-string-exports
```

`--audit-only --output <existing-export-root>` rechecks existing raw evidence and
writes a new `audit.json`; it refuses to overwrite an existing audit. Both the
export record and independent audit keep completeSemanticEquivalence=false and
performanceAcceptance=false. The index retains `graphite.string-exports.v1`.
The export audit is a fresh CI format; historical local export receipts stay
unchanged. A future producer binder must bind the complete fresh audit before
passing any one of its rows to `declarations.load(graph_root, export_row)`.
That function independently resolves GTY01–05 through actual serialized string,
metadata and export hashes. Decoder validity is not source completeness or
cross-arm equivalence. The frozen structural reader's policy and17 tests are
retained with package imports only.

Bootstrap-marker export and actual formatter/source-rule authority binding,
per-pair source-model approval, complete core/topology execution and producers.v1
completion are still separate pending work. No status in this stage replaces them.

The decoder additionally requires forward.properties to authorize the actual graph.types SHA-256. Missing authority/orphan sidecars, malformed or duplicate property syntax, wrong digests and missing/symlinked bound tables fail closed. Uppercase digests follow production matching semantics; unsupported escaped property forms are conservatively rejected. Prefeature graphs without declarations remain an explicit caller path.

The portable bootstrap stage runs the unchanged ExportSerializable bridge against
an independently audited C/actual producer pair sharing the exact JDK module
image. Its two owned phases, raw class bytes, module receipt and actual producer
inputs are independently replayed. The new Marker audit branch retains the old
historical receipt path unchanged. This authority admits only java.io.Serializable
as the exact empty bootstrap interface; ordinary corpus definitions still take
precedence and method lookup still strips this field-only authority. It never
makes an inference or complete-semantic claim. A future whole64 runner should
reuse one verified pair authority rather than replaying complete artifact audits
for each graph; the standalone branch currently favors complete verification.
