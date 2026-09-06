package cypher

import (
	"strings"
	"unicode/utf16"
	"unicode/utf8"

	"github.com/antlr4-go/antlr/v4"
)

// ANTLR's Go InputStream converts invalid UTF-8 bytes independently. Gson can
// supply a Java string containing an isolated surrogate, encoded internally as
// WTF-8; expose that single code unit to the generated lexer instead.
type javaInput struct {
	*antlr.InputStream
	points []rune
}

func inputRunes(source string) []rune {
	points := []rune{}
	for i := 0; i < len(source); {
		var r rune
		size := 0
		if i+2 < len(source) && source[i] == 0xed && source[i+1] >= 0xa0 && source[i+1] <= 0xbf && source[i+2]&0xc0 == 0x80 {
			r = rune(source[i]&15)<<12 | rune(source[i+1]&63)<<6 | rune(source[i+2]&63)
			size = 3
		} else {
			r, size = utf8.DecodeRuneInString(source[i:])
		}
		i += size
		if len(points) > 0 && points[len(points)-1] >= 0xd800 && points[len(points)-1] <= 0xdbff && r >= 0xdc00 && r <= 0xdfff {
			points[len(points)-1] = utf16.DecodeRune(points[len(points)-1], r)
		} else {
			points = append(points, r)
		}
	}
	return points
}
func inputString(points []rune) string {
	var out strings.Builder
	for _, r := range points {
		if utf16.IsSurrogate(r) {
			out.WriteByte(0xe0 | byte(r>>12))
			out.WriteByte(0x80 | byte(r>>6&63))
			out.WriteByte(0x80 | byte(r&63))
		} else {
			out.WriteRune(r)
		}
	}
	return out.String()
}
func newJavaInput(source string) *javaInput {
	points := inputRunes(source)
	backing := source
	if !utf8.ValidString(source) {
		backing = string(points)
	}
	return &javaInput{InputStream: antlr.NewInputStream(backing), points: points}
}
func (s *javaInput) LA(offset int) int {
	if offset == 0 {
		return 0
	}
	if offset < 0 {
		offset++
	}
	index := s.Index() + offset - 1
	if index < 0 || index >= len(s.points) {
		return antlr.TokenEOF
	}
	return int(s.points[index])
}
func (s *javaInput) GetText(start, stop int) string {
	if start >= len(s.points) {
		return ""
	}
	return inputString(s.points[start:min(stop+1, len(s.points))])
}
func (s *javaInput) GetTextFromInterval(interval antlr.Interval) string {
	return s.GetText(interval.Start, interval.Stop)
}
func (s *javaInput) GetTextFromTokens(start, stop antlr.Token) string {
	if start == nil || stop == nil {
		return ""
	}
	return s.GetText(start.GetStart(), stop.GetStop())
}
