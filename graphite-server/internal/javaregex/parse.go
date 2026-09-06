package javaregex

import (
	"context"
	"strconv"
)

type parser struct {
	ctx         context.Context
	source      string
	input       []rune
	pos, groups int
	flags       flags
	names       map[string]int
	quoted      []rune
}

func (p *parser) fail(description string, index int) error {
	return &SyntaxError{description, p.source, index}
}
func (p *parser) skip() {
	if p.flags&flagX == 0 {
		return
	}
	for p.pos < len(p.input) {
		if p.pos&1023 == 0 && p.ctx.Err() != nil {
			return
		}
		r := p.input[p.pos]
		if r == '#' {
			for p.pos < len(p.input) && !lineTerminator(p.input[p.pos], p.flags) {
				if p.pos&1023 == 0 && p.ctx.Err() != nil {
					return
				}
				p.pos++
			}
			continue
		}
		if r == ' ' || r == '\t' || r == '\n' || r == '\r' || r == '\f' || r == 11 {
			p.pos++
			continue
		}
		break
	}
}
func (p *parser) peek() rune {
	p.skip()
	if p.pos == len(p.input) {
		return -1
	}
	return p.input[p.pos]
}
func (p *parser) take() rune {
	r := p.peek()
	if r >= 0 {
		p.pos++
	}
	return r
}
func (p *parser) expression(group bool) (*node, error) {
	var branches []*node
	for {
		var sequence []*node
		for len(p.quoted) > 0 || (p.peek() != -1 && p.peek() != ')' && p.peek() != '|') {
			if err := p.ctx.Err(); err != nil {
				return nil, err
			}
			n, err := p.atom()
			if err != nil {
				return nil, err
			}
			if len(p.quoted) == 0 {
				n, err = p.quantify(n)
				if err != nil {
					return nil, err
				}
			}
			if n.kind == 'l' && len(sequence) > 0 && sequence[len(sequence)-1].kind == 'l' && sequence[len(sequence)-1].flags == n.flags {
				last := sequence[len(sequence)-1]
				last.literal = append(last.literal, n.literal...)
			} else {
				sequence = append(sequence, n)
			}
		}
		branches = append(branches, &node{kind: 's', children: sequence})
		if p.peek() != '|' {
			break
		}
		p.pos++
	}
	if len(branches) == 1 {
		return branches[0], nil
	}
	return &node{kind: '|', children: branches}, nil
}
func (p *parser) atom() (*node, error) {
	if len(p.quoted) > 0 {
		r := p.quoted[0]
		p.quoted = p.quoted[1:]
		return p.literal(r), nil
	}
	r := p.take()
	switch r {
	case '(':
		return p.group()
	case '[':
		pred, err := p.class()
		kind := byte('p')
		if p.flags&flagCanonical != 0 {
			kind = 'C'
		}
		return &node{kind: kind, pred: pred}, err
	case '.':
		f := p.flags
		return &node{kind: 'p', pred: func(r rune) bool { return f&flagS != 0 || !lineTerminator(r, f) }}, nil
	case '^', '$':
		return &node{kind: 'a', mode: byte(r), flags: p.flags}, nil
	case '\\':
		return p.escape(false)
	case '*', '+', '?':
		return nil, p.fail("Dangling meta character '"+string(r)+"'", p.pos-1)
	case '{':
		return nil, p.fail("Illegal repetition", p.pos)
	}
	return p.literal(r), nil
}
func (p *parser) literal(r rune) *node { return &node{kind: 'l', literal: []rune{r}, flags: p.flags} }
func (p *parser) group() (*node, error) {
	previous := p.flags
	defer func() { p.flags = previous }()
	kind := byte('g')
	index := 0
	if p.peek() == '?' {
		p.pos++
		switch p.take() {
		case ':':
			kind = 's'
		case '=':
			kind = '='
		case '!':
			kind = '!'
		case '>':
			kind = '>'
		case '<':
			if p.peek() == '=' || p.peek() == '!' {
				if p.take() == '=' {
					kind = '<'
				} else {
					kind = ','
				}
			} else {
				start := p.pos
				for p.peek() != -1 && p.peek() != '>' {
					p.pos++
				}
				name := javaRunesString(p.input[start:p.pos])
				if len(name) == 0 || !asciiLetter(rune(name[0])) {
					return nil, p.fail("capturing group name does not start with a Latin letter", start)
				}
				for i, r := range name {
					if !asciiLetter(r) && (r < '0' || r > '9') {
						return nil, p.fail("named capturing group is missing trailing '>'", start+i)
					}
				}
				if p.take() != '>' {
					return nil, p.fail("named capturing group is missing trailing '>'", p.pos)
				}
				if _, ok := p.names[name]; ok {
					return nil, p.fail("Named capturing group <"+name+"> is already defined", p.pos-1)
				}
				p.groups++
				index = p.groups
				p.names[name] = index
			}
		default:
			p.pos--
			disable := false
			for {
				r := p.peek()
				if r == '-' {
					if disable {
						break
					}
					disable = true
					p.pos++
					continue
				}
				bit := flagFor(r)
				if bit == 0 {
					break
				}
				p.pos++
				if disable {
					p.flags &^= bit
					if bit == flagUClass {
						p.flags &^= flagUCase
					}
				} else {
					p.flags |= bit
					if bit == flagUClass {
						p.flags |= flagUCase
					}
				}
			}

			r := p.take()
			if r == ')' {
				previous = p.flags
				return &node{kind: 'e'}, nil
			}
			if r != ':' {
				index := p.pos - 1
				if r < 0 {
					index = p.pos
				}
				return nil, p.fail("Unknown inline modifier", index)
			}
			kind = 's'
		}
	}
	if kind == 'g' && index == 0 {
		p.groups++
		index = p.groups
	}
	inner, err := p.expression(true)
	if err != nil {
		return nil, err
	}
	if (kind == '<' || kind == ',') && hasBackreference(inner) {
		return nil, p.fail("Look-behind group does not have an obvious maximum length", p.pos-1)
	}
	if p.take() != ')' {
		return nil, p.fail("Unclosed group", p.pos)
	}
	if kind == 's' {
		return inner, nil
	}
	return &node{kind: kind, children: []*node{inner}, group: index}, nil
}
func flagFor(r rune) flags {
	switch r {
	case 'i':
		return flagI
	case 'u':
		return flagUCase
	case 'U':
		return flagUClass
	case 's':
		return flagS
	case 'm':
		return flagM
	case 'd':
		return flagD
	case 'x':
		return flagX
	case 'c':
		return flagCanonical
	}
	return 0
}
func asciiLetter(r rune) bool { return r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' }
func (p *parser) quantify(n *node) (*node, error) {
	min, max := 0, 0
	switch p.peek() {
	case '*':
		p.pos++
		max = -1
	case '+':
		p.pos++
		min = 1
		max = -1
	case '?':
		p.pos++
		max = 1
	case '{':
		p.pos++
		start := p.pos
		for p.peek() >= '0' && p.peek() <= '9' {
			p.pos++
		}
		if p.pos == start {
			return nil, p.fail("Illegal repetition", p.pos)
		}
		v, err := strconv.ParseInt(javaRunesString(p.input[start:p.pos]), 10, 32)
		if err != nil {
			return nil, p.fail("Illegal repetition range", overflowIndex(p.input, start, p.pos))
		}
		min = int(v)
		max = min
		if p.peek() == ',' {
			p.pos++
			start = p.pos
			for p.peek() >= '0' && p.peek() <= '9' {
				p.pos++
			}
			max = -1
			if p.pos > start {
				v, err = strconv.ParseInt(javaRunesString(p.input[start:p.pos]), 10, 32)
				if err != nil {
					return nil, p.fail("Illegal repetition range", overflowIndex(p.input, start, p.pos))
				}
				max = int(v)
			}
		}
		end := p.take()
		if end != '}' {
			index := p.pos - 1
			if end < 0 {
				index = p.pos
			}
			return nil, p.fail("Unclosed counted closure", index)
		}
		if max >= 0 && max < min {
			return nil, p.fail("Illegal repetition range", p.pos-1)
		}
	default:
		return n, nil
	}
	mode := byte(0)
	if p.peek() == '?' || p.peek() == '+' {
		mode = byte(p.take())
	}
	return &node{kind: 'q', children: []*node{n}, min: min, max: max, mode: mode}, nil
}
func (p *parser) escape(inClass bool) (*node, error) {
	if p.pos == len(p.input) {
		return nil, p.fail("Unexpected internal error", p.pos)
	}
	r := p.input[p.pos]
	p.pos++
	switch r {
	case 'Q':
		for p.pos < len(p.input) {
			if p.pos&1023 == 0 {
				if err := p.ctx.Err(); err != nil {
					return nil, err
				}
			}
			if p.input[p.pos] == '\\' && p.pos+1 < len(p.input) && p.input[p.pos+1] == 'E' {
				p.pos += 2
				break
			}
			p.quoted = append(p.quoted, p.input[p.pos])
			p.pos++
		}
		if len(p.quoted) == 0 {
			return &node{kind: 'e'}, nil
		}
		r = p.quoted[0]
		p.quoted = p.quoted[1:]
		return p.literal(r), nil
	case 'd', 'D', 's', 'S', 'w', 'W', 'h', 'H', 'v', 'V':
		return &node{kind: 'p', pred: predefined(r, p.flags)}, nil
	case 'p', 'P':
		start := p.pos
		name := ""
		if p.pos < len(p.input) && p.input[p.pos] == '{' {
			p.pos++
			start = p.pos
			for p.pos < len(p.input) && p.input[p.pos] != '}' {
				p.pos++
			}
			if p.pos == len(p.input) {
				return nil, p.fail("Unclosed character family", start)
			}
			name = javaRunesString(p.input[start:p.pos])
			p.pos++
		} else {
			if p.pos == len(p.input) {
				return nil, p.fail("Unknown character property name {"+name+"}", p.pos)
			}
			name = javaRunesString([]rune{p.input[p.pos]})
			p.pos++
		}
		if name == "" {
			return nil, p.fail("Empty character family", p.pos-1)
		}
		pred, ok := property(name, p.flags)
		if !ok {
			return nil, p.fail("Unknown character property name {"+name+"}", p.pos-1)
		}
		if r == 'P' {
			pred = negate(pred)
		}
		kind := byte('p')
		if p.flags&flagCanonical != 0 && !inClass {
			kind = 'C'
		}
		return &node{kind: kind, pred: pred}, nil
	case 'A', 'G', 'z', 'Z', 'b', 'B':
		if inClass {
			return nil, p.fail("Illegal/unsupported escape sequence", p.pos-1)
		}
		if r == 'b' && p.pos < len(p.input) && p.input[p.pos] == '{' {
			if p.pos+3 <= len(p.input) && javaRunesString(p.input[p.pos:p.pos+3]) == "{g}" {
				p.pos += 3
				return &node{kind: 'a', mode: 'g'}, nil
			}
		}
		return &node{kind: 'a', mode: byte(r), flags: p.flags}, nil
	case 'R':
		if inClass {
			return nil, p.fail("Illegal/unsupported escape sequence", p.pos-1)
		}
		return &node{kind: 's', children: []*node{{kind: '|', children: []*node{{kind: 'l', literal: []rune{'\r', '\n'}}, {kind: 'p', pred: func(r rune) bool {
			return r == '\r' || r == '\n' || r == 11 || r == 12 || r == 0x85 || r == 0x2028 || r == 0x2029
		}}}}}}, nil
	case 'X':
		if inClass {
			return nil, p.fail("Illegal/unsupported escape sequence", p.pos-1)
		}
		return &node{kind: 'X'}, nil
	case 'N':
		if p.pos == len(p.input) || p.input[p.pos] != '{' {
			return nil, p.fail("Illegal character name escape sequence", p.pos)
		}
		p.pos++
		start := p.pos
		for p.pos < len(p.input) && p.input[p.pos] != '}' {
			p.pos++
		}
		if p.pos == len(p.input) {
			return nil, p.fail("Unclosed character name escape sequence", start)
		}
		name := javaRunesString(p.input[start:p.pos])
		p.pos++
		ch, ok, err := namedCharacter(p.ctx, name)
		if err != nil {
			return nil, err
		}
		if !ok {
			return nil, p.fail("Unknown character name ["+name+"]", p.pos-1)
		}
		return p.literal(ch), nil
	case 't':
		return p.literal('\t'), nil
	case 'n':
		return p.literal('\n'), nil
	case 'r':
		return p.literal('\r'), nil
	case 'f':
		return p.literal('\f'), nil
	case 'a':
		return p.literal(7), nil
	case 'e':
		return p.literal(27), nil
	case 'c':
		if p.pos == len(p.input) {
			return nil, p.fail("Illegal control escape sequence", p.pos)
		}
		v := p.input[p.pos] ^ 64
		p.pos++
		return p.literal(v), nil
	case 'u', 'x':
		digits := 2
		if r == 'u' {
			digits = 4
		}
		braced := r == 'x' && p.pos < len(p.input) && p.input[p.pos] == '{'
		if braced {
			p.pos++
			digits = -1
		}
		start := p.pos
		v := int64(0)
		count := 0
		for p.pos < len(p.input) && (digits < 0 || count < digits) {
			h := hex(p.input[p.pos])
			if h < 0 {
				break
			}
			v = v*16 + int64(h)
			p.pos++
			count++
			if v > 0x10ffff {
				return nil, p.fail("Hexadecimal codepoint is too big", p.pos-1)
			}
		}
		if count == 0 || digits >= 0 && count != digits || braced && (p.pos == len(p.input) || p.input[p.pos] != '}') {
			desc := "Illegal hexadecimal escape sequence"
			if r == 'u' {
				desc = "Illegal Unicode escape sequence"
			}
			index := p.pos
			if braced && count == 0 {
				index = start - 1
			}
			return nil, p.fail(desc, index)
		}
		if braced {
			p.pos++
		}
		_ = start
		// Java merges adjacent escaped surrogate pairs into a code point.
		if v >= 0xD800 && v <= 0xDBFF && p.pos+6 <= len(p.input) && javaRunesString(p.input[p.pos:p.pos+2]) == "\\u" {
			low, e := strconv.ParseInt(javaRunesString(p.input[p.pos+2:p.pos+6]), 16, 32)
			if e == nil && low >= 0xDC00 && low <= 0xDFFF {
				v = 0x10000 + (v-0xD800)*1024 + low - 0xDC00
				p.pos += 6
			}
		}
		return p.literal(rune(v)), nil
	case '0':
		v := rune(0)
		count := 0
		for p.pos < len(p.input) && count < 3 {
			d := p.input[p.pos]
			if d < '0' || d > '7' || count == 2 && v >= 32 {
				break
			}
			v = v*8 + d - '0'
			p.pos++
			count++
		}
		if count == 0 {
			return nil, p.fail("Illegal octal escape sequence", p.pos)
		}
		return p.literal(v), nil
	case 'k':
		if p.pos == len(p.input) || p.input[p.pos] != '<' {
			return nil, p.fail("\\k is not followed by '<' for named capturing group", p.pos)
		}
		p.pos++
		start := p.pos
		for p.pos < len(p.input) && p.input[p.pos] != '>' {
			p.pos++
		}
		name := javaRunesString(p.input[start:p.pos])
		if p.pos == len(p.input) {
			return nil, p.fail("named capturing group is missing trailing '>'", p.pos)
		}
		p.pos++
		index, ok := p.names[name]
		if !ok {
			return nil, p.fail("named capturing group <"+name+"> does not exist", p.pos-1)
		}
		return &node{kind: 'r', group: index, flags: p.flags}, nil
	}
	if r >= '1' && r <= '9' && !inClass {
		index := int(r - '0')
		for p.pos < len(p.input) && p.input[p.pos] >= '0' && p.input[p.pos] <= '9' {
			v := index*10 + int(p.input[p.pos]-'0')
			if v > p.groups {
				break
			}
			index = v
			p.pos++
		}
		return &node{kind: 'r', group: index, flags: p.flags}, nil
	}
	if asciiLetter(r) || r >= '1' && r <= '9' {
		return nil, p.fail("Illegal/unsupported escape sequence", p.pos-1)
	}
	return p.literal(r), nil
}
func hex(r rune) int {
	if r >= '0' && r <= '9' {
		return int(r - '0')
	}
	if r >= 'a' && r <= 'f' {
		return int(r - 'a' + 10)
	}
	if r >= 'A' && r <= 'F' {
		return int(r - 'A' + 10)
	}
	return -1
}
func negate(p predicate) predicate       { return func(r rune) bool { return !p(r) } }
func union(a, b predicate) predicate     { return func(r rune) bool { return a(r) || b(r) } }
func intersect(a, b predicate) predicate { return func(r rune) bool { return a(r) && b(r) } }
func (p *parser) class() (predicate, error) {
	negative := p.peek() == '^'
	if negative {
		p.pos++
	}
	first := true
	var result predicate
	for {
		var term predicate
		for {
			if err := p.ctx.Err(); err != nil {
				return nil, err
			}
			r := p.peek()
			if r == -1 {
				return nil, p.fail("Unclosed character class", p.pos-1)
			}
			if len(p.quoted) == 0 && (r == ']' && !first || r == '&' && p.pos+1 < len(p.input) && p.input[p.pos+1] == '&') {
				break
			}
			atom, single, err := p.classAtom()
			if err != nil {
				return nil, err
			}
			first = false
			if single >= 0 && len(p.quoted) == 0 && p.peek() == '-' && p.pos+1 < len(p.input) && p.input[p.pos+1] != ']' && p.input[p.pos+1] != '[' {
				p.pos++
				other, end, err := p.classAtom()
				_ = other
				if err != nil {
					return nil, err
				}
				if single < 0 || end < 0 || end < single {
					return nil, p.fail("Illegal character range", p.pos-1)
				}
				lo, hi := single, end
				atom = foldPredicate(func(r rune) bool { return r >= lo && r <= hi }, p.flags)
			}
			if term == nil {
				term = atom
			} else {
				term = union(term, atom)
			}
		}
		if term != nil {
			if result == nil {
				result = term
			} else {
				result = intersect(result, term)
			}
		}
		if p.peek() == ']' {
			p.pos++
			break
		}
		p.pos += 2
		first = false
	}
	if result == nil {
		return nil, p.fail("Bad class syntax", p.pos-2)
	}
	if negative {
		result = negate(result)
	}
	return result, nil
}
func (p *parser) classAtom() (predicate, rune, error) {
	var r rune
	if len(p.quoted) > 0 {
		r = p.quoted[0]
		p.quoted = p.quoted[1:]
		literal := r
		return foldPredicate(func(v rune) bool { return v == literal }, p.flags), literal, nil
	}
	r = p.take()
	if r == '[' {
		pred, err := p.class()
		return pred, -1, err
	}
	if r == '\\' {
		n, err := p.escape(true)
		if err != nil {
			return nil, -1, err
		}
		if n.kind == 'p' {
			return n.pred, -1, nil
		}
		if n.kind == 'l' {
			r = n.literal[0]
		} else {
			return nil, -1, p.fail("Illegal/unsupported escape sequence", p.pos-1)
		}
	}
	literal := r
	return foldPredicate(func(v rune) bool { return v == literal }, p.flags), literal, nil
}

func overflowIndex(input []rune, start, end int) int {
	var v int64
	for i := start; i < end; i++ {
		v = v*10 + int64(input[i]-'0')
		if v > 2147483647 {
			return i
		}
	}
	return end - 1
}

func hasBackreference(n *node) bool {
	if n.kind == 'r' {
		return true
	}
	for _, c := range n.children {
		if hasBackreference(c) {
			return true
		}
	}
	return false
}
