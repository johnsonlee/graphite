package query

import (
	"context"
	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// The ordered table is QueryPipeline.DIRECT_STRING_NODE_PROPERTIES. Storage
// supports other raw fields, but they do not extend this compiler capability.
var mainDirectStringTypes = []struct {
	kind       string
	properties []string
}{
	{"EnumConstant", []string{"name"}},
	{"LocalVariable", []string{"name"}},
	{"FieldNode", []string{"class", "name"}},
	{"CallSiteNode", []string{"caller_class", "caller_name", "callee_class", "callee_name"}},
	{"AnnotationNode", []string{"class", "name", "caller_class", "caller_name", "callee_class", "callee_name"}},
}

// Construct only the current source; its concrete lookups are eager, while
// their node decoding and the ordered head merge advance on demand.
func (e evaluator) mainStringCandidates(source Graph, pattern cypher.NodePattern, atoms []distinctStringAtom, sourceCount int) mainNodeNext {
	return e.mainStringCandidatesWithPolicy(source, pattern, atoms, sourceCount, false)
}

func (e evaluator) mainStringCandidatesWithPolicy(source Graph, pattern cypher.NodePattern, atoms []distinctStringAtom, sourceCount int, forcePersisted bool) mainNodeNext {
	return e.mainStringCandidatesWithStorage(source, pattern, atoms, sourceCount, forcePersisted, false)
}

// Streaming bindings request complete candidate consumption with main's default
// mappedView=false. A split source then loads retained storage, not a mapped view.
func (e evaluator) mainStreamingStringCandidates(source Graph, pattern cypher.NodePattern, atoms []distinctStringAtom, sourceCount int) mainNodeNext {
	return e.mainStringCandidatesWithStorage(source, pattern, atoms, sourceCount, false, true)
}

func (e evaluator) mainStringCandidatesWithStorage(source Graph, pattern cypher.NodePattern, atoms []distinctStringAtom, sourceCount int, forcePersisted, fullSplitScan bool) mainNodeNext {
	children := []mainNodeNext{}
	for _, entry := range mainDirectStringTypes {
		if len(pattern.Labels) > 0 && !matchesLabel(store.Node{Kind: entry.kind}, pattern.Labels[0]) {
			continue
		}
		filters := []distinctStringAtom{}
		for _, atom := range atoms {
			for _, property := range entry.properties {
				if atom.property == property {
					filters = append(filters, atom)
					break
				}
			}
		}
		if len(filters) == 0 {
			continue
		}
		ids := source.Store.NodesOfKind(entry.kind)
		if source.Store.Mode == "MAPPED" && entry.kind == "CallSiteNode" {
			spec := &mainStringSourceSpec{atoms: filters, sourceCount: sourceCount, forcePersisted: forcePersisted, fullSplitScan: fullSplitScan, lazyMain: true}
			children = append(children, e.mainCandidateIterator(source, spec, len(ids)))
			continue
		}
		kind := entry.kind
		position := 0
		children = append(children, func(ctx context.Context) (store.Node, bool) {
			local := e
			local.ctx = ctx
			for position < len(ids) {
				local.check()
				id := ids[position]
				position++
				var node store.Node
				if source.Store.Mode == "MAPPED" && kind != "AnnotationNode" {
					matched := false
					for _, atom := range filters {
						sid, present, err := source.Store.ProjectionPropertyStringID(ctx, id, kind, atom.property)
						failMainStringRead(err)
						if !present {
							continue
						}
						text, err := source.Store.ProjectionArrayString(ctx, sid)
						failMainStringRead(err)
						if local.distinctAtomMatches(atom, text) {
							matched = true
							break
						}
					}
					if !matched {
						continue
					}
				}
				if source.Store.Mode == "MAPPED" {
					value, present, err := source.Store.ProjectionCandidateNode(ctx, id)
					failMainStringRead(err)
					if !present {
						continue
					}
					node = value
				} else {
					value, err := source.Store.CandidateNode(ctx, id)
					if err != nil {
						failNodeRead(err)
					}
					node = value
				}
				if source.Store.Mode != "MAPPED" || kind == "AnnotationNode" {
					matched := false
					for _, atom := range filters {
						if local.distinctAtomMatches(atom, NodeProperty(node, atom.property)) {
							matched = true
							break
						}
					}
					if !matched {
						continue
					}
				}
				return node, true
			}
			return store.Node{}, false
		})
	}
	if source.Store.Mode != "MAPPED" {
		position := 0
		return func(ctx context.Context) (store.Node, bool) {
			for position < len(children) {
				n, ok := children[position](ctx)
				if ok {
					return n, true
				}
				position++
			}
			return store.Node{}, false
		}
	}
	return e.mainMergeNodeSequences(source, children)
}

func (e evaluator) mainMergeNodeSequences(source Graph, children []mainNodeNext) mainNodeNext {
	type head struct {
		node    store.Node
		order   int64
		present bool
	}
	heads := make([]head, len(children))
	initialized := false
	advance := -1
	var previous int32
	hasPrevious := false
	return func(ctx context.Context) (store.Node, bool) {
		e.ctx = ctx
		read := func(i int, monotonic bool) {
			n, ok := children[i](ctx)
			if !ok {
				heads[i].present = false
				return
			}
			order, err := source.Store.ProjectionNodeOrder(ctx, n.ID)
			failMainStringRead(err)
			if monotonic && order < heads[i].order {
				functionError("IllegalArgumentException", "String property lookup sequence is not monotonic in canonical graph order")
			}
			heads[i] = head{n, order, true}
		}
		if !initialized {
			for i := range children {
				read(i, false)
			}
			initialized = true
		}
		for {
			if advance >= 0 {
				read(advance, true)
				advance = -1
			}
			winner := -1
			for i, h := range heads {
				if h.present && (winner < 0 || h.order < heads[winner].order) {
					winner = i
				}
			}
			if winner < 0 {
				return store.Node{}, false
			}
			advance = winner
			n := heads[winner].node
			if hasPrevious && n.ID == previous {
				continue
			}
			hasPrevious = true
			previous = n.ID
			return n, true
		}
	}
}

// Main chooses source parallelism only when some participating concrete type
// does not prefer a serial scan. Empty types do not force parallel execution.
func (e evaluator) mainStringParallel(sources []Graph, p *genericDistinctPlan) bool {
	pattern := p.match.Patterns[0].Nodes[0]
	for _, entry := range mainDirectStringTypes {
		if len(pattern.Labels) > 0 && !matchesLabel(store.Node{Kind: entry.kind}, pattern.Labels[0]) {
			continue
		}
		supported := false
		for _, atom := range p.atoms {
			for _, property := range entry.properties {
				supported = supported || atom.property == property
			}
		}
		if !supported {
			continue
		}
		for _, source := range sources {
			if len(source.Store.NodesOfKind(entry.kind)) == 0 {
				continue
			}
			if source.Store.Mode != "MAPPED" {
				continue
			}
			if entry.kind != "CallSiteNode" {
				return true
			}
			index, ok, err := source.Store.RetainedProjectionIndex(e.ctx)
			failMainStringRead(err)
			if !ok {
				return true
			}
			serial, err := index.PrefersSerialProjectionScan(e.ctx)
			failMainStringRead(err)
			if !serial {
				return true
			}
		}
	}
	return false
}
