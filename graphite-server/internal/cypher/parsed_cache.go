package cypher

import (
	"container/list"
	"context"
	"runtime/debug"
	"strings"
	"sync"
	"unicode/utf8"
)

const maxParsedQueryEntries = 1024
const maxParsedQueryBytes int64 = 16 * 1024 * 1024

// Go's memory limit is a soft runtime limit, rather than the JVM's maximum
// heap. Both unlimited Go and the benchmark's -Xmx8g use the 16 MiB ceiling.
var parsedQueries = &parsedQueryCache{maxEntries: maxParsedQueryEntries, byteLimit: func() int64 {
	return min(maxParsedQueryBytes, debug.SetMemoryLimit(-1)/128)
}}

type parsedQueryEntry struct {
	source string
	query  *Query // Private: never returned to a parser caller.
	bytes  int64
}

type parsedQueryCache struct {
	mu         sync.Mutex
	entries    map[string]*list.Element
	lru        list.List
	bytes      int64
	maxEntries int
	maxBytes   int64
	byteLimit  func() int64
	limitOnce  sync.Once
}

func (cache *parsedQueryCache) parse(ctx context.Context, source string) *Query {
	if err := ctx.Err(); err != nil {
		panic(err)
	}
	if strings.TrimFunc(source, kotlinWhitespace) == "" {
		return &Query{Branches: []SingleQuery{{}}}
	}
	cache.mu.Lock()
	entry := cache.entries[source]
	if entry != nil {
		cache.lru.MoveToBack(entry)
	}
	cache.mu.Unlock()
	if entry != nil {
		// The AST API exposes mutable slices and maps. A private cached tree
		// plus an owned copy preserves main's protection against cache pollution.
		return cloneQuery(ctx, entry.Value.(*parsedQueryEntry).query)
	}
	query := cache.parseUncached(ctx, source)
	if err := ctx.Err(); err != nil {
		panic(err)
	}
	cache.limitOnce.Do(func() {
		if cache.byteLimit != nil {
			cache.maxBytes = cache.byteLimit()
		}
	})
	bytes := estimatedParsedQueryBytes(ctx, source, query)
	if bytes > cache.maxBytes || cache.maxEntries <= 0 {
		return query
	}
	owned := cloneQuery(ctx, query)
	cache.mu.Lock()
	defer cache.mu.Unlock()
	if cache.entries[source] != nil {
		return query
	}
	for cache.lru.Len() > 0 && (cache.lru.Len() >= cache.maxEntries || cache.bytes > cache.maxBytes-bytes) {
		eldest := cache.lru.Front()
		old := eldest.Value.(*parsedQueryEntry)
		delete(cache.entries, old.source)
		cache.bytes -= old.bytes
		cache.lru.Remove(eldest)
	}
	if cache.entries == nil {
		cache.entries = make(map[string]*list.Element)
	}
	cache.entries[source] = cache.lru.PushBack(&parsedQueryEntry{source: source, query: owned, bytes: bytes})
	cache.bytes += bytes
	return query
}

func estimatedParsedQueryBytes(ctx context.Context, source string, query *Query) int64 {
	// Java String.length counts UTF-16 units, including isolated WTF-8
	// surrogates preserved by the native string boundary.
	var units int64
	nextCheck := 0
	for i := 0; i < len(source); {
		if i >= nextCheck {
			if err := ctx.Err(); err != nil {
				panic(err)
			}
			nextCheck = i + 4096
		}
		if i+2 < len(source) && source[i] == 0xed && source[i+1] >= 0xa0 && source[i+1] <= 0xbf && source[i+2]&0xc0 == 0x80 {
			units++
			i += 3
			continue
		}
		r, size := utf8.DecodeRuneInString(source[i:])
		units++
		if r > 0xffff {
			units++
		}
		i += size
	}
	clauses := int64(len(query.UnionAll))
	for _, branch := range query.Branches {
		clauses += int64(len(branch.Clauses))
		for _, clause := range branch.Clauses {
			switch c := clause.(type) {
			case MatchClause:
				if !c.Optional && c.Where != nil {
					clauses++
				}
			case ProjectionClause:
				if len(c.OrderBy) > 0 {
					clauses++
				}
				if c.Skip != nil {
					clauses++
				}
				if c.Limit != nil {
					clauses++
				}
			}
		}
	}
	return 128 + 2*units + 2048*clauses
}
