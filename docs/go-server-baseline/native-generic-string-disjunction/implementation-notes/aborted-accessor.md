# Abandoned duplicate raw accessor

An initial implementation subtask created `generic_string_property.go` and its
test file for a broader `RawGenericStringPropertyID` accessor. Both newly created
files were deleted before staging or commit: the existing
`ProjectionPropertyStringID` already supplies precisely the concrete properties
admitted by the direct-string query compiler. Root chose to reuse that accessor
and `mainStringCandidates`, avoiding a duplicate API with unused capabilities.

One targeted correctness command ran before the redirection:

```
/opt/homebrew/Cellar/go/1.22.0/libexec/bin/go test -race -count=1 ./internal/store -run TestRawGenericStringPropertyID
```

It ran from the workspace `graphite-server` directory, session 84703, and exited
1. The available terminal output was:

```
--- FAIL: TestRawGenericStringPropertyIDOffsets (0.00s)
    generic_string_property_test.go:125: want raw ByteBuffer bounds error, got <nil>
FAIL
FAIL    github.com/johnsonlee/graphite/graphite-server/internal/store    0.437s
FAIL
```

The deleted test incorrectly expected `Long.MAX_VALUE` to fail a Field class
read. Persisted `offset + 1` wraps and decodes back to `Long.MAX_VALUE`; Java's
subsequent int narrowing plus the five-byte header wraps to address 4, which was
readable in that control. This was an erroneous test expectation, not evidence
of a failure in the retained production accessor. No success was claimed, no
rerun was made, and no performance measurement used the abandoned code.

The existing accessor's immediate absent response for unsupported property/kind
pairs precedes offset lookup; main's private helper looks up offsets first.
This difference is unreachable through the admitted direct-string compiler
pairs and is not a reason to broaden its capability in this optimization.
