# Real64 cancellation-lock diagnostic on Attempt15

Root revision62b92d20, exact2402 production/testdata inputs; diagnostic-only
helper from /tmp/graphite-a15-mutex-diagnostic, frozen manifest
73a60c2cf55fdac5fe8264fc475d0afd7ddb0a93670505a7122ffc58cd387dd6.
All2405 archived inputs plus the three-file profiler overlay were independently
verified. The same binary/config (only graph paths moved to a fresh COW clone)
ran sequentially with mutex sampling disabled and with rate16 on the repeat
prefix query only. Every prior request preserved index/cache history.
No query/Store/context implementation or runtime scheduling was changed.

Both processes exited0 and closed all Stores. All ten complete typed responses
match pinned main config, with every before/after projection state identical
between control and sampled runs. Original and clone all1152 persisted files
remain byte-identical with no extras. Source and binary hashes remain fixed.
The script, exact commands, raw outputs, profiles and independent verification
are retained. No other real64 process ran concurrently; independent correctness
work could overlap, so these are diagnostic observations on a shared host.

The repeat query's cumulative mutex profile difference reports24.99 seconds of
estimated blocked delay:13.77 seconds (55.10%) under context.cancelCtx.Err and
11.22 seconds (44.90%) under runtime._LostContendedRuntimeLock. ProjectionNodeOrder
accounts for8.36 cumulative seconds and ProjectionCandidateNode4.80, including
nested prepare/check frames; those cumulative stacks must not be added together.
The control profile is empty. Raw sampled contentions are estimated by sampling,
not exact event counts.

Control repeat execute+marshal9.874752s,CPU45.746051s,allocation444,261,976bytes;
sampled repeat10.736632s,CPU49.193854s,allocation444,222,704bytes. Both request
NumGC counters are0. Sampling perturbed duration/CPU; these are not optimization
results. Mutex delay is accumulated goroutine blocking time, not CPU time,
lock-hold time, a fraction of wall time or a predicted speedup. Runtime-lock
samples remain unclassified. The data demonstrates actual blocking in cancelCtx
checks and justifies testing a cancellation-preserving fast check separately.
It does not explain every remaining CPU cost or establish final P95 acceptance.

Next single hypothesis (Attempt17) is nonblocking Done polling at the existing
hot Store projection checkpoints, retaining Err only when cancellation is
observable, with real cancellation/lifetime/error-order regression tests.
No implementation is included in this diagnostic bundle. Attempt16's label-map
switch is separate and unmeasured here.
