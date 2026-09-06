package javaregex

import "context"

type capture struct {
	start, end int
	set        bool
}
type state struct {
	pos      int
	captures []capture
}
type matcher struct {
	ctx   context.Context
	text  []rune
	err   error
	steps uint64
}

func (m *matcher) check() bool {
	m.steps++
	if m.steps&255 == 1 {
		m.err = m.ctx.Err()
	}
	return m.err == nil
}
func (m *matcher) match(n *node, s state, next func(state) bool) bool {
	if !m.check() {
		return false
	}
	switch n.kind {
	case 'e':
		return next(s)
	case 's':
		return m.sequence(n.children, 0, s, next)
	case '|':
		for _, c := range n.children {
			if m.match(c, s, next) {
				return true
			}
		}
		return false
	case 'l':
		if len(m.text)-s.pos < len(n.literal) {
			return false
		}
		for i, r := range n.literal {
			if !m.check() || !equalRune(m.text[s.pos+i], r, n.flags) {
				return false
			}
		}
		s.pos += len(n.literal)
		return next(s)
	case 'X':
		if s.pos == len(m.text) {
			return false
		}
		s.pos = m.graphemeEnd(s.pos)
		return m.err == nil && next(s)
	case 'C':
		if s.pos == len(m.text) {
			return false
		}
		end := m.graphemeEnd(s.pos)
		if end == s.pos+1 {
			if n.pred(m.text[s.pos]) {
				s.pos++
				return next(s)
			}
			return false
		}
		for end > s.pos+1 {
			r, ok := m.canonicalRune(s.pos, end)
			if m.err != nil {
				return false
			}
			if ok && n.pred(r) {
				v := s
				v.pos = end
				if next(v) {
					return true
				}
			}
			end--
		}
		return false
	case 'p':
		if s.pos < len(m.text) && n.pred(m.text[s.pos]) {
			s.pos++
			return next(s)
		}
		return false
	case 'g':
		start := s.pos
		return m.match(n.children[0], s, func(end state) bool {
			c := append([]capture(nil), end.captures...)
			c[n.group] = capture{start, end.pos, true}
			end.captures = c
			return next(end)
		})
	case 'r':
		if n.group >= len(s.captures) || !s.captures[n.group].set {
			return false
		}
		c := s.captures[n.group]
		if len(m.text)-s.pos < c.end-c.start {
			return false
		}
		for i := c.start; i < c.end; i++ {
			if !m.check() || !equalRune(m.text[i], m.text[s.pos+i-c.start], n.flags) {
				return false
			}
		}
		s.pos += c.end - c.start
		return next(s)
	case 'q':
		child := n.children[0]
		if child.kind == 'p' || child.kind == 'l' && len(child.literal) == 1 {
			return m.simpleRepeat(n, s, next)
		}
		if n.mode == '+' {
			var chosen state
			found := m.repeat(n, s, 0, func(v state) bool { chosen = v; return true })
			return found && next(chosen)
		}
		return m.repeat(n, s, 0, next)
	case '>':
		var chosen state
		return m.match(n.children[0], s, func(v state) bool { chosen = v; return true }) && next(chosen)
	case '=', '!':
		var chosen state
		found := m.match(n.children[0], s, func(v state) bool { chosen = v; return true })
		if n.kind == '!' {
			return !found && m.err == nil && next(s)
		}
		chosen.pos = s.pos
		return found && next(chosen)
	case '<', ',':
		// Java lookbehind searches from the shortest candidate to longer ones.
		for start := s.pos; start >= 0; start-- {
			if !m.check() {
				return false
			}
			candidate := s
			candidate.pos = start
			var chosen state
			found := m.match(n.children[0], candidate, func(v state) bool {
				if v.pos != s.pos {
					return false
				}
				chosen = v
				return true
			})
			if found {
				if n.kind == ',' {
					return false
				}
				return next(chosen)
			}
		}
		return n.kind == ',' && next(s)
	case 'a':
		return m.anchor(n, s.pos) && next(s)
	}
	return false
}
func (m *matcher) sequence(nodes []*node, index int, s state, next func(state) bool) bool {
	if index == len(nodes) {
		return next(s)
	}
	return m.match(nodes[index], s, func(v state) bool { return m.sequence(nodes, index+1, v, next) })
}
func (m *matcher) repeat(n *node, s state, count int, next func(state) bool) bool {
	if !m.check() {
		return false
	}
	if n.mode == '?' && count >= n.min && next(s) {
		return true
	}
	if n.max < 0 || count < n.max {
		if m.match(n.children[0], s, func(v state) bool {
			if v.pos == s.pos && count >= n.min {
				return next(v)
			}
			return m.repeat(n, v, count+1, next)
		}) {
			return true
		}
	}
	return n.mode != '?' && count >= n.min && next(s)
}
func lineTerminator(r rune, f flags) bool {
	return r == '\n' || f&flagD == 0 && (r == '\r' || r == 0x85 || r == 0x2028 || r == 0x2029)
}
func (m *matcher) anchor(n *node, pos int) bool {
	switch n.mode {
	case 'g':
		return m.graphemeBoundary(pos)
	case 'A', 'G':
		return pos == 0
	case 'z':
		return pos == len(m.text)
	case '^':
		if n.flags&flagM != 0 && pos == len(m.text) {
			return false
		}
		return pos == 0 || n.flags&flagM != 0 && pos < len(m.text) && lineTerminator(m.text[pos-1], n.flags) && !(m.text[pos-1] == '\r' && m.text[pos] == '\n' && n.flags&flagD == 0)
	case '$', 'Z':
		if pos == len(m.text) {
			return true
		}
		if !lineTerminator(m.text[pos], n.flags) {
			return false
		}
		if m.text[pos] == '\n' && pos > 0 && m.text[pos-1] == '\r' && n.flags&flagD == 0 {
			return false
		}
		if n.mode == '$' && n.flags&flagM != 0 {
			return true
		}
		return pos == len(m.text)-1 || pos == len(m.text)-2 && m.text[pos] == '\r' && m.text[pos+1] == '\n' && n.flags&flagD == 0
	case 'b', 'B':
		before := pos > 0 && boundaryWord(m.text, pos-1, n.flags)
		after := pos < len(m.text) && boundaryWord(m.text, pos, n.flags)
		return (before != after) == (n.mode == 'b')
	}
	return false
}

// Single-code-point repetition uses bounded stack space even for long values.
func (m *matcher) simpleRepeat(n *node, s state, next func(state) bool) bool {
	atom := n.children[0]
	count := 0
	for s.pos+count < len(m.text) && (n.max < 0 || count < n.max) {
		if !m.check() {
			return false
		}
		r := m.text[s.pos+count]
		if atom.kind == 'p' {
			if !atom.pred(r) {
				break
			}
		} else if !equalRune(r, atom.literal[0], atom.flags) {
			break
		}
		count++
	}
	if count < n.min {
		return false
	}
	if n.mode == '+' {
		s.pos += count
		return next(s)
	}
	if n.mode == '?' {
		for i := n.min; i <= count; i++ {
			if !m.check() {
				return false
			}
			v := s
			v.pos += i
			if next(v) {
				return true
			}
		}
		return false
	}
	for i := count; i >= n.min; i-- {
		if !m.check() {
			return false
		}
		v := s
		v.pos += i
		if next(v) {
			return true
		}
	}
	return false
}
