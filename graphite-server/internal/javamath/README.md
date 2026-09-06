# Java 17 ARM64 Math numerical target

This pure Go package provides `Sin`, `Cos`, `Tan`, `Exp`, `Log`, `Log10`,
`Sqrt`, `Asin`, `Acos`, `Atan`, and `Atan2`, with the corresponding Go `math`
function signatures. `Atan2(y, x)` keeps the usual argument order. There is no
JVM, C library, cgo, or runtime oracle dependency.

The target is the project's baseline runtime: Homebrew OpenJDK **17.0.18+0,
HotSpot ARM64**, default intrinsic settings. It is deliberately more specific
than the Java `Math` API's permitted error bounds. This is not a claim that all
JVM versions, CPU architectures, or intrinsic settings produce identical bits.

## Implementation and sources

The algorithms and polynomial constants are translated from the permissively
licensed [Sun fdlibm sources](https://netlib.org/fdlibm/). Their copyright and
permission notices are preserved in each derived Go file. Download URLs and
SHA-256 hashes appear in [testdata/provenance.json](testdata/provenance.json).
OpenJDK source was inspected to identify the runtime target; its GPL assembler
and Java implementation source are not included or copied into this package.

`Sin` and `Cos` evaluate the fdlibm polynomials with explicit `math.FMA` calls
and retain the target's original argument interval for compensated cosine
subtraction. Their argument reduction also specifies where operations fuse.
This reproduces the ARM64 intrinsic's rounding; using `StrictMath` for these
functions would change 266 results in the stored corpus. The relevant runtime
entry points are in OpenJDK's
[ARM64 stub generator](https://github.com/openjdk/jdk17u/blob/jdk-17.0.18%2B8/src/hotspot/cpu/aarch64/stubGenerator_aarch64.cpp).

The other transcendental functions preserve fdlibm's separate multiply/add
rounding with explicit conversions. In particular Go `math.Exp`, `math.Tan`,
and `math.Log` are not substitutes for this target. `Sqrt` uses the standard
library's correctly rounded IEEE-754 operation. Other standard-library calls
perform exact bit manipulation, scaling, sign handling, or explicit fusion.

## Independent correctness oracle

[MathOracle.java](testdata/MathOracle.java) calls `Math` and `StrictMath`
directly. It saves Math results before and after 50,000 discarded calls to
exercise JIT compilation. No timings are measured. There are 2,741 inputs per
function, plus an additional 169 signed-zero/infinity/subnormal `atan2` pairs:
30,320 cases in total. Inputs include fixed numerical edge cases and their
adjacent binary64 values, deterministic random raw bits and ordinary values,
polynomial branch boundaries, and multiples of pi/2 with adjacent values.

All non-NaN results compare exact binary64 bits, including signed zero and
infinity. NaN results compare by classification; payload/sign propagation is
not part of this package's contract. The stored Java oracle has no cold/warm
differences. Math differs from StrictMath on 150 sine and 116 cosine cases;
the other nine functions agree in this corpus. This is finite coverage, not
an exhaustive proof across all binary64 inputs.

From `graphite-server`, regenerate and verify with the recorded Java runtime:

```sh
java -Xmx128m internal/javamath/testdata/MathOracle.java > /tmp/javamath-oracle.tsv
python3 - <<'PY'
import gzip, pathlib
p = pathlib.Path('internal/javamath/testdata/java17-arm64.tsv.gz')
actual = pathlib.Path('/tmp/javamath-oracle.tsv').read_bytes()
assert actual == gzip.decompress(p.read_bytes())
PY
go test -race ./internal/javamath
go vet ./internal/javamath
```

The fixture contains Math cold, StrictMath, and Math warm bits separately.
Tests also assert the observed Cypher regressions: `Exp(1)` is
`2.7182818284590455`, and `1/Tan(1)` is `0.6420926159343306`.
No performance measurements or speed claims accompany this package.

## Scope limits

The exported functions use the ARM64 Java target even when Go runs on another
architecture; they do not select that host's JVM behavior. Only the recorded
macOS ARM64 runtime has been used as the execution oracle. Alternate JVMs,
disabled intrinsics, and NaN payload details are outside the verified target.
Functions such as `Pow`, `Cbrt`, `Hypot`, and hyperbolic functions are not part
of this package. Integrating the package into query evaluation is a separate
change.
