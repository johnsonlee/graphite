package query

import (
	"context"
	"errors"
	"sort"
	"strings"

	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
)

// An internal context switch keeps correctness tests and diagnostic comparisons
// on the complete scanner without changing the user-visible query language.
type candidateScanOnlyKey struct{}

// Force the certified A6 dictionary path in correctness comparisons.
type candidateDirectoryOnlyKey struct{}
type stringCandidateOperand struct {
	property           store.CallSiteStringProperty
	lower, emptyOnNull bool
}
type stringCandidateAtom struct {
	operand  stringCandidateOperand
	op, term string
}
type stringCandidatePlan struct {
	atoms   []stringCandidateAtom
	untyped bool
}

func (e evaluator) compileStringCandidates(clause cypher.MatchClause) *stringCandidatePlan {
	if !e.indexFirst || e.ctx.Value(candidateScanOnlyKey{}) == true || clause.Optional || len(clause.Patterns) != 1 {
		return nil
	}
	pattern := clause.Patterns[0]
	if pattern.PathVariable != "" || len(pattern.Relationships) != 0 || len(pattern.Nodes) != 1 {
		return nil
	}
	node := pattern.Nodes[0]
	if node.Variable == "" || len(node.Properties) != 0 || len(node.Labels) > 1 {
		return nil
	}
	untyped := len(node.Labels) == 0
	if !untyped && !strings.EqualFold(node.Labels[0], "CallSite") && !strings.EqualFold(node.Labels[0], "CallSiteNode") {
		return nil
	}
	plan := &stringCandidatePlan{untyped: untyped}
	var compile func(cypher.Expr) bool
	compile = func(expression cypher.Expr) bool {
		e.check()
		binary, ok := expression.(cypher.Binary)
		if !ok {
			return false
		}
		if binary.Op == "OR" {
			return compile(binary.Left) && compile(binary.Right)
		}
		switch binary.Op {
		case "=", "CONTAINS", "STARTS WITH", "ENDS WITH":
		default:
			return false
		}
		operand, ok := e.compileStringOperand(binary.Left, node.Variable)
		if !ok {
			return false
		}
		var term string
		switch right := binary.Right.(type) {
		case cypher.Literal:
			term, ok = right.Value.(string)
		case cypher.Parameter:
			term, ok = e.parameters[right.Name].(string)
		default:
			return false
		}
		if !ok {
			return false
		}
		// Every non-CallSite/non-Annotation kind returns null for these four keys.
		// Wrappers may turn that null into an empty string. In that case an empty
		// RHS can make this OR arm true on other kinds; do not infer CallSite.
		if untyped && operand.emptyOnNull && term == "" {
			return false
		}
		plan.atoms = append(plan.atoms, stringCandidateAtom{operand, binary.Op, term})
		return true
	}
	if !compile(clause.Where) {
		return nil
	}
	return plan
}
func (e evaluator) compileStringOperand(expression cypher.Expr, variable string) (stringCandidateOperand, bool) {
	e.check()
	if property, ok := expression.(cypher.Property); ok {
		object, ok := property.Object.(cypher.Variable)
		if !ok || object.Name != variable {
			return stringCandidateOperand{}, false
		}
		names := map[string]store.CallSiteStringProperty{"caller_class": store.CallerClass, "caller_name": store.CallerName, "callee_class": store.CalleeClass, "callee_name": store.CalleeName}
		p, ok := names[property.Key]
		return stringCandidateOperand{property: p}, ok
	}
	call, ok := expression.(cypher.Call)
	if !ok || call.Distinct || call.Star {
		return stringCandidateOperand{}, false
	}
	name := strings.ToLower(call.Name)
	switch name {
	case "tostring", "tolower", "tolowercase":
		if len(call.Arguments) != 1 {
			return stringCandidateOperand{}, false
		}
	case "coalesce":
		if len(call.Arguments) != 2 {
			return stringCandidateOperand{}, false
		}
		literal, ok := call.Arguments[1].(cypher.Literal)
		if !ok || literal.Value != "" {
			return stringCandidateOperand{}, false
		}
	default:
		return stringCandidateOperand{}, false
	}
	operand, ok := e.compileStringOperand(call.Arguments[0], variable)
	if !ok {
		return operand, false
	}
	if name == "tolower" || name == "tolowercase" {
		if operand.lower {
			return operand, false
		}
		operand.lower = true
	}
	if name == "coalesce" {
		operand.emptyOnNull = true
	}
	return operand, true
}

type candidateNodePosition struct {
	id     int32
	offset int64
}
type candidateGraphNodes struct {
	source Graph
	nodes  []candidateNodePosition
}

