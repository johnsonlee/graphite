package query

import (
	"context"
	"errors"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// Storage inputs only: no projection expressions, columns, or mutable cursor.
// Each invocation owns its positions. A6/A7 preparation and certificates are separate.
type mainNodeNext func(context.Context) (store.Node, bool)

type mainStringSourceSpec struct {
	atoms          []distinctStringAtom
	sourceCount    int
	forcePersisted bool
	fullSplitScan  bool
	lazyMain       bool
	generic        bool
}

func (p *ordinaryProjectionPlan) mainSourceSpec() *mainStringSourceSpec {
	return &mainStringSourceSpec{atoms: p.atoms, sourceCount: p.sourceCount,
		forcePersisted: p.forcePersisted || p.scoped && p.sourceCount < 40, generic: p.generic, lazyMain: true}
}

func (e evaluator) mainCandidateIterator(source Graph, plan *mainStringSourceSpec, limit int) mainNodeNext {
	if limit <= 0 {
		return func(context.Context) (store.Node, bool) { return store.Node{}, false }
	}
	prepare := source.Store.PrepareDistinctStringIndex
	var index *store.DistinctStringIndex
	var retained bool
	var err error
	if plan.lazyMain {
		index, retained, err = source.Store.MainRetainedProjectionIndex()
		prepare = source.Store.PrepareMainOrdinaryStringIndex
	} else {
		index, retained, err = source.Store.RetainedProjectionIndex(e.ctx)
	}
	failMainStringRead(err)
	retainedSplitPreflight := retained
	raw := false
	var ids []int32
	var mappedNext func(context.Context) (int32, bool)
	candidatesPrepared := false
	if !retained {
		forcePersisted := plan.forcePersisted
		if forcePersisted {
			var prepared bool
			if plan.lazyMain {
				prepared, err = source.Store.MainPreparedProjectionFile()
			} else {
				prepared, err = source.Store.PreparedProjectionFile(e.ctx)
			}
			failMainStringRead(err)
			if prepared {
				index, _, err = prepare(e.ctx, store.DistinctProjectionOptions{ConsumeWork: e.storeWorkConsumer(), MainSource: plan.lazyMain, SourceCount: 1, Limit: limit, RetainPersisted: true})
				failMainStringRead(err)
				raw = index != nil && index.Raw
			} else {
				raw = true
			}
		} else if plan.sourceCount >= 40 && plan.fullSplitScan {
			// The streaming consumer passes the whole concrete node count. Main's raw
			// bounded parallel shortcut is therefore ineligible; preserve retained-index
			// loading/building and cannot-match preflight without initializing a view.
			index, _, err = prepare(e.ctx, store.DistinctProjectionOptions{ConsumeWork: e.storeWorkConsumer(), MainSource: plan.lazyMain, SourceCount: plan.sourceCount, Limit: limit, SkipPreparedPreference: true, CannotMatch: func() bool { return e.distinctCannotMatch(source, &indexedDistinctPlan{atoms: plan.atoms}) }})
			failMainStringRead(err)
			raw = index != nil && index.Raw
			retainedSplitPreflight = index != nil && !raw
		} else if plan.sourceCount >= 40 {
			view, ok, err := prepare(e.ctx, store.DistinctProjectionOptions{ConsumeWork: e.storeWorkConsumer(), MainSource: plan.lazyMain, SourceCount: 40, Limit: limit, InitializeMappedView: true})
			if plan.lazyMain {
				failMainMappedRead(err)
			} else {
				failMainStringRead(err)
			}
			var exact []map[int32]bool
			mappedExact := false
			if ok {
				exact, mappedExact = e.mainExactMatches(source, view, plan)
			}
			if mappedExact && !ordinaryAnyExact(exact) {
				ids = []int32{}
				candidatesPrepared = true
			} else if mappedExact && mainSharedMatcher(plan) {
				mappedNext, candidatesPrepared = e.mainSelectedMappedIDs(source, view, plan, exact, limit)
			}
			if !candidatesPrepared {
				waves := mappedExact && e.mainExactCanFill(view, plan, exact, limit)
				ids, candidatesPrepared = e.mainParallelCandidates(source, plan, limit, exact, waves)
				if !candidatesPrepared {
					index, _, err = prepare(e.ctx, store.DistinctProjectionOptions{ConsumeWork: e.storeWorkConsumer(), MainSource: plan.lazyMain, SourceCount: 40, Limit: limit, SkipPreparedPreference: true})
					failMainStringRead(err)
					raw = index != nil && index.Raw
				}
			}
		} else if plan.sourceCount > 1 {
			raw = true
		} else {
			ids, candidatesPrepared = e.mainParallelCandidates(source, plan, limit, nil, false)
			if !candidatesPrepared {
				index, _, err = prepare(e.ctx, store.DistinctProjectionOptions{ConsumeWork: e.storeWorkConsumer(), MainSource: plan.lazyMain, SourceCount: 1, Limit: limit, SkipPreparedPreference: true, CannotMatch: func() bool { return e.distinctCannotMatch(source, &indexedDistinctPlan{atoms: plan.atoms}) }})
				failMainStringRead(err)
				raw = index != nil && index.Raw
			}
		}
	}
	// Split consumers inspect retained exact-string matches before consulting
	// the node-match cache. In particular, a cached exact miss is free and must
	// not become the node cache's minimum one-unit charge. A retained index
	// loaded only after the raw fallback does not repeat this earlier stage.
	if plan.lazyMain && retainedSplitPreflight && plan.sourceCount >= 40 && !plan.forcePersisted {
		exact, supported := e.mainRetainedExactMatches(source, index, plan)
		if supported && !ordinaryAnyExact(exact) {
			ids, candidatesPrepared = []int32{}, true
		} else if supported {
			ids, candidatesPrepared = e.mainParallelCandidates(source, plan, limit, exact, false)
		}
	}
	if !candidatesPrepared {
		if raw {
			ids = source.Store.NodesOfKind("CallSiteNode")
		} else {
			// The retained range iterator is initialized below, without consuming IDs.
		}
	}
	position := 0
	var nextID func(context.Context) (int32, bool)
	if mappedNext != nil {
		nextID = mappedNext
	} else if index != nil && !raw && !candidatesPrepared {
		nextID = e.mainIndexNodeIDs(source, index, plan, limit)
	} else {
		nextID = func(ctx context.Context) (int32, bool) {
			if position >= len(ids) {
				return 0, false
			}
			id := ids[position]
			position++
			return id, true
		}
	}
	yielded := 0
	var matchers []*boundedStringMatcher
	boundedRaw := raw && plan.boundedSerialRawMatcher(limit)
	readRawString := func(sid int32) (string, error) {
		text, err := source.Store.ProjectionString(e.ctx, sid)
		failMainStringRead(err)
		return text, nil
	}
	callsite := func(ctx context.Context) (store.Node, bool) {
		e.ctx = ctx
		if boundedRaw && matchers == nil {
			matchers = sharedStringMatchers(plan.atoms, len(source.Store.Strings), serialRawMatcherCapacity)
		}
		accounting := bufferedGraphWork{work: e.work}
		defer accounting.flush()
		for {
			if raw && yielded >= limit {
				return store.Node{}, false
			}
			id, ok := nextID(ctx)
			if !ok {
				return store.Node{}, false
			}
			if raw || !plan.lazyMain {
				e.check()
			}
			if raw {
				accounting.consume()
				sids, err := source.Store.ProjectionStringIDs(e.ctx, id)
				failMainStringRead(err)
				matched := false
				for i, atom := range plan.atoms {
					p := distinctCallSiteProperties[atom.property]
					if boundedRaw {
						if matchers[i].matches(e, sids[p], readRawString) {
							matched = true
							break
						}
						continue
					}
					var text string
					var err error
					if len(source.Store.Strings) <= 1<<16 {
						text, err = source.Store.ProjectionArrayString(e.ctx, sids[p])
					} else {
						text, err = source.Store.ProjectionString(e.ctx, sids[p])
					}
					failMainStringRead(err)
					if e.distinctAtomMatches(atom, text) {
						matched = true
						break
					}
				}
				if !matched {
					continue
				}
			}
			var node store.Node
			var present bool
			var err error
			if plan.lazyMain && !raw {
				node, present, err = source.Store.MainProjectionCandidateNode(id)
			} else {
				node, present, err = source.Store.ProjectionCandidateNode(e.ctx, id)
			}
			failMainStringRead(err)
			if !present {
				continue
			}
			if node.Kind != "CallSiteNode" && !raw {
				if candidatesPrepared {
					functionError("ClassCastException", "Cannot cast io.johnsonlee.graphite.core."+node.Kind+" to io.johnsonlee.graphite.core.CallSiteNode")
				}
				continue
			}
			accounting.flush()
			if plan.generic {
				if plan.lazyMain && !raw {
					_, err = source.Store.MainProjectionNodeOrder(node.ID)
				} else {
					_, err = source.Store.ProjectionNodeOrder(e.ctx, node.ID)
				}
				failMainStringRead(err)
			}
			yielded++
			return node, true
		}
	}
	if !plan.generic || len(source.Store.NodesOfKind("AnnotationNode")) == 0 {
		return callsite
	}
	annotationIDs := source.Store.NodesOfKind("AnnotationNode")
	ai := 0
	annotation := func(ctx context.Context) (store.Node, bool) {
		e.ctx = ctx
		for ai < len(annotationIDs) {
			id := annotationIDs[ai]
			ai++
			node, err := source.Store.CandidateNode(e.ctx, id)
			failMainStringRead(err)
			e.consume(1)
			for _, atom := range plan.atoms {
				if e.distinctAtomMatches(atom, NodeProperty(node, atom.property)) {
					return node, true
				}
			}
		}
		return store.Node{}, false
	}
	return e.mainMergeNodeSequences(source, []mainNodeNext{callsite, annotation})
}

// Source lifetime failures remain typed for the iterator owner. Ordinary's
// established public error mapping is applied by its adapter.
func failMainStringRead(err error) {
	if errors.Is(err, store.ErrStoreClosed) {
		panic(err)
	}
	failProjectionRead(err)
}

// Thread interruption at main's mapped-view checkpoints has its own storage
// exception. Keep its message and retain the native cancellation cause so a
// worker coordinator can still recognize local cancellation. Callback failures
// are passed through unchanged, including their original request error object.
func failMainMappedRead(err error) {
	if err == context.Canceled || err == context.DeadlineExceeded {
		panic(&Error{Class: "CancellationException", Message: "Mapped CallSite string index view interrupted", cause: err})
	}
	failMainStringRead(err)
}
