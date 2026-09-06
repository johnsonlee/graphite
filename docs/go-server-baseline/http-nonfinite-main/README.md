# Non-finite query results over HTTP

Thirty POST requests were captured from pinned main `4e328b0109e13c896b74004823fb049fcb19251a` using Java17.0.18/Gson2.11.0 and one tiny persisted correctness fixture. The dedicated JVM on port19872 was stopped afterward. Full requests, response bytes, headers, commands, identity, and logs are preserved here.

All three surfaces—root, scoped, and explicit graph selection—behave identically for these expressions:

| Query result | Status | Response |
|---|---:|---|
| `1e999`, `-1e999`, `sqrt(-1)`, `log(0)`, `toFloat('NaN')`, `toFloat('Infinity')` | 500 | Javalin `Server Error` problem JSON |
| Non-finite values nested in lists or maps | 500 | Same problem JSON |
| `0.0/0.0` | 400 | `Division by zero`, `cypher_query_failed` |
| `sqrt(4)` | 200 | Numeric `2.0` |

No response contains a bare NaN/Infinity token. The query evaluator can produce a non-finite double, but Gson then fails during response serialization. Native query serialization now uses a typed error to map that phase to500; evaluation errors retain their original400 handling.

`TestHTTPNonfiniteAgainstMain` compares status, Content-Type, and complete parsed JSON using number-preserving decoding: **30/30 pass**. This is a correctness check and has no performance interpretation. Reproduce the capture with `python3 docs/go-server-baseline/http-nonfinite-main/capture.py --out /tmp/new-nonfinite-capture` in a new output directory; run the native test from `graphite-server`.
