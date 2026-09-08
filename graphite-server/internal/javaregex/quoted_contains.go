package javaregex

import (
	"context"
	"strings"
)

// This plan is installed only after the ordinary regex parser has succeeded.
// It recognizes exactly . * + quoted literal + . * (without the spaces), with
// default flags. Nothing in the caller's candidate or expression order changes.
type quotedContains struct {
	literal  string
	fallback []int
}

func compileQuotedContains(ctx context.Context, pattern string) (*quotedContains, error) {
	const prefix, suffix = `.*\Q`, `\E.*`
	if !strings.HasPrefix(pattern, prefix) || !strings.HasSuffix(pattern, suffix) || len(pattern) <= len(prefix)+len(suffix) {
		return nil, nil
	}
	literal := pattern[len(prefix) : len(pattern)-len(suffix)]
	for i := 0; i < len(literal); i++ {
		if i&1023 == 0 {
			if err := ctx.Err(); err != nil {
				return nil, err
			}
		}
		b := literal[i]
		if b >= 0x80 || b == '\r' || b == '\n' || b == '\\' && i+1 < len(literal) && literal[i+1] == 'E' {
			return nil, nil
		}
	}
	plan := &quotedContains{literal: literal, fallback: make([]int, len(literal))}
	// A prefix table bounds work even for long, repetitive literals. Retaining
	// it in the immutable compiled pattern avoids allocation on each match.
	work := 0
	for i, j := 1, 0; i < len(literal); i++ {
		for {
			if work&1023 == 0 {
				if err := ctx.Err(); err != nil {
					return nil, err
				}
			}
			work++
			if literal[i] == literal[j] {
				j++
				break
			}
			if j == 0 {
				break
			}
			j = plan.fallback[j-1]
		}
		plan.fallback[i] = j
	}
	return plan, ctx.Err()
}

// matches handles ASCII input only. Java's default dot cannot span CR or LF;
// finding the literal is therefore insufficient until all input is checked.
// Non-ASCII (including UTF-8/WTF-8) falls back to the complete Java matcher.
func (p *quotedContains) matches(ctx context.Context, text string) (matched, handled bool, err error) {
	position, work := 0, 0
	lineBreak := false
	for i := 0; i < len(text); i++ {
		b := text[i]
		if work&1023 == 0 {
			if err := ctx.Err(); err != nil {
				return false, true, err
			}
		}
		work++
		if b >= 0x80 {
			return false, false, nil
		}
		if b == '\r' || b == '\n' {
			lineBreak = true
		}
		if matched || lineBreak {
			continue
		}
		for position > 0 && b != p.literal[position] {
			if work&1023 == 0 {
				if err := ctx.Err(); err != nil {
					return false, true, err
				}
			}
			work++
			position = p.fallback[position-1]
		}
		if b == p.literal[position] {
			position++
			matched = position == len(p.literal)
		}
	}
	if err := ctx.Err(); err != nil {
		return false, true, err
	}
	return matched && !lineBreak, true, nil
}
