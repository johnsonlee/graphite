// Package javaregex implements Java Pattern-style whole-string matching in Go.
// Parsing and backtracking cooperate with context cancellation; no JVM is used.
package javaregex

import (
	"context"
	"fmt"
	"strings"
	"unicode/utf16"
)

// SyntaxError carries Java PatternSyntaxException fields and message format.
type SyntaxError struct {
	Description, Pattern string
	Index                int
}

func (e *SyntaxError) Error() string {
	if e.Index < 0 {
		return e.Description + "\n" + e.Pattern
	}
	message := fmt.Sprintf("%s near index %d\n%s", e.Description, e.Index, e.Pattern)
	if e.Index < javaIndex([]rune(e.Pattern), len([]rune(e.Pattern))) {
		message += "\n" + strings.Repeat(" ", e.Index) + "^"
	}
	return message
}

// UnsupportedError never silently substitutes a different regex dialect.
type UnsupportedError struct{ Feature string }

func (e *UnsupportedError) Error() string {
	return "Java regex feature is not implemented: " + e.Feature
}

type flags uint16

const (
	flagI flags = 1 << iota
	flagUCase
	flagUClass
	flagS
	flagM
	flagD
	flagX
	flagCanonical
)

type predicate func(rune) bool

type node struct {
	kind     byte
	children []*node
	pred     predicate
	literal  []rune
	flags    flags
	min, max int
	mode     byte
	group    int
	name     string
}

// Pattern is immutable after compilation and safe for concurrent matching.
type Pattern struct {
	source string
	root   *node
	groups int
}

func Compile(pattern string) (*Pattern, error) { return CompileContext(context.Background(), pattern) }
func CompileContext(ctx context.Context, pattern string) (*Pattern, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	p := parser{ctx: ctx, source: pattern, input: []rune(pattern), names: map[string]int{}}
	root, err := p.expression(false)
	if err != nil {
		return nil, err
	}
	if p.pos < len(p.input) {
		return nil, p.fail("Unmatched closing ')'", p.pos-1)
	}
	return &Pattern{source: pattern, root: root, groups: p.groups}, nil
}
func MatchesContext(ctx context.Context, pattern, text string) (bool, error) {
	p, err := CompileContext(ctx, pattern)
	if err != nil {
		return false, err
	}
	return p.MatchesContext(ctx, text)
}
func (p *Pattern) MatchesContext(ctx context.Context, text string) (bool, error) {
	if err := ctx.Err(); err != nil {
		return false, err
	}
	runes := make([]rune, 0, len(text))
	for _, r := range text {
		if len(runes)&1023 == 0 {
			if err := ctx.Err(); err != nil {
				return false, err
			}
		}
		runes = append(runes, r)
	}
	m := matcher{ctx: ctx, text: runes}
	captures := make([]capture, p.groups+1)
	ok := m.match(p.root, state{captures: captures}, func(s state) bool { return s.pos == len(m.text) })
	if m.err != nil {
		return false, m.err
	}
	if err := ctx.Err(); err != nil {
		return false, err
	}
	return ok, nil
}
func javaIndex(runes []rune, end int) int {
	if end < 0 {
		return end
	}
	if end > len(runes) {
		end = len(runes)
	}
	n := 0
	for _, r := range runes[:end] {
		if utf16.IsSurrogate(r) || r < 0x10000 {
			n++
		} else {
			n += 2
		}
	}
	return n
}
