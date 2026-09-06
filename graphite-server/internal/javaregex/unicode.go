package javaregex

import (
	_ "embed"
	"encoding/json"
	"sort"
	"strings"
	"sync"
)

//go:embed java17_unicode.json
var unicodeJSON []byte
var unicodeOnce sync.Once
var javaUnicode struct {
	Ranges                      map[string][]rune `json:"ranges"`
	Lower, Upper                map[rune]rune
	ScriptAliases, BlockAliases map[string]string
}
var caseEquivalents map[rune][]rune
var blocks map[string]string

func unicodeData() {
	unicodeOnce.Do(func() {
		if err := json.Unmarshal(unicodeJSON, &javaUnicode); err != nil {
			panic(err)
		}
		caseEquivalents = map[rune][]rune{}
		seen := map[rune]bool{}
		for r := range javaUnicode.Lower {
			seen[r] = true
		}
		for r := range javaUnicode.Upper {
			seen[r] = true
		}
		for r := range seen {
			f := foldRune(r)
			caseEquivalents[f] = append(caseEquivalents[f], r)
		}
		blocks = map[string]string{}
		for name := range javaUnicode.Ranges {
			if strings.HasPrefix(name, "blk:") {
				blocks[strings.ToUpper(name[4:])] = name
			}
		}
		for alias, block := range javaUnicode.BlockAliases {
			blocks[alias] = "blk:" + block
		}
	})
}
func foldRune(r rune) rune {
	if v, ok := javaUnicode.Upper[r]; ok {
		r = v
	}
	if v, ok := javaUnicode.Lower[r]; ok {
		r = v
	}
	return r
}
func equalRune(a, b rune, f flags) bool {
	if a == b {
		return true
	}
	if f&flagI == 0 {
		return false
	}
	if f&flagUCase == 0 {
		if a >= 'A' && a <= 'Z' {
			a += 32
		}
		if b >= 'A' && b <= 'Z' {
			b += 32
		}
		return a == b
	}
	unicodeData()
	return foldRune(a) == foldRune(b)
}
func foldPredicate(p predicate, f flags) predicate {
	if f&flagI == 0 {
		return p
	}
	if f&flagUCase == 0 {
		return func(r rune) bool {
			if p(r) {
				return true
			}
			if r >= 'a' && r <= 'z' {
				return p(r - 32)
			}
			if r >= 'A' && r <= 'Z' {
				return p(r + 32)
			}
			return false
		}
	}
	unicodeData()
	return func(r rune) bool {
		if p(r) {
			return true
		}
		canonical := foldRune(r)
		if p(canonical) {
			return true
		}
		for _, v := range caseEquivalents[canonical] {
			if p(v) {
				return true
			}
		}
		return false
	}
}
func inRanges(r rune, ranges []rune) bool {
	i := sort.Search(len(ranges)/2, func(i int) bool { return ranges[2*i+1] >= r })
	return i < len(ranges)/2 && ranges[2*i] <= r
}
func table(name string) predicate {
	unicodeData()
	ranges := javaUnicode.Ranges[name]
	return func(r rune) bool { return inRanges(r, ranges) }
}
func unicodeSpace(r rune) bool {
	return r >= 9 && r <= 13 || r == 0x85 || r == 0x20 || r == 0xa0 || r == 0x1680 || r >= 0x2000 && r <= 0x200a || r == 0x2028 || r == 0x2029 || r == 0x202f || r == 0x205f || r == 0x3000
}
func horizontal(r rune) bool {
	return r == '\t' || r == 0x20 || r == 0xa0 || r == 0x1680 || r == 0x180e || r >= 0x2000 && r <= 0x200a || r == 0x202f || r == 0x205f || r == 0x3000
}
func unicodeWord(r rune) bool {
	return table("Alphabetic")(r) || category("M")(r) || table("Nd")(r) || table("Pc")(r) || r == 0x200c || r == 0x200d
}
func predefined(code rune, f flags) predicate {
	inverse := code >= 'A' && code <= 'Z'
	if inverse {
		code += 32
	}
	var p predicate
	switch code {
	case 'd':
		if f&flagUClass != 0 {
			p = table("Nd")
		} else {
			p = func(r rune) bool { return r >= '0' && r <= '9' }
		}
	case 's':
		if f&flagUClass != 0 {
			p = unicodeSpace
		} else {
			p = func(r rune) bool { return r == ' ' || r >= 9 && r <= 13 }
		}
	case 'w':
		if f&flagUClass != 0 {
			p = unicodeWord
		} else {
			p = func(r rune) bool { return asciiLetter(r) || r >= '0' && r <= '9' || r == '_' }
		}
	case 'h':
		p = horizontal
	case 'v':
		p = func(r rune) bool { return r >= 10 && r <= 13 || r == 0x85 || r == 0x2028 || r == 0x2029 }
	}
	if inverse {
		return negate(p)
	}
	return p
}
func boundaryWord(text []rune, pos int, f flags) bool {
	if f&flagUClass != 0 {
		return unicodeWord(text[pos])
	}
	r := text[pos]
	if r == '_' || table("javaLetterOrDigit")(r) {
		return true
	}
	if table("Mn")(r) {
		for pos--; pos >= 0; pos-- {
			r = text[pos]
			if table("javaLetterOrDigit")(r) {
				return true
			}
			if !table("Mn")(r) {
				break
			}
		}
	}
	return false
}
func category(name string) predicate {
	unicodeData()
	switch name {
	case "LC":
		return union(union(table("Lu"), table("Ll")), table("Lt"))
	case "LD":
		return union(category("L"), table("Nd"))
	case "L1":
		return func(r rune) bool { return r >= 0 && r <= 255 }
	case "all":
		return func(r rune) bool { return true }
	}
	if r, ok := javaUnicode.Ranges[name]; ok {
		return func(v rune) bool { return inRanges(v, r) }
	}
	if len(name) == 1 && strings.Contains("LMNZCPS", name) {
		var p predicate = func(r rune) bool { return false }
		for key, v := range javaUnicode.Ranges {
			if len(key) == 2 && strings.HasPrefix(key, name) {
				ranges := v
				p = union(p, func(r rune) bool { return inRanges(r, ranges) })
			}
		}
		return p
	}
	return nil
}
func normalizeProperty(name string) string { return strings.ToUpper(name) }
func property(name string, f flags) (predicate, bool) {
	unicodeData()
	var p predicate
	if strings.HasPrefix(name, "In") {
		if key, ok := blocks[normalizeProperty(name[2:])]; ok {
			p = table(key)
		}
	}
	if strings.Contains(name, "=") {
		parts := strings.SplitN(name, "=", 2)
		switch parts[0] {
		case "sc", "script":
			p = script(parts[1])
		case "blk", "block":
			if key, ok := blocks[normalizeProperty(parts[1])]; ok {
				p = table(key)
			}
		case "gc", "general_category":
			p = category(parts[1])
		}
	} else if strings.HasPrefix(name, "Is") {
		n := name[2:]
		p = unicodeProperty(n)
		if p == nil {
			p = script(n)
		}
		if p == nil {
			p = category(n)
		}
	} else if strings.HasPrefix(name, "java") {
		if name == "javaAlphabetic" {
			p = table("Alphabetic")
		} else if name == "javaIdeographic" {
			p = table("Ideographic")
		} else if _, ok := javaUnicode.Ranges[name]; ok {
			p = table(name)
		}
	} else if p == nil {
		p = category(name)
		if p == nil {
			p = posix(name, f&flagUClass != 0)
		}
	}
	if p == nil {
		return nil, false
	}
	return propertyCase(p, name, f), true
}
func script(name string) predicate {
	key, ok := javaUnicode.ScriptAliases[strings.ToUpper(name)]
	if !ok {
		return nil
	}
	return table("sc:" + key)
}
func unicodeProperty(name string) predicate {
	switch strings.ToUpper(name) {
	case "ALPHABETIC", "ALPHA":
		return table("Alphabetic")
	case "IDEOGRAPHIC":
		return table("Ideographic")
	case "LETTER":
		return table("javaLetter")
	case "LOWERCASE", "LOWER":
		return table("javaLowerCase")
	case "UPPERCASE", "UPPER":
		return table("javaUpperCase")
	case "TITLECASE":
		return table("javaTitleCase")
	case "WHITE_SPACE", "WHITESPACE", "SPACE":
		return unicodeSpace
	case "CONTROL", "CNTRL":
		return table("Cc")
	case "PUNCTUATION", "PUNCT":
		return category("P")
	case "HEX_DIGIT", "HEXDIGIT", "XDIGIT":
		return func(r rune) bool {
			return table("Nd")(r) || r >= 'a' && r <= 'f' || r >= 'A' && r <= 'F' || r >= 0xff21 && r <= 0xff26 || r >= 0xff41 && r <= 0xff46
		}
	case "ASSIGNED":
		return negate(table("Cn"))
	case "NONCHARACTER_CODE_POINT", "NONCHARACTERCODEPOINT":
		return func(r rune) bool { return r >= 0xfdd0 && r <= 0xfdef || r&0xfffe == 0xfffe }
	case "DIGIT":
		return table("Nd")
	case "ALNUM":
		return union(table("Alphabetic"), table("Nd"))
	case "BLANK":
		return func(r rune) bool { return r == '\t' || table("Zs")(r) }
	case "GRAPH":
		return func(r rune) bool { return !unicodeSpace(r) && !table("Cc")(r) && !table("Cs")(r) && !table("Cn")(r) }
	case "PRINT":
		return func(r rune) bool {
			return (!unicodeSpace(r) && !table("Cc")(r) && !table("Cs")(r) && !table("Cn")(r) || r == '\t' || table("Zs")(r)) && !table("Cc")(r)
		}
	case "JOIN_CONTROL", "JOINCONTROL":
		return func(r rune) bool { return r == 0x200c || r == 0x200d }
	case "WORD":
		return unicodeWord
	}
	return nil
}
func posix(name string, uni bool) predicate {
	if uni {
		switch name {
		case "Lower":
			return table("javaLowerCase")
		case "Upper":
			return table("javaUpperCase")
		case "Alpha":
			return table("Alphabetic")
		case "Digit":
			return table("Nd")
		case "Alnum":
			return union(table("Alphabetic"), table("Nd"))
		case "Space":
			return unicodeSpace
		case "Punct":
			return category("P")
		case "Cntrl":
			return table("Cc")
		case "XDigit":
			return unicodeProperty("Hex_Digit")
		case "Blank", "Graph", "Print":
			return unicodeProperty(name)
		}
	}
	switch name {
	case "Lower":
		return func(r rune) bool { return r >= 'a' && r <= 'z' }
	case "Upper":
		return func(r rune) bool { return r >= 'A' && r <= 'Z' }
	case "ASCII":
		return func(r rune) bool { return r >= 0 && r < 128 }
	case "Alpha":
		return asciiLetter
	case "Digit":
		return func(r rune) bool { return r >= '0' && r <= '9' }
	case "Alnum":
		return func(r rune) bool { return asciiLetter(r) || r >= '0' && r <= '9' }
	case "Punct":
		return func(r rune) bool { return r >= 33 && r <= 126 && !asciiLetter(r) && !(r >= '0' && r <= '9') }
	case "Graph":
		return func(r rune) bool { return r >= 33 && r <= 126 }
	case "Print":
		return func(r rune) bool { return r >= 32 && r <= 126 }
	case "Blank":
		return func(r rune) bool { return r == ' ' || r == '\t' }
	case "Cntrl":
		return func(r rune) bool { return r >= 0 && r < 32 || r == 127 }
	case "XDigit":
		return func(r rune) bool { return hex(r) >= 0 }
	case "Space":
		return predefined('s', 0)
	}
	return nil
}

func propertyCase(p predicate, name string, f flags) predicate {
	if f&flagI == 0 {
		return p
	}
	general := name
	if strings.HasPrefix(general, "Is") {
		general = general[2:]
	}
	if strings.Contains(general, "=") {
		parts := strings.SplitN(general, "=", 2)
		if parts[0] == "gc" || parts[0] == "general_category" {
			general = parts[1]
		}
	}
	if general == "Lu" || general == "Ll" || general == "Lt" {
		return category("LC")
	}
	if name == "Lower" || name == "Upper" {
		if f&flagUClass == 0 {
			return asciiLetter
		}
		return union(union(table("javaLowerCase"), table("javaUpperCase")), table("javaTitleCase"))
	}
	if name == "javaLowerCase" || name == "javaUpperCase" || name == "javaTitleCase" {
		return union(union(table("javaLowerCase"), table("javaUpperCase")), table("javaTitleCase"))
	}
	if strings.HasPrefix(name, "Is") {
		switch strings.ToUpper(name[2:]) {
		case "LOWERCASE", "UPPERCASE", "TITLECASE", "LOWER", "UPPER":
			return union(union(table("javaLowerCase"), table("javaUpperCase")), table("javaTitleCase"))
		}
	}
	return p
}
