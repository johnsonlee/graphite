# HTTP JSON accessor and error-order differential

Pinned main: `4e328b0109e13c896b74004823fb049fcb19251a`, using the previously
identified main fat JAR. These 213 cases use one JVM-produced tiny persisted
fixture for correctness only. There are no performance observations.

`capture.py` records the exact initial generation/launch logic. Both process
commands are retained. `initial.json` contains all baseline/candidate responses
and the original 96 differences. `candidate.json` contains the corrected native
binary identity and 213 complete response matches against the frozen baseline.
Only the temporary data-directory prefix and descriptor `loadedAt` are normalized
in the checked-in unit-test oracle. Status, content type, array order and all other
response values remain exact.

Cases cover GET/POST root/scoped/selected queries, empty queries, missing versus
null fields, JSON object versus other JSON values, singleton-array/string/number/
boolean coercion, graph selectors, timeout fields, and graph load path/mode errors.
Gson accessor errors and endpoint-specific error precedence are preserved, including
main's generic 500 response when timeout processing receives a non-object body.
The single-node/relationship engine remains fully native.

This corpus covers valid JSON values and their accessors. Lenient/malformed Gson
syntax, broader malformed HTTP protocol behavior and remaining query error-string
boundaries require additional verification; this result is not full server parity.
