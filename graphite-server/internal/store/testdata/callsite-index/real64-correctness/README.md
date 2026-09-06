# Real 64-graph index validation

All 64 original persisted graphs passed full v2 index header, content-identity, numeric CRC32, CSR directory/posting offset order and trigram validation. The command exited 0. Each Store was opened in MAPPED mode and closed before the next graph, followed by Go GC; no query or HTTP process was started. Original graph files were only read. This is correctness evidence, with no timing, allocation or performance conclusion.

```sh
python3 graphite-server/internal/store/testdata/callsite-index/real64-correctness/run.py /tmp/graphite-go-callsite-index-reader-90787051 /tmp/pr113-exp037-fixture.nXn4fg/graphs.tsv
```

`run-identity.json` records the exact Go command, environment, base revision, full Go source hashes and input manifest hash. `run.log` contains unedited test output; `exit-code.txt` is 0. `validated-indexes.json` records every graph's index SHA-256, byte count, header identity/counts and actual store catalog counts. `summary.json` aggregates them. The runner derives these artifacts from the test log and does not inspect or alter original files beyond the Store/index reads. `module-validation.*` records the final full-module race and vet commands/results.

| Validated quantity | Total |
| --- | ---: |
| Graphs | 64 |
| Serialized nodes | 19,431,891 |
| Methods | 1,374,983 |
| Edges | 20,448,885 |
| AnnotationNode records | 0 in every graph |
| Index bytes | 368,089,488 |
| CallSites | 5,046,935 |
| String-table entries | 2,793,940 |
| Trigram postings | 31,587,846 |
| Unique caller-class IDs | 126,308 |
| Unique caller-name IDs | 423,532 |
| Unique callee-class IDs | 372,571 |
| Unique callee-name IDs | 612,447 |

The manifest's SHA-256 is `809c8b8ceed25428af65350edfa2c391f449051bf069b12555df245099f26ae2`. These totals agree with the original header inventory and catalog. Zero AnnotationNodes is a property of this corpus; a general query planner still needs correct handling of other node types. This run checks the loader's documented structure/integrity contract, not adversarial semantic authenticity or every node's full decoding. Trusted identity sidecars avoid identity rederivation, as documented in the Store README. The independent pinned-main tiny fixture remains the source of exact directory/ID/signature/trigram API comparison.
