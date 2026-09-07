# Independent signal integration verification

The five-file signal delta was applied without modification to clean base
`e6c3bd0c4a6f73ec85ce892bd173750dd7d62c0a` in
`/tmp/graphite-go-signal-root-e6c3bd0c`. `source.json` identifies every changed
file, the provider manifest and version `3.0.0-root-signal-audit`.
`frozen.patch` preserves the delivered delta; the provider's main/before/after
evidence remains in the adjacent `native-signal-parity` directory.

Actual native TERM and INT now return 143 and 130 after shutdown and profile
flush, including when saving the report fails. Ordinary help, parse failure and
profile-save failure retain their existing statuses. This closes the previously
observed main/native discrepancy; it does not change query execution.

Independent checks all passed:

- Whole-module `go test -race ./...` and `go vet ./...`.
- Default four-target Gradle packaging, both shadow JARs and both installDist
  distributions, followed by all four actual packaged entry-point smoke checks.
- Seven actual Darwin process cases, including direct TERM/INT, Homebrew native
  TERM, nested argument-file JVM bridge INT, and TERM with report-save failure.
- Both packaged native profile routes and nine profile CLI cases. The new
  `profile-cli-verify.py` changes only the historical harness's TERM expectation
  from 0 to 143; `profile-cli-harness.json` identifies its original. Historical
  profile receipts and their original verifier remain unchanged.
- Release preparation validates all four actual binary versions and every
  embedded JAR binary. Checksum-verified release archives feed the production
  Docker staging helper. Actual Linux ARM64 and AMD64 containers run as native
  PID 1, serve the persisted fixture and exact UI, and exit 143 after Docker stop.
- Additional ARM64 container TERM and INT cases return 143/130 and yield parsed
  HTML profiles copied after the process has stopped.

`verification.json`, `runtime-verification.json` and `docker-verification.json`
retain commands, exit codes and logs. Individual receipts retain complete
catalogs, image IDs, process arguments and report hashes. `final-verification.json`
rechecks source identity and archive/JAR/resource agreement after these runs.

These are correctness observations on macOS ARM64 with Go 1.22 and Java 17, and
the local Linux Docker engine. AMD64 Docker execution uses emulation; physical
AMD64, Windows console behavior, repeated signals and SIGKILL are unverified.
No remote release or performance run is claimed. Profile files establish flush
behavior only. The independent query DISTINCT delta was absent from this clean
signal build; its later combined module verification has a separate receipt.
