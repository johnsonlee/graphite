package javaregex

import (
	"bytes"
	"compress/gzip"
	"context"
	_ "embed"
	"encoding/json"
	"io"
	"strconv"
	"strings"
	"sync"
)

//go:embed java17_advanced.json
var advancedJSON []byte

//go:embed java17_names.json.gz
var namesGZIP []byte
var advancedOnce sync.Once
var advanced struct {
	GraphemeRanges   map[int][]rune
	Decompositions   map[rune]string
	Compositions     map[string]rune
	CombiningClasses map[rune]int
}

func advancedData() {
	advancedOnce.Do(func() {
		if err := json.Unmarshal(advancedJSON, &advanced); err != nil {
			panic(err)
		}
	})
}
func graphemeType(r rune) int {
	advancedData()
	for t, ranges := range advanced.GraphemeRanges {
		if inRanges(r, ranges) {
			return t
		}
	}
	return 0
}

// These classes describe UAX29 grapheme behavior as exposed by Java17's
// development oracle; the runtime has no dependency on JDK internals.
const (
	gOther = iota
	gCR
	gLF
	gControl
	gExtend
	gZWJ
	gRI
	gPrepend
	gSpacing
	gL
	gV
	gT
	gLV
	gLVT
	gPictograph
)

func (m *matcher) graphemeEnd(start int) int {
	if start >= len(m.text) {
		return start
	}
	previous := graphemeType(m.text[start])
	regional := 0
	if previous == gRI {
		regional = 1
	}
	pictograph := previous == gPictograph
	for i := start + 1; i < len(m.text); i++ {
		if !m.check() {
			return start
		}
		current := graphemeType(m.text[i])
		join := false
		switch {
		case previous == gCR && current == gLF:
			join = true
		case previous >= gCR && previous <= gControl || current >= gCR && current <= gControl:
			join = false
		case previous == gL && (current == gL || current == gV || current == gLV || current == gLVT):
			join = true
		case (previous == gLV || previous == gV) && (current == gV || current == gT):
			join = true
		case (previous == gLVT || previous == gT) && current == gT:
			join = true
		case current == gExtend || current == gZWJ || current == gSpacing || previous == gPrepend:
			join = true
		case pictograph && previous == gZWJ && current == gPictograph:
			join = true
		case regional%2 == 1 && previous == gRI && current == gRI:
			join = true
		}
		if !join {
			return i
		}
		if current == gRI {
			regional++
		}
		previous = current
	}
	return len(m.text)
}
func (m *matcher) graphemeBoundary(pos int) bool {
	if len(m.text) == 0 {
		return true
	}
	if pos == 0 || pos == len(m.text) {
		return true
	}
	for i := 0; i < pos; {
		next := m.graphemeEnd(i)
		if m.err != nil || next <= i {
			return false
		}
		i = next
		if i == pos {
			return true
		}
		if i > pos {
			return false
		}
	}
	return false
}

// canonicalRune returns a single NFC code point, using Java17's own canonical
// decomposition/composition and combining-class data. Multi-codepoint NFC
// results deliberately fail the single-character property test.
func (m *matcher) canonicalRune(start, end int) (rune, bool) {
	advancedData()
	decomposed := make([]rune, 0, end-start)
	for _, r := range m.text[start:end] {
		if !m.check() {
			return 0, false
		}
		if d, ok := advanced.Decompositions[r]; ok {
			decomposed = append(decomposed, []rune(d)...)
		} else {
			decomposed = append(decomposed, r)
		}
	}
	// Stable canonical ordering; starters (class zero) are never crossed.
	for i := 1; i < len(decomposed); i++ {
		ccc := advanced.CombiningClasses[decomposed[i]]
		if ccc == 0 {
			continue
		}
		for j := i; j > 0; j-- {
			if !m.check() {
				return 0, false
			}
			previous := advanced.CombiningClasses[decomposed[j-1]]
			if previous == 0 || previous <= ccc {
				break
			}
			decomposed[j], decomposed[j-1] = decomposed[j-1], decomposed[j]
		}
	}
	if len(decomposed) == 1 {
		return decomposed[0], true
	}
	r, ok := advanced.Compositions[string(decomposed)]
	return r, ok
}

type contextReader struct {
	ctx    context.Context
	reader io.Reader
}

func (r contextReader) Read(p []byte) (int, error) {
	if err := r.ctx.Err(); err != nil {
		return 0, err
	}
	return r.reader.Read(p)
}

var namesMu sync.RWMutex
var characterNames []byte

func namedCharacter(ctx context.Context, name string) (rune, bool, error) {
	namesMu.RLock()
	names := characterNames
	namesMu.RUnlock()
	if names == nil {
		reader, err := gzip.NewReader(contextReader{ctx, bytes.NewReader(namesGZIP)})
		if err != nil {
			return 0, false, err
		}
		decoded, err := io.ReadAll(contextReader{ctx, reader})
		reader.Close()
		if err != nil {
			return 0, false, err
		}
		if err = ctx.Err(); err != nil {
			return 0, false, err
		}
		namesMu.Lock()
		if characterNames == nil {
			characterNames = decoded
		}
		names = characterNames
		namesMu.Unlock()
	}
	name = strings.ToUpper(strings.TrimFunc(name, func(r rune) bool { return r <= 32 }))
	key, _ := json.Marshal(name)
	key = append(key, ':')
	index := bytes.Index(names, key)
	if index < 0 {
		return 0, false, ctx.Err()
	}
	start := index + len(key)
	end := start
	for end < len(names) && names[end] >= '0' && names[end] <= '9' {
		end++
	}
	codepoint, err := strconv.ParseInt(string(names[start:end]), 10, 32)
	if err != nil {
		return 0, false, err
	}
	if err = ctx.Err(); err != nil {
		return 0, false, err
	}
	return rune(codepoint), true, nil
}
