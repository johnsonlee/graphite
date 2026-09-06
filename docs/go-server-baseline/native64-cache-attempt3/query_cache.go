package server

import (
	"container/list"
	"context"
	"strconv"
	"strings"
	"sync"

	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
)

// Cache only successful, deterministic responses from acquired immutable graph
// generations. Admission and request cancellation still run for every hit.
// Entries own encoded bytes only: retired graphs and mapped memory are not held.
type queryResponseCache struct {
	mu                   sync.Mutex
	entries              map[string]*list.Element
	lru                  list.List
	bytes                int
	maxBytes, maxEntries int
}
type cachedResponse struct {
	key   string
	body  []byte
	bytes int
}

func makeResponseKey(text, surface, mode string, limit, perGraph int, includeRows bool, leases []*Lease) string {
	// Length-prefix raw bytes, including possible WTF-8 query strings. JSON key
	// encoding would replace isolated surrogates and could merge distinct inputs.
	var b strings.Builder
	field := func(s string) { b.WriteString(strconv.Itoa(len(s))); b.WriteByte(':'); b.WriteString(s) }
	for _, v := range []string{text, surface, mode, strconv.Itoa(limit), strconv.Itoa(perGraph), strconv.FormatBool(includeRows), strconv.Itoa(len(leases))} {
		field(v)
	}
	for _, l := range leases {
		field(l.ID)
		field(strconv.FormatUint(l.snapshot.descriptor.Generation, 10))
	}
	return b.String()
}
func (c *queryResponseCache) get(key string) ([]byte, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if el := c.entries[key]; el != nil {
		c.lru.MoveToFront(el)
		return el.Value.(*cachedResponse).body, true
	}
	return nil, false
}
func (c *queryResponseCache) put(key string, body []byte) {
	c.putContext(context.Background(), key, body)
}
func (c *queryResponseCache) putContext(ctx context.Context, key string, body []byte) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if ctx.Err() != nil {
		return
	}
	maxBytes, maxEntries := c.maxBytes, c.maxEntries
	if maxBytes == 0 {
		maxBytes = 32 << 20
	}
	if maxEntries == 0 {
		maxEntries = 512
	}
	size := len(key) + len(body) + 256
	if size > maxBytes || maxEntries < 1 {
		return
	}
	if c.entries == nil {
		c.entries = map[string]*list.Element{}
	}
	if el := c.entries[key]; el != nil {
		c.lru.MoveToFront(el)
		return
	}
	// Copy before evicting: cancellation must not disturb retained responses.
	retained := make([]byte, len(body))
	for start := 0; start < len(body); start += 65536 {
		if ctx.Err() != nil {
			return
		}
		end := min(start+65536, len(body))
		copy(retained[start:end], body[start:end])
	}
	if ctx.Err() != nil {
		return
	}
	for c.lru.Len() >= maxEntries || c.bytes > maxBytes-size {
		oldest := c.lru.Back()
		entry := oldest.Value.(*cachedResponse)
		delete(c.entries, entry.key)
		c.bytes -= entry.bytes
		c.lru.Remove(oldest)
	}
	// Do not alias producer buffers. Hits are used only by http.ResponseWriter.
	c.entries[key] = c.lru.PushFront(&cachedResponse{key, retained, size})
	c.bytes += size
}
func (s *Server) memoizedCypher(ctx context.Context, key, source string, build func() (any, error)) (any, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if body, ok := s.queryCache.get(key); ok {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		return body, nil
	}
	value, err := build()
	if err != nil {
		return nil, err
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	// Eligibility is checked only for successful execution. Unknown future AST
	// nodes/functions bypass caching, preserving their original error behavior.
	ast, parseErr := cypher.ParseContext(ctx, source)
	if parseErr == nil && deterministicQueryContext(ctx, ast) {
		s.queryCache.putContext(ctx, key, value.([]byte))
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	return value, nil
}
func deterministicQuery(q *cypher.Query) bool {
	return deterministicQueryContext(context.Background(), q)
}
func deterministicQueryContext(ctx context.Context, q *cypher.Query) bool {
	for _, branch := range q.Branches {
		for _, clause := range branch.Clauses {
			if ctx.Err() != nil {
				return false
			}
			switch c := clause.(type) {
			case cypher.MatchClause:
				if !deterministicExpr(ctx, c.Where) {
					return false
				}
				for _, p := range c.Patterns {
					for _, n := range p.Nodes {
						for _, x := range n.Properties {
							if !deterministicExpr(ctx, x) {
								return false
							}
						}
					}
					for _, r := range p.Relationships {
						for _, x := range r.Properties {
							if !deterministicExpr(ctx, x) {
								return false
							}
						}
					}
				}
			case cypher.UnwindClause:
				if !deterministicExpr(ctx, c.Expression) {
					return false
				}
			case cypher.ProjectionClause:
				if !deterministicExpr(ctx, c.Where) || !deterministicExpr(ctx, c.Skip) || !deterministicExpr(ctx, c.Limit) {
					return false
				}
				for _, i := range c.Items {
					if !deterministicExpr(ctx, i.Expression) {
						return false
					}
				}
				for _, i := range c.OrderBy {
					if !deterministicExpr(ctx, i.Expression) {
						return false
					}
				}
			default:
				return false
			}
		}
	}
	return true
}
func deterministicExpr(ctx context.Context, expression cypher.Expr) bool {
	if ctx.Err() != nil {
		return false
	}
	all := func(values ...cypher.Expr) bool {
		for _, v := range values {
			if !deterministicExpr(ctx, v) {
				return false
			}
		}
		return true
	}
	switch e := expression.(type) {
	case nil, cypher.Literal, cypher.Variable:
		return true
	case cypher.Property:
		return all(e.Object)
	case cypher.Binary:
		return all(e.Left, e.Right)
	case cypher.Unary:
		return all(e.Operand)
	case cypher.Call:
		// Deliberately explicit: rand/timestamp and any newly added functions cannot
		// accidentally become cacheable because a blacklist missed them.
		switch strings.ToLower(e.Name) {
		case "coalesce", "exists", "id", "elementid", "qualifiedid", "graphid", "graphids", "labels", "type", "nodes", "relationships", "properties", "keys", "size", "length", "head", "last", "tail", "tostring", "tointeger", "toint", "tofloat", "toboolean", "tolower", "tolowercase", "toupper", "touppercase", "trim", "ltrim", "rtrim", "replace", "split", "substring", "left", "right", "reverse", "range", "abs", "ceil", "ceiling", "floor", "round", "sign", "sqrt", "exp", "log", "log10", "sin", "cos", "tan", "cot", "asin", "acos", "atan", "atan2", "degrees", "radians", "haversin", "pi", "e", "count", "collect", "sum", "avg", "min", "max", "stdev", "stdevp", "percentilecont", "percentiledisc":
			return all(e.Arguments...)
		default:
			return false
		}
	case cypher.List:
		return all(e.Elements...)
	case cypher.Map:
		for _, v := range e.Entries {
			if !all(v) {
				return false
			}
		}
		return true
	case cypher.Index:
		return all(e.Object, e.Index)
	case cypher.Slice:
		return all(e.Object, e.From, e.To)
	case cypher.Case:
		if !all(e.Test, e.Else) {
			return false
		}
		for _, w := range e.Whens {
			if !all(w.Condition, w.Result) {
				return false
			}
		}
		return true
	case cypher.ListComprehension:
		return all(e.List, e.Where, e.Projection)
	case cypher.Predicate:
		return all(e.List, e.Where)
	default:
		return false
	}
}
