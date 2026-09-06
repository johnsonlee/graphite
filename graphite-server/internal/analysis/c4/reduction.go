package c4

import "sort"

// ReduceTransitiveEdges intentionally preserves Kotlin's first-arriving alternate
// path capacity, rather than substituting a maximum-flow interpretation.
func ReduceTransitiveEdges(edges []Relationship, preserveRuntime bool) []Relationship {
	indices := []int{}
	for i, e := range edges {
		if !preserveRuntime || relationshipKind(e) != RunsOn {
			indices = append(indices, i)
		}
	}
	if len(indices) > 200 {
		return edges
	}
	redundant := map[string]bool{}
	weight := func(e Relationship) int {
		if e.Weight == nil {
			return 1
		}
		return max(1, *e.Weight)
	}
	type item struct {
		id       string
		capacity int
	}
	for _, omit := range indices {
		e := edges[omit]
		hierarchy := relationshipKind(e) == RunsOn || relationshipKind(e) == BuildsOn
		queue := []item{{e.From, UnboundedModelElements}}
		best := map[string]int{}
		capacity := -1
		found := false
		for len(queue) > 0 && !found {
			cur := queue[0]
			queue = queue[1:]
			if n, ok := best[cur.id]; ok && n >= cur.capacity {
				continue
			}
			best[cur.id] = cur.capacity
			for _, j := range indices {
				next := edges[j]
				if j == omit || next.From != cur.id {
					continue
				}
				nc := min(cur.capacity, weight(next))
				if hierarchy {
					nc = 1
				}
				if next.To == e.To {
					capacity = nc
					found = true
					break
				}
				if n, ok := best[next.To]; !ok || n < nc {
					queue = append(queue, item{next.To, nc})
				}
			}
		}
		if (hierarchy && found) || (!hierarchy && capacity >= weight(e)) {
			redundant[compactJSON(e)] = true
		}
	}
	out := []Relationship{}
	for _, e := range edges {
		if !redundant[compactJSON(e)] {
			out = append(out, e)
		}
	}
	return out
}
func SelectReadableRelationships(edges []Relationship) []Relationship {
	if len(edges) <= 1 {
		return edges
	}
	candidates := []Relationship{}
	for _, e := range edges {
		if relationshipKind(e) != CollaboratesWith {
			candidates = append(candidates, e)
		}
	}
	if len(candidates) == 0 {
		candidates = edges
	}
	seen := map[string]bool{}
	dedup := []Relationship{}
	for _, e := range candidates {
		k := e.From + ":" + e.To + ":" + string(relationshipKind(e))
		if !seen[k] {
			seen[k] = true
			dedup = append(dedup, e)
		}
	}
	sort.SliceStable(dedup, func(i, j int) bool { return value(dedup[i].Weight) > value(dedup[j].Weight) })
	reduced := ReduceTransitiveEdges(dedup, false)
	out := []Relationship{}
	outgoing, incoming := map[string]int{}, map[string]int{}
	seen = map[string]bool{}
	add := func(e Relationship, caps bool) {
		if len(out) >= 12 || e.From == "" || e.To == "" {
			return
		}
		k := e.From + ":" + e.To + ":" + string(relationshipKind(e))
		if seen[k] || (caps && (outgoing[e.From] >= 2 || incoming[e.To] >= 2)) {
			return
		}
		out = append(out, e)
		seen[k] = true
		outgoing[e.From]++
		incoming[e.To]++
	}
	for _, e := range reduced {
		add(e, true)
	}
	if len(out) < min(12, len(reduced), 6) {
		for _, e := range reduced {
			add(e, false)
		}
	}
	return out
}
