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

The fresh formatter binder consumes the independently replayed marker/producer
pair, checks the exact reviewed renderer expressions, seven adapter method
boundaries (including both method-descriptor overloads), identity caches and
unchanged dependency/build definitions, then writes the existing local-array
source-rule schema. Identical writer aliases must use strict comparison without
this correction rule. Its source-only report explicitly keeps
productionFormatterTestsVerified=false and syntheticLocalInferenceOracleClaim=false.
Production Kotlin test execution and full core/topology/index comparison remain
mandatory separate authorities. The source contract is a portable textual copy
of the reviewed source fragments; its historical sourceReview path is provenance
only and is never loaded by the binder.

The production formatter correctness stage executes the exact reviewed three test
classes (21 cases) already present in the actual parent/candidate checkout. It
never copies candidate tests into C. The pinned producer JDK and existing isolated
Gradle homes/cache are reused, with --no-daemon and fresh isolated test results;
all Test forks are bounded to4g/APC4. The original source/runtime/fixture artifact
audits are replayed before and after. Raw JUnit XML requires the complete exact
case set without failures/skips. Actual ordered test classpath contents are bound
before/after, and every resolved matching Graphite/SootUp production class is
compared to writer.jar, with required formatter/cache/persistence classes present.
This proves the selected actual writer model tests, not universal bytecode
inference or complete graph equivalence. Correctness time is outside performance
boundaries. Absent tests in a distinct old parent fail rather than being backfilled.

## Corrected fixture comparability for native pressure

The producer assembler can consume the completed actual64 core/topology/index
execution audit for each non-accepted arm. The139-phase raw-Local path is required:
both actual writer exports, every graph's complete persisted Local inventory and
zero unproved array identities. It retains the classfile/source correction counts,
actual pair revisions, fixture manifests, raw reports and complete upstream pins.
The old audit's historical Local warning is not silently deleted; the new scoped
binding separately establishes precisely the array authority supplied by those
reports. Non-array inference is not a new claim.

This is an explicit corrected-comparability mode, not strict semantic equivalence.
`completeSemanticEquivalence`, `strictEquivalence` and
`sourceToDeclarationCompletenessClaim` remain false. Existing declaration parser,
wire and query correctness gates cover the additive feature; this binding does not
claim that every source Signature was independently reconstructed. Partial artifact
and39-response bundles still cannot produce a pressure plan. The original strict
proof path remains available and unchanged in scope.

Preparation reuses the actual independently audited runtime, saved readiness and
all39 captured complete responses and their original expected payloads. It launches
no server and does not alter old receipts. The pressure validator replays the bound
producer evidence and matches every planned request, graph scope and oracle to it.
The fixed CABBAC/c4/2-warmup/20-sample protocol and resource limits are unchanged.
No record or plan readiness establishes a performance pass; actual pressure results
remain necessary. Missing raw proofs, incomplete pairs or conflicting corrections
block preparation.

### Construction uses the same sealed writers

`run_real64_construction.py --producers <packet.json> --base-sha <A> --candidate-sha <B>
--output <fresh-directory> --prefix <report-prefix>` consumes the corrected bundle
and the already built writer JARs. It does not compile or start query servers.
It measures six sequential fresh64 outputs in fixed CABBAC order with the same
four source JARs, JDK, `-Xmx4g` and `-XX:ActiveProcessorCount=4`. Each timed writer
must finish saving all graphs, indexes and manifests, its embedded readback, and
normal exit. A second `--verify` and full inventory are mandatory untimed gates.
Source/JAR reads before each cell are matched; disk-cold behavior is not claimed.

After the second readback and full inventory, identical completed output files
share storage through hardlinks to sealed producer files or earlier completed
outputs. This untimed retention step checks actual SHA-256, size, file mode and
filesystem before replacing an output inode. Every measured writer starts with
fresh output paths; all output paths and manifests remain available, and the
final audit still checks their complete bytes. This bounds duplicate disk usage
without changing the construction measurement or claiming a memory saving.

`audit_real64_construction.py` independently reconstructs the original GNU time,
owned phase exits, exact writer commands, provenance, index bytes and saved64
inventory. Cross-version comparability belongs to the pinned same-source writer
bundle; each fresh output has its own validity checks. Nondeterministic saved
bytes are not treated as a semantic difference, and complete semantic/source
Signature completeness flags remain false. Failed and unissued cells are retained.

`benchmark-construction.mjs` rechecks archived raw resources and both fixed
accepted-baseline comparisons. Each requires wall time no greater than baseline,
and CPU and RSS individually within5%. This does not apply the separate accepted
Native loading tradeoff and cannot authorize loading or query acceptance.
The workflow must actually execute the six cells: neither a prepared plan nor the
producer's initial single CAB construction capture can satisfy this operation.

### JVM loading ends at complete registry readiness

`run_real64_loading.py` takes the same producer/base/candidate/output/prefix
arguments and reuses its sealed CLI JARs and saved64 fixtures. It validates the
actual packaged `MainKt serve` classes/options before launch; the minimized CLI
JAR need not contain the standalone explorer entrypoint. Six fresh processes run
sequentially in CABBAC order with the same JDK, `-Xmx4g`, APC4 and MAPPED mode.
The fixed boundary starts before the owned wrapper spawn and ends after consuming
and validating the complete `/api/graphs` response against all saved counts.
There is no oracle query, warmup or measured query. Startup topology work is
included; deferred first-query work remains a separate query obligation.

Linux cumulative process CPU is charged from process zero, including early JVM
startup before the first sample. Complete samples within the boundary give the
lower bound; the first post-ready sample gives the upper bound, with two clock
ticks of uncertainty. RSS uses the in-boundary sampled maximum as its lower
bound and the first post-ready `/proc/PID/status` VmHWM as a conservative upper
bound, checking PID/start identity on both sides. The original status/stat reads,
timestamps, complete readiness bytes, owned cleanup and all failures are kept.
GNU time covers shutdown too: its peak only corroborates VmHWM and never replaces
the ready peak. The audit and comparator independently reconstruct these bounds.

The comparator requires both fixed accepted-baseline directions to satisfy wall
time and independent CPU/RSS limits. An uncertain interval cannot pass, and the
Native loading time/CPU exception does not apply to JVM loading. Input reads are
matched, not disk-cold. Complete semantic/source-declaration claims remain false;
this evidence cannot satisfy construction or sustained query requirements. CI
must actually run and audit all six cells before loading becomes available.
