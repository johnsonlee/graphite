# SwiftPM shared source index correctness

Manual diagnostic run [41881203176](https://github.com/johnsonlee/graphite/actions/runs/41881203176) compared the historical temporary and workspace roots in one pinned Linux image. Its archived ZIP SHA-256 matches the Actions digest, `1f541d488a7b6afad966281035a1c5161d2ea667e9abad0753ad03f387832b26`.

Both builds used baseline `0818c68ecd084e14e80a978f7940c6d9d03e1a9a`, the same frontend binary, importer JAR, IndexStore library, SwiftPM Git tree, dependency lock and 12 dependency checkout revisions. All 481 selected relative paths and file hashes agree. File, type, method, field, call, constant, annotation, node and edge totals agree. Both IRs imported and their saved graphs each returned 4,555 methods.

| Shared physical source | Temp module | Workspace module | Occurrences per root |
|---|---|---|---:|
| PackageDescription/PackageDescriptionSerialization.swift | PackageLoading | PackageDescription | 591 |
| PackageLoading/ContextModel.swift | PackageDescription | PackageLoading | 83 |
| PackagePlugin/PluginMessages.swift | PackagePlugin | SPMBuildCore | 679 |

These were the only files with different normalized compiler facts. Both package descriptions list the two targets for each physical source. The pinned SwiftPM source tree contains three corresponding symlinks. Graphite canonicalizes their paths, then reads `symbolOccurrences(inFilePath:)` once per distinct path. At the pinned IndexStoreDB revision this operation stops at the first matching provider. It therefore returns only one compilation context even though both modules compile the source. The first provider differs between the two fresh stores. The artifacts establish the selected modules, though they do not contain the two databases' internal unit ordering. [Pinned reader](https://github.com/swiftlang/indexstore-db/blob/54212fce1aecb199070808bdb265e7f17e396015/lib/Index/SymbolIndex.cpp#L506-L530)

The occurrence and relation USR sets have 347 values unique to the temporary root and 315 unique to the workspace, producing the observed 39,150 and 39,118 counts. The IR has 60 strings unique to the temporary checkout and 31 unique to the workspace, producing 21,459 and 21,430. Each of those 91 values has an actual reference in a field owner or type, a type relation, or a call-site callee. The post-import count alone misses the semantic difference.

Decoding string IDs and comparing entity multisets found 80 class origins, 142 field tuples, 20 type relation tuples, 13 caller/callee/line tuples and two method signatures unique to **each** side. In the temporary-root graph, line 36 calls `FoundationEssentials.JSONEncoder.init()` from `PackageDescription.ContextModel.encode()`. In the workspace graph the same call belongs to `PackageLoading.ContextModel.encode()`. The source declares both compilation contexts.

A repair should enumerate each distinct compiled context for shared sources, retain its module and USRs and collapse only duplicate observations of the same semantic occurrence. Parse shared source text once where possible. Qualify position-based declaration maps and same-callee/same-position call deduplication with the owning context so the recovered calls and annotations survive emission. Validate using two targets compiling one symlinked source in two checkout roots. Both saved graphs should expose both module-qualified declarations and both callers of a shared callee. Preserve the original baseline pin until any explicit baseline transition can be justified independently. This diagnostic contains no performance measurements.

[shared-source-baseline-analysis.json](shared-source-baseline-analysis.json) contains the full per-string reference slots and example entities. The original artifact and analysis script are retained locally under `/Users/johnsonlee/.codex/benchmarks/apple-validation/apple-index-diagnosis-41881203176/`.
