package cypher

import (
	"math"
	"testing"
)

func TestJavaFloatingLiteralRange(t *testing.T) {
	for _, test := range []struct {
		source string
		want   float64
	}{{"1e999", math.Inf(1)}, {"1e-999", 0}} {
		value, err := numberValue(token{text: test.source})
		if err != nil {
			t.Fatal(err)
		}
		if value != test.want {
			t.Fatalf("%s got %#v want %v", test.source, value, test.want)
		}
	}
}
func TestJavaIsolatedSurrogateLiteral(t *testing.T) {
	value, err := stringValue(token{text: `'\uD800'`})
	if err != nil {
		t.Fatal(err)
	}
	if value != "\xed\xa0\x80" {
		t.Fatalf("isolated UTF-16 surrogate lost: %x", value)
	}
	pair, err := stringValue(token{text: `'\uD834\uDD1E'`})
	if err != nil {
		t.Fatal(err)
	}
	if pair != "𝄞" {
		t.Fatalf("surrogate pair got %q", pair)
	}
}
