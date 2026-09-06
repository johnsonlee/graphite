package javastring

import (
	"strings"
	"unicode/utf16"
	"unicode/utf8"
)

// Java strings may retain isolated UTF-16 surrogates. Internally these are
// preserved in WTF-8; only the final UTF-8 wire conversion replaces them with
// '?', matching the JVM UTF-8 encoder used by the source server.
func UTF16(s string) []uint16 {
	out := []uint16{}
	for i := 0; i < len(s); {
		if i+2 < len(s) && s[i] == 0xed && s[i+1] >= 0xa0 && s[i+1] <= 0xbf && s[i+2]&0xc0 == 0x80 {
			out = append(out, uint16(s[i]&15)<<12|uint16(s[i+1]&63)<<6|uint16(s[i+2]&63))
			i += 3
			continue
		}
		r, n := utf8.DecodeRuneInString(s[i:])
		i += n
		if r > 0xffff {
			a, b := utf16.EncodeRune(r)
			out = append(out, uint16(a), uint16(b))
		} else {
			out = append(out, uint16(r))
		}
	}
	return out
}
func FromUTF16(units []uint16) string {
	var out strings.Builder
	for i := 0; i < len(units); i++ {
		u := units[i]
		if u >= 0xd800 && u <= 0xdbff && i+1 < len(units) && units[i+1] >= 0xdc00 && units[i+1] <= 0xdfff {
			out.WriteRune(utf16.DecodeRune(rune(u), rune(units[i+1])))
			i++
			continue
		}
		if utf16.IsSurrogate(rune(u)) {
			out.WriteByte(0xe0 | byte(u>>12))
			out.WriteByte(0x80 | byte(u>>6&63))
			out.WriteByte(0x80 | byte(u&63))
		} else {
			out.WriteRune(rune(u))
		}
	}
	return out.String()
}
func WireString(s string) string {
	if utf8.ValidString(s) {
		return s
	}
	units := UTF16(s)
	for i, u := range units {
		if u >= 0xd800 && u <= 0xdbff && i+1 < len(units) && units[i+1] >= 0xdc00 && units[i+1] <= 0xdfff {
			continue
		}
		if u >= 0xdc00 && u <= 0xdfff && i > 0 && units[i-1] >= 0xd800 && units[i-1] <= 0xdbff {
			continue
		}
		if utf16.IsSurrogate(rune(u)) {
			units[i] = '?'
		}
	}
	return string(utf16.Decode(units))
}
func CodePoints(s string) []rune {
	units := UTF16(s)
	result := []rune{}
	for i := 0; i < len(units); i++ {
		u := units[i]
		if u >= 0xd800 && u <= 0xdbff && i+1 < len(units) && units[i+1] >= 0xdc00 && units[i+1] <= 0xdfff {
			result = append(result, utf16.DecodeRune(rune(u), rune(units[i+1])))
			i++
		} else {
			result = append(result, rune(u))
		}
	}
	return result
}
