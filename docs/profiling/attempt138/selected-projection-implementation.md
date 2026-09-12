# Attempt 138 selected mapped projection implementation

Implemented only `MappedCallSiteStringIndexView.kt` in the attempt normal clone. The API matches rejected Attempt 133's selectedProjectionHits signature. Its four-property eligibility, predicate checks, repeated/null columns, complete selected-posting validation before an early tuple hit, raw field equality, cancellation/accounting finally flush and encounter-order sort before LIMIT remain. No complete validator or persisted-format code changed.

Two maps are allocated inside each invocation: HashMap<String, Int> caches StringTable.findId results including -1, and HashMap<Long, Boolean> caches corresponding-property membership (packed property index and string ID). Each projected column resolves through those maps and rejects missing IDs/property membership immediately, before resolving later columns. The maps are not retained by the view or shared with another call. The shortest posting lookup remains the existing selectedTupleAnchor helper, after feasibility and original-predicate checks.

No initial-path implementation, storage wiring, StringTable edit, test/build/profile/benchmark execution, or workspace edit was done here. Root owns integration and verification. The prior Attempt 133 rejection remains unchanged.
