# Attempt 6: CallSite dictionary candidates

This candidate is based on native commit `1c235916`, which already includes Attempt 5, EAGER encounter order, Constant membership, LIMIT 0 handling, and the frozen optional CallSite index reader. The reader is a prerequisite, not part of this optimization delta. No 64-graph runtime, performance measurement, or commit was performed by this implementation task.

## Scope and eligibility

The provider handles the initial mandatory MATCH in each UNION branch: exactly one named node, no inline properties, no path/relationships, no previous bindings, and either no label or one CallSite/CallSiteNode label. The whole WHERE must be a pure OR tree of `=`, `CONTAINS`, `STARTS WITH`, or `ENDS WITH` atoms. Each left operand is one of caller_class, caller_name, callee_class, callee_name, wrapped by toString, coalesce with exactly a literal empty fallback, and at most one toLower/toLowercase. Exact arity, DISTINCT/star flags, the node variable, and every OR arm are checked structurally. RHS terms are string literals or directly supplied string parameters. There is no query-text or 42-case whitelist.

These four properties are non-null strings on CallSites. They are null on every other node kind except AnnotationNode, whose dynamic properties require general evaluation. Untyped sources containing any Annotation fall back. The compiler propagates whether wrappers turn a null property into an empty string; any untyped arm that can then match an empty RHS rejects the whole plan. This covers the coalesce/empty-needle counterexample. A right-hand parameter that is null, missing, or not a string also falls back without evaluating it during planning.

All unsupported forms use the existing complete evaluator: OPTIONAL, later MATCH, UNWIND/correlated bindings, multiple nodes, inline properties, incomplete OR, AND, negation, regex, reversed comparisons, arbitrary function calls/arguments, multiple lower operations, and other labels/properties. This is a capability boundary for an optimization, not a language restriction.

Only MAPPED Stores use the provider. EAGER remains on the loaded-value scanner, including when the original directory is no longer available. The source graph selection order is unchanged, IDs are deduplicated only within their own graph, and candidate unions preserve CallSite node-data offset order. No per-source LIMIT, projection shortcut, output-value deduplication, or early aggregation is added. Every candidate is fully decoded and still runs the original pattern checks, WHERE, binding/provenance logic, projection, aggregation, DISTINCT, ordering, and final materialization.

## Dictionary filtering, not trigram search

Every eligible query scans the relevant CSR string directories. It applies the existing exact predicate and Java ROOT transformation to directory values, obtains postings for matching strings, and merges matching IDs by data offset. A lowercased value is reused only across atoms for the same directory entry. The standard evaluator's UTF-16/WTF-8 matching semantics remain in use.

The implementation does **not** consult trigram postings or signatures. It is not a trigram-search experiment. It does not add a result cache or persistent transformed-string cache. Dictionary scans and lowercasing of unique strings remain per-query work and are included in query timing.

## Cold certification and Store lifetime

Index availability alone cannot establish equivalence to the previous scanner. A cold eligible query first asks the frozen reader to validate its optional format/CRC/CSR, then runs additional certification inside that query:

1. Completely decode every native core node. This proves that skipping noncandidates cannot hide an error that the existing native decoder would raise. It is not a proof of every JVM decoder behavior.
2. Check that CallSites in the original NodeIDs stream have strictly increasing data offsets. A reordered legacy nodeindex can otherwise make a sorted posting union change existing encounter order.
3. Check all four CSR directories/postings against actual raw CallSite string IDs. Unique directory keys, strict posting-row offsets, correct property association, and the exact total CallSite count together prove complete, duplicate-free membership. This closes the reader's deliberately retained identity-trust gap for the postings used by this optimization. Trigram/signature semantics are irrelevant because this candidate does not use them.

All preparation completes for all selected graphs before emitting any candidate. An unavailable sidecar, unsupported mode, failed decode/order/association proof, or other optional failure returns to the original scanner without publishing partial rows or throwing the discovered core error early. Even an available index producing zero candidates requires the proof: an empty posting answer must not conceal a core decode error.

