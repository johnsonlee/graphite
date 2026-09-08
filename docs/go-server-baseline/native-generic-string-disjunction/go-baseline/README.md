# Frozen Go 513e2b96 generic disjunction baseline

This is a correctness diagnostic, with zero performance measurements. The full
Go module was copied before candidate edits at revision
`513e2b96d4cd5136560461d06f3b804e6aa67c41`. The original module snapshot contains
2,534 files; an independent Git-object audit verified all 2,530 tracked module
files against that revision. The sole added module file is the diagnostic test
helper archived here. Production files and workspace tests were not edited.

All 201 inputs from the parent original-Java oracle were captured in original
order: 181 public requests and 20 provider-wrapper controls. The capture process
exited 0 after writing every result. The comparison controller exited **1**,
retaining every actual difference against each of the two original JVM runs.

| Comparison against each original JVM run | Equal | Different |
| --- | ---: | ---: |
| Public output, errors, phase, retained/mapped state | 157 | 24 |
| Provider-wrapper payload/error/state controls | 19 | 1 |
| Total | 176 | 25 |

The 24 public differences comprise two original empty-needle fallback row-order
controls, six isolated-surrogate output differences, and 16 malformed-payload,
load-boundary, or generic-routing differences. Both original empty-needle outputs
are compared separately and retained unchanged. Rows are never sorted to make
results pass. For the six surrogate cases, Go returns UTF16 `[65,63,66]` while
original Java returns `[65,55296,66]` or `[65,56320,66]`. The input parameter
encoding preserves UTF16 explicitly; these are output differences.

The provider payload-ID control illustrates a scope difference: original private
`lookupStringPropertyDisjunction` yields FieldNode ID 1152, while Go's
`mainStringCandidates` wrapper runs an additional canonical-order lookup and
raises IndexOutOfBoundsException before yielding. This helper tests the existing
Go wrapper, including its merge/order behavior. It does not claim to expose an
API identical to original Java's private provider, even for matching controls.

Public single-source calls use internal `executeSources` with an explicit
context and `WorkTrackingEnabled: true`. Cross-source calls use
`ExecuteCrossWithOptions` with the same work flag and the original `scoped`
policy. Error records preserve simple query exception class, null-message state,
and UTF16 message. Raw Go loading failures without an equivalent query exception
are explicitly identified by Go type. Provider records retain every successfully
yielded prefix with payload type, ID, and `objectString` UTF16 content.

Go has no equivalent exposed main diagnostic counters. Captures record those as
unavailable; original Java diagnostics remain in the parent raw captures.
Reported equality covers only the explicitly compared fields, not work-budget,
fast-path-counter, resource, or full server fidelity.

All 3,036 original per-case fixture files remain byte-identical before and after
execution. Only 22 optional `graph.callsite-string-index` files were added. The
49 original fixture variants (588 files) were checked against the parent archive
manifest. All compiled module files remain unchanged. Compiler, linker,
dependency package input hashes, command logs, exact executable, full module
source, raw results, and unfiltered comparisons are archived. Dependency hashes
were recorded after the successful build, not falsely presented as a pre-build
input snapshot. The module and explicit oracle/helper inputs were hashed before.

Reproduce into a fresh module copy and output directory:

```sh
python3 docs/go-server-baseline/native-generic-string-disjunction/go-baseline/run.py \
  --module /absolute/path/to/fresh/frozen-module \
  --output /absolute/path/to/new-capture
```

`run.py` intentionally refuses an existing output directory or helper file and
returns nonzero on mismatches. `source.tar.gz` contains the exact compiled module,
including the helper; remove only its
`internal/query/generic_disjunction_diagnostic_test.go` in a fresh extracted copy
before using the runner. `audit.py` records the original external capture and
Git verification. `verify.py` independently verifies this repository archive
without rerunning either runtime. No files have been committed by this worker.
