package javastring

import "testing"

func TestROOTCaseUsesExistingMainOracleResults(t *testing.T) {
	// Exact expected strings also occur in query/testdata/functions-jvm-oracle.json.
	for _, c := range []struct {
		input, want string
		upper       bool
	}{
		{"straße ﬃ ı", "STRASSE FFI I", true},
		{"ΟΣ İ ΣΟΣ", "ος i̇ σος", false},
		{"ΟΣ. ΣΟΣ! AΣ1 AΣ_A AΣ́", "ος. σος! aς1 aσ_a aς́", false},
		{"ﬄ ß ŉ ǰ", "FFL SS ʼN J̌", true},
		{"A\xed\xa0\x80Z", "a\xed\xa0\x80z", false},
	} {
		if got := Case(c.input, c.upper, nil); got != c.want {
			t.Errorf("Case(%q,%v)=%q, want %q", c.input, c.upper, got, c.want)
		}
	}
	if Lower("İ") != "i̇" || Upper("ß") != "SS" {
		t.Fatal("ROOT convenience wrappers changed expansion mappings")
	}
}

func TestCasePreservesCancellationCallback(t *testing.T) {
	for _, text := range []string{"ABCDEF", "ΟΣ ΟΣ ΟΣ"} {
		t.Run(text, func(t *testing.T) {
			calls := 0
			defer func() {
				if got := recover(); got != "cancel" || calls != 3 {
					t.Errorf("got panic %v after %d calls", got, calls)
				}
			}()
			Case(text, false, func() {
				calls++
				if calls == 3 {
					panic("cancel")
				}
			})
			t.Fatal("cancellation did not leave casing")
		})
	}
}

func TestJavaWhitespaceAndDecimalDigits(t *testing.T) {
	for _, r := range []rune{' ', '\t', 0x1c, 0xa0, 0x2007, 0x202f} {
		if !Whitespace(r) {
			t.Errorf("whitespace missing U+%04X", r)
		}
	}
	for _, r := range []rune{0x85, 0x200b, 'A', 0xd800} {
		if Whitespace(r) {
			t.Errorf("unexpected whitespace U+%04X", r)
		}
	}
	for _, c := range []struct {
		r     rune
		value int
	}{{'5', 5}, {'９', 9}, {'٣', 3}, {0x1d7d9, 1}} {
		if value, ok := Digit(c.r); !ok || value != c.value {
			t.Errorf("Digit(%U)=%d,%v", c.r, value, ok)
		}
	}
	for _, r := range []rune{'A', '²', 0xd800} {
		if _, ok := Digit(r); ok {
			t.Errorf("unexpected digit %U", r)
		}
	}
}