A successful proof is cached as completed=true/valid=true. An invalid proof is cached as completed=true/valid=false (unavailable). Both are scoped to the same immutable Store instance. **Cancellation, deadline expiry, or Store.Close errors are not published as either cached result.** The fixed lock order is the reader lifetime lock followed by certificate state; cached returns and final publication check cancellation/close under that lifetime lock. CandidateNode holds the reader lock through full mapped decoding. Returned IDs are copied, and no borrowed mapped slice escapes.

Cold reader validation, full-core decoding, and association/order certification are real first-query CPU/allocation/latency costs. They are not moved into unmeasured startup. Subsequent queries reuse the Store's proof but still perform directory filtering and posting union. Reload creates a new Store and a new proof. Graph bytes and public Store tables retain the existing read-only lifetime contract.

## Correctness evidence

The pinned reference is main `4e328b0109e13c896b74004823fb049fcb19251a`, Java 17.0.18+0 Homebrew ARM64. Jar SHA-256 is `91c3a1d154ca96004c55df195d9f752e077cab3e33ca1570b2c88b872d9bc34d`.

`plan-cases.json` and the three `*-plan-main.json` files contain 32 scenarios × scoped/cross × three fixtures = **192 complete main results**. All columns, rows, provenance, or exception class/message are compared, and each also has an enabled-versus-forced-scan check. The fixtures include CallSites with sparse, nonnumeric encounter IDs, isolated-surrogate and ROOT-case strings, a mixed non-Annotation graph, and a graph with dynamic Annotation properties. Both principal untyped four-field raw/wrapped forms are explicitly asserted to select the provider on safe sources, rather than merely returning correct rows through fallback.

Additional tests check the 15 fixed-property kinds for the null-inference premise and the Annotation exception separately; every preparation cancellation check; cancellation on the last certificate publication check for valid and invalid proofs; concurrent Close and cached certificates after Close; available-empty versus missing indexes; EAGER directory removal; a valid CRC/identity but incorrect property association; reordered legacy nodeindex records; old bindings, UNION/provenance, containers/projection errors, and complete-OR rejection. Full module race tests and vet are required before freezing.

Use `regenerate-plans.py /path/to/graphite-explore.jar` to regenerate the independent valid-data query corpus. It compiles the actual-main helper and saves fixtures using the actual main writer. It performs no HTTP or performance workload. The original `clean` fixture and its Java constructor/provenance come from the frozen reader's `store/testdata/callsite-index` corpus.

## Known main differences: 61 of 80 corrupt-core observations

`corrupt-cases.json` and the four `*-main.json` files preserve **80** main observations: ten query shapes × scoped/cross × clean, bad first unmatched node, bad last unmatched node, or bad matched node. A mutation replaces a callee return-type string ID with an invalid SID while retaining the four indexed property SIDs and trusted optional sidecars. These reference observations were captured before implementation using the same pinned jar; their source identity is not rewritten to imply a native result.

`corrupt-native.json` preserves all 80 native results, and `corrupt-main-native-differences.json` preserves both sides of **61/80 known differences**. Candidate-enabled execution equals the original forced scanner for all 80, including those 61 reference mismatches. Thus fallback proves preservation of existing native behavior; it does **not** establish main corrupt-graph parity.

The reference raw four-field DISTINCT/LIMIT projection can bypass full node decoding even for a corrupted matched node. The corresponding wrapped query reads full nodes and fails. Main may also throw a projection ClassCastException before encountering a later corrupt record, while the current native pipeline scans first. Native decode error classes/messages differ from the JVM's. Separately, the clean scoped raw projection path adds `$metadata.graphIds:["single"]` in main while the ordinary wrapped path does not. These specialized main projection, error-class, and evaluation-order behaviors remain explicit future compatibility work, not changes hidden inside this optimization.

`capture-native_test.go.txt` is the native observation helper; it can be copied temporarily into internal/query to regenerate the 80-record capture. The regular regression tests retain those complete native outputs and compare the candidate with the forced scanner. MAPPED supertype Class-identity iteration and unrelated parser/count/lazy-execution work also remain outside this candidate.
