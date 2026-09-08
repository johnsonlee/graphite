The first archived-evidence verifier completed artifact/input-source/fixture
audits, then failed at its stronger assertion that the independent raw output
JSON files were byte identical. The earlier comparison had established complete
parsed JSON equality. The output producer uses Java `Map.of` for its top-level
object, whose member iteration order differs between JVM processes. No array,
row, error, diagnostic, or state value differs.

The verifier now compares the entire parsed JSON value, preserving every array
order and every field, and separately verifies that the convenient root
`main.json` is a byte-exact copy of `main-capture/main.json`. No captured output
was rewritten. The failed assertion was a verifier mistake, not a main executor
failure. Final verification is recorded separately.
