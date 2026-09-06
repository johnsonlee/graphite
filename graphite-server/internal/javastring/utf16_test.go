package javastring

import (
	"reflect"
	"testing"
)

func TestUTF16RetainsIsolatedSurrogatesAndCombinesPairs(t *testing.T) {
	cases := []struct {
		text   string
		units  []uint16
		points []rune
		wire   string
	}{
		{"", []uint16{}, []rune{}, ""},
		{"A😀Z", []uint16{'A', 0xd83d, 0xde00, 'Z'}, []rune{'A', 0x1f600, 'Z'}, "A😀Z"},
		{"\xed\xa0\x80", []uint16{0xd800}, []rune{0xd800}, "?"},
		{"\xed\xb0\x80", []uint16{0xdc00}, []rune{0xdc00}, "?"},
		{"\xed\xa0\xbd\xed\xb8\x80", []uint16{0xd83d, 0xde00}, []rune{0x1f600}, "😀"},
		{"\xed\xa0\x80X\xed\xb0\x80�", []uint16{0xd800, 'X', 0xdc00, 0xfffd}, []rune{0xd800, 'X', 0xdc00, 0xfffd}, "?X?�"},
	}
	for _, c := range cases {
		if got := UTF16(c.text); !reflect.DeepEqual(got, c.units) {
			t.Errorf("UTF16(%q)=%x, want %x", c.text, got, c.units)
		}
		if got := UTF16(FromUTF16(c.units)); !reflect.DeepEqual(got, c.units) {
			t.Errorf("round trip %x became %x", c.units, got)
		}
		if got := CodePoints(c.text); !reflect.DeepEqual(got, c.points) {
			t.Errorf("CodePoints(%q)=%x, want %x", c.text, got, c.points)
		}
		if got := WireString(c.text); got != c.wire {
			t.Errorf("WireString(%q)=%q, want %q", c.text, got, c.wire)
		}
	}
}
