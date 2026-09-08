# Frozen benchmark builder independent review

Reviewed `native64-p95-sampling/prepare-frozen.py` SHA-256 `849e228ddc7864834cace9297e9511cc8463f3c1b907db01e48be4b4e5417d59` against original `prepare.py` SHA-256 `e93a3af81334bb188147565a1b8410c09881c91d1a7c6a38f3319d16ae3de8b6`. This review runs no builder, tests, Go, JVM, or benchmark. The scripts and existing evidence were not changed by the reviewer.

## Finding: bind the manifest bytes at initial read

P2, `prepare-frozen.py:31,68,89–94`: the script parses `expected` before capturing the manifest file’s SHA. If the manifest is replaced between line 31 and line 68, a candidate and copied source matching the original `expected` can still pass every source check. The later manifest check only compares the replacement against its own later input hash. The receipt can consequently associate `correctnessManifestSHA256` with different contents from those used to compute `sourceIdentitySHA256`.

Minimal correction: read the initial manifest bytes once, compute their SHA and parse those same bytes, then require the manifest hash at input capture and final verification to equal that initial hash. Emit the saved hash in the receipt. Re-parsing and comparing to `expected` is also sufficient for semantic binding, but capturing the exact initial bytes additionally fixes the raw provenance claim. This is a source-proven binding window; it is not a report that concurrent mutation occurred in an existing run.

## Preserved behavior and scope

The candidate is compared against the complete supplied map before copying, both candidate and copy are compared after copying, and both are checked again after building. `source-sha256:` plus a canonical map digest identifies uncommitted engine contents. Workspace HEAD is separately labeled `workspaceRevisionAtBuild`; the new receipt explicitly declines a commit-match claim. The removed original HEAD matching check is therefore replaced by a different, explicit identity contract rather than silently omitted.

The diff preserves original main-tooling verification, Go environment/dependency discovery, Cgo/Swig rejection, dependency/toolchain hashes, Java classpath/toolchain inventory, build command, runtime environment recording, and binary hashing. It does not change either runtime’s timed entry point. Input/output paths are passed as subprocess arguments, not interpolated shell commands; expected manifest keys are checked against discovered relative paths rather than used as arbitrary copy destinations.

`run.py:108–118` still takes the measurement lock, binds the build JSON and input hashes, checks native binary identity and main classpath, and verifies recorded inputs. `run.py:133–139` still writes `measurementAcceptanceEligible=false` and explicitly identifies original case 821 gate failure and warm-after-failed-prewarm as diagnostic continuation. The new builder does not legitimize formal warm sampling or confer correctness/performance acceptance on its source identity.

The supplied manifest is an identity assertion, not proof of a passed correctness gate; the caller must separately bind it to actual correctness receipts. The existing enumeration hashes file contents, not file metadata or symlink topology, and `Path.rglob` does not traverse symlinked directories. A claim covering arbitrary linked source layouts would require an explicit policy. This is an existing enumeration limitation, not a demonstrated issue in the specified frozen candidate. Checks rely on ordinary Python assertions, as in the original builder; optimized Python execution that disables assertions is outside the verified invocation protocol.

No build success, mismatch-rejection execution, full-source filesystem equality, formal warm eligibility, or P95 conclusion is established by this source-only review. The manifest binding finding should be corrected before treating a new build’s provenance receipt as complete.

## Resolution follow-up

The parent corrected the main binding window in script SHA-256 `b79c4f8e448c777bd84aa19a999c8905c1993014405a8025e12e1d9519aa8bde`. The reviewer independently reread the diff: initial bytes now supply both parsed expected contents and the initial SHA; those exact bytes are copied to `correctness-source-manifest.json`; both the external and copied manifest are bound into inputs; the captured external hash and final external hash must equal the initial hash. The unchanged post-build input loop also verifies the copied bytes. No builder or rejection-control execution was performed by this reviewer.

One final receipt expression still re-reads `sha(manifest_path)` after the final assertion in this reviewed version. The reviewer requested replacing it with `manifest_initial_sha` so the emitted provenance uses the already verified bytes directly. This follow-up preserves the original finding rather than replacing its historical record.

## Final resolution confirmed

The reviewer independently reread final script SHA-256 `bf69528addfa2eab6de49ab6ede2581ff844075b6982f2293de26bbc42418552`. `correctnessManifestSHA256` now emits `manifest_initial_sha` directly. The canonical `module-source.json` is added to the paths hashed into build inputs before compilation, checked by the existing post-build verification loop, and its receipt field `engineSourceManifestSHA256` uses that saved input hash. The initial manifest bytes, expected source map, copied manifest, canonical manifest, and final provenance fields are now consistently bound. The reported P2 finding and its final receipt follow-up are fully resolved at source-review level; no remaining blocking finding was identified in this bounded diff review.

The parent reported three earlier CLI rejection controls (changed hash, missing path, extra path) passing before this metadata-only correction; this reviewer did not independently execute or inspect their runtime evidence and does not claim a successful actual build. No Go/JVM/build/test runtime was launched. Original timing and diagnostic warm/acceptance limitations remain as recorded above.
