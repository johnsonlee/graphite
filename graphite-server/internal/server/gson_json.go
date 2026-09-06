package server

// A recursive JSON reader implementing the Gson JsonParser.parseString contract:
// one lenient value, followed by a legacy-strict end-of-document check. This is
// a parser, not a sequence of textual rewrites (which would corrupt strings).
import (
	"fmt"
	"strings"
	"unicode/utf16"
	"unicode/utf8"
)

type gsonMember struct {
	name  string
	value *gsonValue
}
type gsonValue struct {
	kind    byte
	text    string
	members []gsonMember
	items   []*gsonValue
}
type gsonReader struct {
	input                []uint16
	pos, line, lineStart int
	path                 string
}

const gsonTroubleshooting = "\nSee https://github.com/google/gson/blob/main/Troubleshooting.md#malformed-json"

func (p *gsonReader) location() string {
	return fmt.Sprintf(" at line %d column %d path %s", p.line+1, p.pos-p.lineStart+1, p.path)
}
func (p *gsonReader) malformed(message string) error {
	return fmt.Errorf("com.google.gson.stream.MalformedJsonException: %s%s%s", message, p.location(), gsonTroubleshooting)
}
func (p *gsonReader) eof() error {
	return fmt.Errorf("java.io.EOFException: End of input%s", p.location())
}
func (p *gsonReader) advance() uint16 {
	c := p.input[p.pos]
	p.pos++
	if c == '\n' {
		p.line++
		p.lineStart = p.pos
	}
	return c
}
func (p *gsonReader) whitespace(lenient bool) (bool, error) {
	for p.pos < len(p.input) {
		c := p.input[p.pos]
		if c == ' ' || c == '\r' || c == '\t' || c == '\n' {
			p.advance()
			continue
		}
		if c == '/' && p.pos+1 < len(p.input) && (p.input[p.pos+1] == '/' || p.input[p.pos+1] == '*') {
			if !lenient {
				p.advance()
				return false, p.malformed("Use JsonReader.setStrictness(Strictness.LENIENT) to accept malformed JSON")
			}
			p.advance()
			style := p.advance()
			if style == '/' {
				for p.pos < len(p.input) && p.input[p.pos] != '\n' && p.input[p.pos] != '\r' {
					p.advance()
				}
				continue
			}
			for p.pos+1 < len(p.input) && !(p.input[p.pos] == '*' && p.input[p.pos+1] == '/') {
				p.advance()
			}
			if p.pos+1 >= len(p.input) {
				return false, p.malformed("Unterminated comment")
			}
			p.advance()
			p.advance()
			continue
		}
		if c == '#' {
			if !lenient {
				p.advance()
				return false, p.malformed("Use JsonReader.setStrictness(Strictness.LENIENT) to accept malformed JSON")
			}
			for p.pos < len(p.input) && p.input[p.pos] != '\n' && p.input[p.pos] != '\r' {
				p.advance()
			}
			continue
		}
		return true, nil
	}
	return false, nil
}
func parseGsonJSON(body []byte) (*gsonValue, error) {
	// HTTP body decoding is UTF-8. Escaped UTF-16 units inside quoted JSON are
	// decoded separately and retained as WTF-8 for the native Java String layer.
	units := gsonUTF8Units(body)
	p := &gsonReader{input: units, path: "$"}
	if p.pos < len(units) && units[p.pos] == 0xFEFF {
		p.pos++
		p.lineStart++
	}
	ok, err := p.whitespace(true)
	if err != nil {
		return nil, err
	}
	if !ok {
		return &gsonValue{kind: 'n', text: "null"}, nil
	}
	if p.pos+5 <= len(units) && stringUnits(units[p.pos:p.pos+5]) == ")]}'\n" {
		for i := 0; i < 5; i++ {
			p.advance()
		}
		ok, err = p.whitespace(true)
		if err != nil {
			return nil, err
		}
		if !ok {
			return &gsonValue{kind: 'n', text: "null"}, nil
		}
	}
	v, err := p.value()
	if err != nil {
		return nil, err
	}
	if v.kind != 'n' {
		p.path = "$"
		ok, err = p.whitespace(false)
		if err != nil {
			return nil, err
		}
		if ok {
			p.advance()
			return nil, p.malformed("Use JsonReader.setStrictness(Strictness.LENIENT) to accept malformed JSON")
		}
	}
	return v, nil
}
func (p *gsonReader) value() (*gsonValue, error) {
	ok, err := p.whitespace(true)
	if err != nil {
		return nil, err
	}
	if !ok {
		return nil, p.eof()
	}
	switch p.input[p.pos] {
	case '{':
		return p.object()
	case '[':
		return p.array()
	case '\'', '"':
		text, err := p.quoted()
		return &gsonValue{kind: 's', text: text}, err
	}
	start := p.pos
	for p.pos < len(p.input) && !gsonDelimiter(p.input[p.pos]) {
		p.advance()
	}
	if p.pos == start {
		return nil, p.malformed("Expected value")
	}
	token := stringUnits(p.input[start:p.pos])
	switch strings.ToLower(token) {
	case "null":
		return &gsonValue{kind: 'n', text: "null"}, nil
	case "true":
		return &gsonValue{kind: 'b', text: "true"}, nil
	case "false":
		return &gsonValue{kind: 'b', text: "false"}, nil
	}
	if validJSONNumber(token) {
		return &gsonValue{kind: 'd', text: token}, nil
	}
	return &gsonValue{kind: 's', text: token}, nil
}
func gsonDelimiter(c uint16) bool {
	switch c {
	case ' ', '\t', '\r', '\n', '\f', ',', ':', '[', ']', '{', '}', '#', '/', '\\', ';', '=':
		return true
	}
	return false
}
func validJSONNumber(s string) bool {
	if s == "" {
		return false
	}
	i := 0
	if s[i] == '-' {
		i++
		if i == len(s) {
			return false
		}
	}
	if s[i] == '0' {
		i++
	} else {
		if s[i] < '1' || s[i] > '9' {
			return false
		}
		for i < len(s) && s[i] >= '0' && s[i] <= '9' {
			i++
		}
	}
	if i < len(s) && s[i] == '.' {
		i++
		start := i
		for i < len(s) && s[i] >= '0' && s[i] <= '9' {
			i++
		}
		if i == start {
			return false
		}
	}
	if i < len(s) && (s[i] == 'e' || s[i] == 'E') {
		i++
		if i < len(s) && (s[i] == '+' || s[i] == '-') {
			i++
		}
		start := i
		for i < len(s) && s[i] >= '0' && s[i] <= '9' {
			i++
		}
		if i == start {
			return false
		}
	}
	return i == len(s)
}
func (p *gsonReader) object() (*gsonValue, error) {
	p.advance()
	v := &gsonValue{kind: 'o'}
	base := p.path
	p.path = base + "."
	first := true
	for {
		ok, err := p.whitespace(true)
		if err != nil {
			return nil, err
		}
		if !ok {
			return nil, p.eof()
		}
		if first && p.input[p.pos] == '}' {
			p.advance()
			p.path = base
			return v, nil
		}
		var name string
		if p.input[p.pos] == '"' || p.input[p.pos] == '\'' {
			name, err = p.quoted()
			if err != nil {
				return nil, err
			}
		} else {
			start := p.pos
			for p.pos < len(p.input) && !gsonDelimiter(p.input[p.pos]) {
				p.advance()
			}
			if p.pos == start {
				if p.input[p.pos] == '}' {
					p.advance()
				}
				return nil, p.malformed("Expected name")
			}
			name = stringUnits(p.input[start:p.pos])
		}
		p.path = base + "." + name
		ok, err = p.whitespace(true)
		if err != nil {
			return nil, err
		}
		if !ok {
			return nil, p.eof()
		}
		switch p.advance() {
		case ':':
		case '=':
			if p.pos < len(p.input) && p.input[p.pos] == '>' {
				p.advance()
			}
		default:
			return nil, p.malformed("Expected ':'")
		}
		child, err := p.value()
		if err != nil {
			return nil, err
		}
		replaced := false
		for i := range v.members {
			if v.members[i].name == name {
				v.members[i].value = child
				replaced = true
				break
			}
		}
		if !replaced {
			v.members = append(v.members, gsonMember{name, child})
		}
		p.path = base + "." + name
		ok, err = p.whitespace(true)
		if err != nil {
			return nil, err
		}
		if !ok {
			return nil, p.eof()
		}
		switch p.advance() {
		case '}':
			p.path = base
			return v, nil
		case ',', ';':
			first = false
		default:
			return nil, p.malformed("Unterminated object")
		}
	}
}
func (p *gsonReader) array() (*gsonValue, error) {
	p.advance()
	v := &gsonValue{kind: 'a'}
	base := p.path
	first := true
	for {
		p.path = fmt.Sprintf("%s[%d]", base, len(v.items))
		ok, err := p.whitespace(true)
		if err != nil {
			return nil, err
		}
		if !ok {
			return nil, p.eof()
		}
		if first && p.input[p.pos] == ']' {
			p.advance()
			p.path = base
			return v, nil
		}
		var child *gsonValue
		if p.input[p.pos] == ',' || p.input[p.pos] == ';' || p.input[p.pos] == ']' {
			child = &gsonValue{kind: 'n', text: "null"}
		} else {
			child, err = p.value()
			if err != nil {
				return nil, err
			}
		}
		v.items = append(v.items, child)
		p.path = fmt.Sprintf("%s[%d]", base, len(v.items))
		ok, err = p.whitespace(true)
		if err != nil {
			return nil, err
		}
		if !ok {
			return nil, p.eof()
		}
		switch p.advance() {
		case ']':
			p.path = base
			return v, nil
		case ',', ';':
			first = false
		default:
			return nil, p.malformed("Unterminated array")
		}
	}
}
func (p *gsonReader) quoted() (string, error) {
	quote := p.advance()
	var units []uint16
	for p.pos < len(p.input) {
		c := p.advance()
		if c == quote {
			return stringUnits(units), nil
		}
		if c != '\\' {
			units = append(units, c)
			continue
		}
		if p.pos == len(p.input) {
			return "", p.malformed("Unterminated escape sequence")
		}
		e := p.advance()
		switch e {
		case 'u':
			if p.pos+4 > len(p.input) {
				return "", p.malformed("Unterminated escape sequence")
			}
			raw := p.input[p.pos : p.pos+4]
			value := uint16(0)
			for _, h := range raw {
				digit := gsonHex(h)
				if digit < 0 {
					return "", p.malformed("Malformed Unicode escape \\u" + stringUnits(raw))
				}
				value = value*16 + uint16(digit)
			}
			for i := 0; i < 4; i++ {
				p.advance()
			}
			units = append(units, value)
		case 't':
			units = append(units, '\t')
		case 'b':
			units = append(units, '\b')
		case 'n':
			units = append(units, '\n')
		case 'r':
			units = append(units, '\r')
		case 'f':
			units = append(units, '\f')
		case '\n', '\'', '"', '\\', '/':
			units = append(units, e)
		default:
			return "", p.malformed("Invalid escape sequence")
		}
	}
	return "", p.malformed("Unterminated string")
}
func gsonHex(c uint16) int {
	switch {
	case c >= '0' && c <= '9':
		return int(c - '0')
	case c >= 'a' && c <= 'f':
		return int(c - 'a' + 10)
	case c >= 'A' && c <= 'F':
		return int(c - 'A' + 10)
	}
	return -1
}
func stringUnits(units []uint16) string {
	var b []byte
	for i := 0; i < len(units); i++ {
		r := rune(units[i])
		if r >= 0xD800 && r <= 0xDBFF && i+1 < len(units) && units[i+1] >= 0xDC00 && units[i+1] <= 0xDFFF {
			r = utf16.DecodeRune(r, rune(units[i+1]))
			i++
		}
		if utf16.IsSurrogate(r) {
			b = append(b, byte(0xE0|r>>12), byte(0x80|r>>6&63), byte(0x80|r&63))
		} else {
			b = utf8.AppendRune(b, r)
		}
	}
	return string(b)
}
func (v *gsonValue) canonical() []byte {
	switch v.kind {
	case 's':
		return gsonQuote(v.text)
	case 'n', 'b', 'd':
		return []byte(v.text)
	case 'a':
		out := []byte{'['}
		for i, x := range v.items {
			if i > 0 {
				out = append(out, ',')
			}
			out = append(out, x.canonical()...)
		}
		return append(out, ']')
	case 'o':
		out := []byte{'{'}
		for i, x := range v.members {
			if i > 0 {
				out = append(out, ',')
			}
			out = append(out, gsonQuote(x.name)...)
			out = append(out, ':')
			out = append(out, x.value.canonical()...)
		}
		return append(out, '}')
	}
	return nil
}
func gsonQuote(s string) []byte {
	out := []byte{'"'}
	for i := 0; i < len(s); {
		if i+2 < len(s) && s[i] == 0xED && s[i+1] >= 0xA0 && s[i+1] <= 0xBF && s[i+2]&0xC0 == 0x80 {
			r := uint16(s[i]&15)<<12 | uint16(s[i+1]&63)<<6 | uint16(s[i+2]&63)
			out = append(out, []byte(fmt.Sprintf("\\u%04x", r))...)
			i += 3
			continue
		}
		r, n := utf8.DecodeRuneInString(s[i:])
		i += n
		switch r {
		case '"', '\\':
			out = append(out, '\\', byte(r))
		case '\t':
			out = append(out, '\\', 't')
		case '\n':
			out = append(out, '\\', 'n')
		case '\r':
			out = append(out, '\\', 'r')
		case '\b':
			out = append(out, '\\', 'b')
		case '\f':
			out = append(out, '\\', 'f')
		default:
			if r < 32 || r == 0x2028 || r == 0x2029 {
				out = append(out, []byte(fmt.Sprintf("\\u%04x", r))...)
			} else {
				out = utf8.AppendRune(out, r)
			}
		}
	}
	return append(out, '"')
}

