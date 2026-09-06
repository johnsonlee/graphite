# Native standalone HTML CPU profiling delivery

Worktree `/tmp/graphite-go-native-html-profile-bbdfae2a`, base `bbdfae2a` (full SHA in manifest). No root, Attempt7, frozen audit or packaging-script files were edited. No commit or performance benchmark was run.

The patch completes the initial profiling draft with native main integration, correct CPU metadata/inline ordering, exact int64 JSON, offline search/zoom/back/reset/keyboard controls, bounded SVG rendering, tests and usage documentation. Only exact `GRAPHITE_NATIVE_CPU_PROFILE=1` opts in. `GRAPHITE_PROFILE` is the output path, default `profile.html`. Wrapper routing and JVM async-profiler behavior remain storage's separate work.

Start occurs before argument parsing and graph startup. execute returns after normal operation or handled SIGINT/SIGTERM, then Stop flushes runtime CPU samples, parses them in-process using pinned github.com/google/pprof, renders a self-contained report and atomically renames it over the selected output. main considers os.Exit only afterward. Disabled execution calls the pre-existing execute path. Report errors are explicit stderr/nonzero outcomes; old reports survive unsuccessful rendering/writes. Temporary raw/HTML files are private and removed on normal completion/failure. Abrupt SIGKILL cannot flush.

The tree reverses both leaf-first locations and inline frames according to pinned pprof proto/profile.proto lines 110 and 187–194. CPU uses cpu/nanoseconds, never sample counts; a period of a different type is not labeled as nanoseconds. Overflow/negative totals are rejected. JSON stores nanoseconds as exact decimal strings; UI BigInt keeps totals/tooltips/search percentages exact, while pixel widths use approximate ratios. Function/file text is escaped in embedded JSON and inserted only through textContent. Labels are combined with a visible notice, not retained or filtered. Native server has no pprof query labels today.

At most 8,000 frames / 200 levels are drawn in a view and subpixel frames are omitted; the complete tree remains embedded. Next match can zoom into hidden branches. Twenty-thousand-way fanout is covered without recursive spread calls. Recording/report size still scales with the complete sampled call tree. This is an optional profiling feature, not a performance optimization or a benchmark result.

## Verification

Final binary: `/tmp/graphite-native-html-profile-bbdfae2a-final`, SHA256 `7741020d44e4ea640626152923de90043989fe5d17c0e96fbcb2fd53674fb50d`.

- Full-module `go test -race ./...` and `go vet ./...` passed; logs retained. Formatting/diff check passed.
- Report tests assert concrete inclusive CPU totals, inline direction, metadata, empty/invalid/overflow cases, unsafe function/file strings, values above JS safe integer range, atomic replacement and large fanout.
- Session tests capture a real nonempty runtime CPU stack, private temporary permissions/cleanup, concurrent repeated Stop and explicit failures. The brief CPU workload proves sampling only, with no timing or throughput comparison.
- `verify-binary.py` passed nine real-binary scenarios: disabled help for three non-enabling values; enabled help/default path; invalid arguments with completed report; start-output failure; stop-output failure preserving an existing directory; disabled serve; enabled serve with SIGTERM flush. Enabled/disabled serve execute 30 identical real Cypher requests and retain their exact result. The enabled report contains actual native CPU stacks.
- CUA Chrome browser checks over a temporary loopback HTTP preview verify initial stats, search60%, inclusive search100%, zoom/back/reset, updated details, keyboard Enter, empty data, and Next match on a 20,000-branch report. A screenshot was visually inspected in the tool response. Concrete observed text is in `evidence/browser-observations.json`.

Browser constraints are explicit: the in-app browser was unavailable. Direct file:// navigation was rejected by Browser URL policy, with an instruction not to bypass it; no workaround was attempted. Direct file-protocol behavior and the escape/deep fixtures were not browser-verified. Escaping/precision/deep rendering have Go format tests, and all assets/data/code are inline. The local preview tab/server were closed after verification.

The evidence contains actual generated HTML and raw CLI/HTTP outputs; synthetic HTML serves format/interaction testing only. Neither binary requests nor synthetic fanout are latency/CPU-performance evidence.

## Reproduce

From `graphite-server/`:

```
go test -race ./...
go vet ./...
go build -o /tmp/graphite-profile-server ./cmd/graphite-server
GRAPHITE_PROFILE_UI_FIXTURES=/tmp/profile-ui go test ./internal/profiling -run TestProfileBrowserFixtures -count=1
```

From worktree root:

```
python3 native-profile-freeze/verify-binary.py /tmp/graphite-profile-server
```

See `graphite-server/internal/profiling/README.md` for end-user usage and semantics. The patch contains implementation/tests/docs only. This delivery directory holds evidence, command runner and manifest separately. The older integration binary previously sent to storage had SHA256 `87e5162d0421bc5f7051c703ea732d5c0bff3ba6a6d6424c6cc1525e93f461ae`; final source adds a nil-sample-type validation guard without changing its wrapper/env contract.
