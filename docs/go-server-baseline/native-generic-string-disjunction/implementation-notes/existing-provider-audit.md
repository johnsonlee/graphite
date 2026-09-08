# Existing provider reuse audit

This read-only audit compared pinned main `4e328b01`, the frozen 201-scenario
oracle, and the existing Go provider. It did not run a performance measurement.

`store/projection_property.go` already covers the exact direct-string compiler
pairs: EnumConstant name, LocalVariable name, FieldNode class/name. Its persisted
offset lookup, negative offset absence, Java int narrowing/address wrapping,
selective four-byte read, and unvalidated raw SID meet the required mapped raw
access boundary. The broader raw fields admitted by main storage are not
additional query compiler capabilities. No new accessor is needed.

`query/main_string_candidates.go` already constructs one iterator per concrete
kind, preserves per-kind predicate order and short circuiting, filters raw SIDs
before decoding Enum/Local/Field payloads, and merges mapped candidates by their
payload node ID's canonical offset. `ProjectionArrayString` gives the required
array bounds error for a SID consumed by predicate matching. A match-state cache
must retain that bounds check before using a SID as an array index and share only
states for equal transform/mode/expected keys within the same concrete iterator.
Raw string properties can differ while sharing a matcher, as in main.

`ProjectionCandidateNode` deliberately returns payload ID and payload kind. The
oracle proves this matters: the generic Field provider yields payload ID 1152
when selected offset ID is 18 and yields IntConstant after the tag is changed.
The merge then uses the returned payload ID, just as main. No concrete type or
selected ID validation should be added to this branch.

Annotation is a decoder-first fallback because main's raw property capability
rejects Annotation. Main `graph.nodes<T>()` contains an erased `as? T` whose
bound is Node, not a concrete `Class.cast`; source inspection does not justify a
new concrete-kind check in the Go fallback. The oracle's Annotation payload-tag
controls return empty, consistent with reading an IntConstant and then failing
the string predicate. They do not by themselves distinguish concrete type
rejection from ordinary property nonmatching.

Remaining decoder boundaries should be checked by root's full oracle comparison:

- Non-CallSite `ProjectionCandidateNode` delegates to the general decoder,
  whose collection count rejects negative/oversized values early. Main's node
  serializer can consume counts differently; no general fidelity claim follows
  from the raw helper's correctness.
- General decoder truncation reports a textual `truncated data` error, while
  main's mapped `ByteBufferDataInput` throws EOFException. In particular the
  frozen `field-bad-tail-hit` requires EOFException; a predicate miss must avoid
  the final boolean entirely. The query error boundary may translate this, so
  a public comparison is needed before calling it a current regression.
- Unknown nested scalar tags already use the fallback string SID in the current
  general decoder, matching the oracle's tag-127 Enum/Annotation cases. Do not
  replace this with strict unknown-tag rejection.
- General decoder cancellation/lifetime/collection behavior is broader than this
  bounded accessor audit. Full module checks, original 64-graph replay, and
  measured performance remain separate requirements.