// Java's UTF8 decoder replaces one malformed sequence, including its valid
// continuation prefix, with one U+FFFD. Go range replaces each malformed byte.
func gsonUTF8Units(input []byte) []uint16 {
	out := make([]uint16, 0, len(input))
	for i := 0; i < len(input); {
		first := input[i]
		if first < 128 {
			out = append(out, uint16(first))
			i++
			continue
		}
		need := 0
		switch {
		case first >= 0xC2 && first <= 0xDF:
			need = 2
		case first >= 0xE0 && first <= 0xEF:
			need = 3
		case first >= 0xF0 && first <= 0xF4:
			need = 4
		}
		if need == 0 {
			out = append(out, 0xFFFD)
			i++
			continue
		}
		consumed := 1
		for consumed < need && i+consumed < len(input) {
			b := input[i+consumed]
			if b&0xC0 != 0x80 {
				break
			}
			if consumed == 1 && (first == 0xE0 && b < 0xA0 || first == 0xF0 && b < 0x90 || first == 0xF4 && b > 0x8F) {
				break
			}
			consumed++
		}
		if consumed != need {
			out = append(out, 0xFFFD)
			i += consumed
			continue
		}
		var r rune
		switch need {
		case 2:
			r = rune(first&31)<<6 | rune(input[i+1]&63)
		case 3:
			r = rune(first&15)<<12 | rune(input[i+1]&63)<<6 | rune(input[i+2]&63)
		case 4:
			r = rune(first&7)<<18 | rune(input[i+1]&63)<<12 | rune(input[i+2]&63)<<6 | rune(input[i+3]&63)
		}
		i += need
		if utf16.IsSurrogate(r) {
			out = append(out, 0xFFFD)
		} else if r > 0xFFFF {
			high, low := utf16.EncodeRune(r)
			out = append(out, uint16(high), uint16(low))
		} else {
			out = append(out, uint16(r))
		}
	}
	return out
}
