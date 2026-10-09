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