// Preparation completes for all sources before publishing any candidate. A
// partial/unavailable index can therefore fall back without duplicated rows.
func (e evaluator) indexedNodeWalker(graph *store.Store, clause cypher.MatchClause, slot *candidateSlot) func(func(any)) {
	plan := e.compileStringCandidates(clause)
	if plan == nil {
		return nil
	}
	sources := e.graphs
	if !e.cross && graph != nil {
		sources = []Graph{{Store: graph}}
	}
	prepared := make([]candidateGraphNodes, 0, len(sources))
	for _, source := range sources {
		e.check()
		// EAGER owns already-loaded values; consulting files after load could add
		// failures when its original directory has been moved or removed.
		if source.Store.Mode != "MAPPED" {
			return nil
		}
		if plan.untyped && len(source.Store.NodesOfKind("AnnotationNode")) != 0 {
			return nil
		}
		view, available, err := source.Store.TryCallSiteStringIndex(e.ctx)
		if err != nil {
			candidatePreparationError(err)
			return nil
		}
		if !available {
			return nil
		}
		certified, err := source.Store.CertifyCallSiteCandidates(e.ctx, view)
		if err != nil {
			candidatePreparationError(err)
			return nil
		}
		if !certified {
			return nil
		}
		selected := map[int32]bool{}
		trigramChecked, trigramValid := false, false
		for property := store.CallerClass; property <= store.CalleeName; property++ {
			atoms := []stringCandidateAtom{}
			for _, atom := range plan.atoms {
				if atom.operand.property == property {
					atoms = append(atoms, atom)
				}
			}
			if len(atoms) == 0 {
				continue
			}
			dictionaryAtoms := make([]stringCandidateAtom, 0, len(atoms))
			for _, atom := range atoms {
				hashes := e.candidateTrigrams(atom)
				if len(hashes) != 0 && e.ctx.Value(candidateDirectoryOnlyKey{}) != true {
					if !trigramChecked {
						trigramValid, err = source.Store.CertifyCallSiteTrigrams(e.ctx, view)
						if err != nil {
							candidatePreparationError(err)
							return nil
						}
						trigramChecked = true
					}
					if trigramValid {
						stringIDs, err := view.TrigramAnchor(e.ctx, hashes)
						if err != nil {
							candidatePreparationError(err)
							return nil
						}
						for _, sid := range stringIDs {
							e.check()
							value := source.Store.Strings[sid]
							if atom.operand.lower {
								value = e.javaCase(value, false)
							}
							if e.binary(cypher.Binary{Left: cypher.Literal{Value: value}, Op: atom.op, Right: cypher.Literal{Value: atom.term}}, nil) != true {
								continue
							}
							ids, err := view.Postings(e.ctx, property, sid)
							if err != nil {
								candidatePreparationError(err)
								return nil
							}
							for _, id := range ids {
								e.check()
								selected[id] = true
							}
						}
						continue
					}
				}
				dictionaryAtoms = append(dictionaryAtoms, atom)
			}
			atoms = dictionaryAtoms
			if len(atoms) == 0 {
				continue
			}
			directory, err := view.Directory(e.ctx, property)
			if err != nil {
				candidatePreparationError(err)
				return nil
			}
			for _, entry := range directory {
				e.check()
				value := source.Store.Strings[entry.StringID]
				lower := ""
				lowered := false
				matches := false
				for _, atom := range atoms {
					left := value
					if atom.operand.lower {
						if !lowered {
							lower = e.javaCase(value, false)
							lowered = true
						}
						left = lower
					}
					if e.binary(cypher.Binary{Left: cypher.Literal{Value: left}, Op: atom.op, Right: cypher.Literal{Value: atom.term}}, nil) == true {
						matches = true
						break
					}
				}
				if !matches {
					continue
				}
				ids, err := view.Postings(e.ctx, property, entry.StringID)
				if err != nil {
					candidatePreparationError(err)
					return nil
				}
				for _, id := range ids {
					e.check()
					selected[id] = true
				}
			}
		}
		nodes := make([]candidateNodePosition, 0, len(selected))
		for id := range selected {
			e.check()
			raw, err := source.Store.RawCallSiteStringIDs(e.ctx, id)
			if err != nil {
				candidatePreparationError(err)
				return nil
			}
			nodes = append(nodes, candidateNodePosition{id, raw.Offset})
		}
		sort.Slice(nodes, func(i, j int) bool { e.check(); return nodes[i].offset < nodes[j].offset })
		e.check()
		prepared = append(prepared, candidateGraphNodes{source, nodes})
	}
	return func(accept func(any)) {
		for _, group := range prepared {
			for _, position := range group.nodes {
				e.check()
				node, err := group.source.Store.CandidateNode(e.ctx, position.id)
				if err != nil {
					candidatePreparationError(err)
					failNodeRead(err)
				}
				slot.graph, slot.graphID, slot.qualified = group.source.Store, group.source.ID, e.cross
				slot.isMethod, slot.node = false, node
				accept(slot)
			}
		}
	}
}
func candidatePreparationError(err error) {
	if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) || errors.Is(err, store.ErrStoreClosed) {
		panic(err)
	}
}

// Persisted grams describe whole Java ROOT lowercase strings. Raw ASCII RHS
// survives that transform; raw non-ASCII does not (contextual final sigma).
// A LOWER predicate uses its ORIGINAL RHS: exact matching never lowercases it.
func (e evaluator) candidateTrigrams(atom stringCandidateAtom) []int32 {
	e.check()
	if atom.op != "CONTAINS" {
		return nil
	}
	units := javaUTF16(atom.term)
	e.check()
	if len(units) < 3 {
		return nil
	}
	if !atom.operand.lower {
		for i, u := range units {
			e.check()
			if u >= 128 {
				return nil
			}
			if u >= 'A' && u <= 'Z' {
				units[i] = u + ('a' - 'A')
			}
		}
	}
	hashes := []int32{}
	seen := map[int32]bool{}
	for i := 0; i+2 < len(units); i++ {
		e.check()
		hash := (int32(units[i])*31+int32(units[i+1]))*31 + int32(units[i+2])
		if !seen[hash] {
			seen[hash] = true
			hashes = append(hashes, hash)
		}
	}
	return hashes
}
