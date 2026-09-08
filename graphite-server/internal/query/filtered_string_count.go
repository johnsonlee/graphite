package query

import (
	"context"
	"math"
	"runtime"
	"sort"
	"strings"

	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

type filteredStringCountPlan struct {
	node               cypher.NodePattern
	condition, counted cypher.Expr
	atoms              []distinctStringAtom
	column             string
	distinct, raw      bool
	graphID            *string
}

type filteredStringCountPartial struct {
	count    int64
	distinct map[string]bool
	matched  bool
}

func (e evaluator) compileFilteredStringCount(branch cypher.SingleQuery) *filteredStringCountPlan {
	if len(branch.Clauses) != 2 {
		return nil
	}
	m, ok := branch.Clauses[0].(cypher.MatchClause)
	if !ok || m.Optional || m.Where == nil || len(m.Patterns) != 1 {
		return nil
	}
	r, ok := branch.Clauses[1].(cypher.ProjectionClause)
	if !ok || r.With || r.All || r.Distinct || len(r.Items) != 1 || r.Where != nil || r.Limit != nil || r.Skip != nil || len(r.OrderBy) != 0 {
		return nil
	}
	pattern := m.Patterns[0]
	if pattern.PathVariable != "" || len(pattern.Relationships) != 0 || len(pattern.Nodes) != 1 {
		return nil
	}
	node := pattern.Nodes[0]
	if node.Variable == "" || len(node.Labels) > 1 || len(node.Properties) != 0 {
		return nil
	}
	p := &filteredStringCountPlan{node: node, condition: m.Where, column: r.Items[0].Alias}
	if p.column == "" {
		p.column = columnName(r.Items[0].Expression)
	}
	// The public pipeline routes unknown labels to general execution before the
	// filtered-count helper; its local empty-label shortcut is unreachable here.
	if len(node.Labels) == 1 && !lazyKnownLabel(node.Labels[0]) {
		return nil
	}
	call, ok := r.Items[0].Expression.(cypher.Call)
	if !ok || !strings.EqualFold(call.Name, "count") {
		return nil
	}
	if call.Star {
		if len(call.Arguments) != 0 {
			return nil
		}
	} else {
		if len(call.Arguments) != 1 {
			return nil
		}
		p.counted = call.Arguments[0]
		p.distinct = call.Distinct
	}
	p.atoms = e.lazyNecessaryCandidates(m.Where, node.Variable)
	if p.atoms == nil {
		return nil
	}
	_, exact := e.compileDistinctDisjunction(m.Where, node.Variable)
	property, propertyOK := distinctProperty(p.counted, node.Variable)
	p.raw = exact && (p.counted == nil || propertyOK && property != "graphId" && property != "elementId" && property != "qualifiedId")
	p.graphID = e.filteredCountGraphEquality(m.Where, node.Variable)
	return p
}

func (e evaluator) filteredCountGraphEquality(expr cypher.Expr, variable string) *string {
	b, ok := expr.(cypher.Binary)
	if !ok {
		return nil
	}
	if b.Op == "AND" {
		if id := e.filteredCountGraphEquality(b.Left, variable); id != nil {
			return id
		}
		return e.filteredCountGraphEquality(b.Right, variable)
	}
	if b.Op != "=" {
		return nil
	}
	if distinctGraphReference(b.Left, variable) {
		if id, ok := e.distinctStringConstant(b.Right); ok {
			return &id
		}
	}
	if distinctGraphReference(b.Right, variable) {
		if id, ok := e.distinctStringConstant(b.Left); ok {
			return &id
		}
	}
	return nil
}

func (e evaluator) filteredStringCount(graph *store.Store, branch cypher.SingleQuery) (Result, bool) {
	defer ordinarySourceFailure()
	p := e.compileFilteredStringCount(branch)
	if p == nil {
		return Result{}, false
	}
	row := map[string]any{p.column: int64(0)}
	result := Result{Columns: []string{p.column}, Rows: []map[string]any{row}}
	sources := e.graphs
	if !e.cross {
		if graph == nil {
			return Result{}, false
		}
		sources = []Graph{{"single", graph}}
	}
	// The public planner first re-enters with an exact root graph scope. The
	// helper's equality pruning below then keeps this pipeline's storage count.
	if !e.sourceScopeApplied {
		route := e.streamingGraphConstraint(p.condition, p.node.Variable)
		if route != nil {
			routed := []Graph{}
			for _, source := range sources {
				if route[source.ID] {
					routed = append(routed, source)
				}
			}
			sources = routed
		}
	}
	storageSourceCount := len(sources)
	selected := []Graph{}
	for _, source := range sources {
		if p.graphID == nil || source.ID == *p.graphID {
			selected = append(selected, source)
		}
	}
	sources = selected
	parallel := min(len(sources), runtime.NumCPU(), 8)
	if len(sources) >= 40 {
		parallel = min(len(sources), max(1, runtime.NumCPU()/2))
	}
	if !e.cross {
		parallel = 1
	}
	partials := make([]filteredStringCountPartial, len(sources))
	task := func(ctx context.Context, i int) filteredStringCountPartial {
		local := e
		local.ctx = ctx
		local.rowOrders = nil
		local.regexes = &regexLRU{entries: map[string]compiledRegex{}}
		return local.filteredCountSource(sources[i], p, storageSourceCount)
	}
	if parallel <= 1 {
		for i := range sources {
			partials[i] = task(e.ctx, i)
		}
	} else {
		runDistinctTasks(e.ctx, len(sources), parallel, false, task, func(i int, value filteredStringCountPartial) bool { partials[i] = value; return false })
	}
	var count int64
	distinct := map[string]bool{}
	ids := []string{}
	seenSources := map[string]bool{}
	for i, part := range partials {
		count += part.count
		if p.distinct {
			for value := range part.distinct {
				distinct[value] = true
			}
		}
		if e.cross && part.matched && !seenSources[sources[i].ID] {
			seenSources[sources[i].ID] = true
			ids = append(ids, sources[i].ID)
		}
	}
	if p.distinct {
		count = int64(len(distinct))
	}
	row[p.column] = count
	if len(ids) > 0 {
		// CypherExecutor.materializeResult normalizes the source set with
		// String.sorted(): JVM UTF-16 order, independent of partial/source order.
		sort.Slice(ids, func(i, j int) bool { return compareUTF16(ids[i], ids[j]) < 0 })
		row[provenanceKey] = ids
	}
	return result, true
}

func (e evaluator) filteredCountSource(source Graph, p *filteredStringCountPlan, sourceCount int) filteredStringCountPartial {
	out := filteredStringCountPartial{}
	if p.distinct {
		out.distinct = map[string]bool{}
	}
	accept := func(value any) {
		if value == nil {
			return
		}
		if p.distinct {
			out.distinct[key(value)] = true
		} else {
			out.count++
		}
	}
	if !p.raw {
		next := e.mainStreamingStringCandidates(source, p.node, p.atoms, sourceCount)
		bindings := map[string]any{}
		for {
			node, ok := next(e.ctx)
			if !ok {
				break
			}
			bindings[p.node.Variable] = e.nodeValue(source.Store, source.ID, node)
			if e.eval(p.condition, bindings) != true {
				continue
			}
			out.matched = true
			if p.counted == nil {
				out.count++
			} else {
				accept(e.eval(p.counted, bindings))
			}
		}
		return out
	}
	property, _ := distinctProperty(p.counted, p.node.Variable)
	for _, entry := range mainDirectStringTypes {
		if len(p.node.Labels) > 0 && !matchesLabel(store.Node{Kind: entry.kind}, p.node.Labels[0]) {
			continue
		}
		atoms := []distinctStringAtom{}
		supported := p.counted == nil
		for _, name := range entry.properties {
			supported = supported || property == name
		}
		for _, atom := range p.atoms {
			for _, name := range entry.properties {
				if atom.property == name {
					atoms = append(atoms, atom)
					break
				}
			}
		}
		if len(atoms) == 0 {
			continue
		}
		if entry.kind == "CallSiteNode" && source.Store.Mode == "MAPPED" && supported {
			var counted *store.CallSiteStringProperty
			if p.distinct {
				v := store.CallSiteStringProperty(distinctCallSiteProperties[property])
				counted = &v
			}
			count, values := e.filteredCountStorage(source, atoms, counted)
			out.matched = out.matched || count > 0
			if p.distinct {
				for _, value := range values {
					out.distinct[key(value)] = true
				}
			} else {
				out.count += count
			}
			continue
		}
		nodePattern := cypher.NodePattern{Variable: p.node.Variable, Labels: []string{entry.kind}}
		next := e.mainStreamingStringCandidates(source, nodePattern, atoms, sourceCount)
		for {
			node, ok := next(e.ctx)
			if !ok {
				break
			}
			out.matched = true
			if p.counted == nil {
				out.count++
			} else {
				accept(NodeProperty(node, property))
			}
		}
	}
	return out
}

func (e evaluator) filteredCountStorage(source Graph, atoms []distinctStringAtom, property *store.CallSiteStringProperty) (int64, []string) {
	index, retained, err := source.Store.RetainedProjectionIndex(e.ctx)
	failMainStringRead(err)
	if !retained {
		if len(source.Store.NodesOfKind("CallSiteNode")) == 0 {
			return 0, nil
		}
		// Aggregate's preflight precedes persisted loading; it cannot be supplied as
		// PrepareDistinctStringIndex.CannotMatch, whose callback runs after loading.
		preflightAtoms := []distinctStringAtom{}
		seen := map[string]bool{}
		for _, atom := range atoms {
			k := ordinaryStringKey(atom)
			if !seen[k] {
				seen[k] = true
				preflightAtoms = append(preflightAtoms, atom)
			}
		}
		if e.distinctCannotMatch(source, &indexedDistinctPlan{atoms: preflightAtoms}) {
			return 0, nil
		}
		index, _, err = source.Store.PrepareDistinctStringIndex(e.ctx, store.DistinctProjectionOptions{MainSource: true, SourceCount: 1, Limit: math.MaxInt32, SkipPreparedPreference: true})
		failMainStringRead(err)
	}
	ranges := [][]int32{}
	sharedStates := map[string]*boundedStringMatcher{}
	for _, atom := range atoms {
		propertyIndex := store.CallSiteStringProperty(distinctCallSiteProperties[atom.property])
		matches, known := e.stringIndexMatches(source, index, atom, failMainStringRead)
		appendRange := func(sid int32) {
			ids, err := index.Postings(e.ctx, propertyIndex, sid)
			failMainStringRead(err)
			if len(ids) > 0 {
				ranges = append(ranges, ids)
			}
		}
		if atom.op == "=" && !atom.lower {
			if sid := e.distinctStringTableID(source.Store.Strings, atom.term); sid >= 0 {
				appendRange(sid)
			}
			continue
		}
		// Known matching IDs are ordered. Main intersects them with the property
		// directory without evaluating or decoding the other dictionary strings.
		if known {
			for _, sid := range matches {
				e.check()
				appendRange(sid)
			}
			continue
		}
		cacheKey := ordinaryStringKey(atom)
		matcher := sharedStates[cacheKey]
		if matcher == nil {
			matcher = &boundedStringMatcher{atom: atom, dense: make([]byte, len(source.Store.Strings))}
			sharedStates[cacheKey] = matcher
		}
		readString := func(sid int32) (string, error) {
			value, err := source.Store.ProjectionString(e.ctx, sid)
			failMainStringRead(err)
			return value, nil
		}
		directory, err := index.Directory(e.ctx, propertyIndex)
		failMainStringRead(err)
		for _, entry := range directory {
			e.check()
			if matcher.matches(e, entry.StringID, readString) {
				appendRange(entry.StringID)
			}
		}
	}
	count, values, err := index.AggregateProjectionRanges(e.ctx, ranges, property)
	failMainStringRead(err)
	return count, values
}
