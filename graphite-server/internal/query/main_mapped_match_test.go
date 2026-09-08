package query

import "testing"

func TestMainMappedContains(t *testing.T) {
	// Fixed semantic expectations for main's reusableContains/String.indexOf;
	// do not derive expected results through the implementation's case helpers.
	for _, tc := range []struct {
		name, actual, expected string
		lower                  bool
		want                   bool
	}{
		{"raw exact case", "AbCd", "bC", false, true},
		{"raw different case", "AbCd", "bc", false, false},
		{"ASCII lower actual", "AbCd", "bc", true, true},
		{"ASCII expected unchanged", "AbCd", "BC", true, false},
		{"empty expected", "abc", "", false, true},
		{"empty actual", "", "a", true, false},
		{"both empty", "", "", true, true},
		{"dotted I expansion", "XİY", "i\u0307", true, true},
		{"dotted I preserves combining dot", "İ", "i", true, true},
		{"dotted I expected unchanged", "İ", "İ", true, false},
		{"ROOT I is not dotless", "I", "ı", true, false},
		{"Greek final sigma", "ΟΣ", "ος", true, true},
		{"Greek final sigma differs from medial", "ΟΣ", "οσ", true, false},
		{"Greek medial sigma", "ΟΣΑ", "οσα", true, true},
		{"Greek lower is not case folding", "ος", "οσ", true, false},
		{"supplementary full character", "a😀b", "😀", false, true},
		{"supplementary high surrogate", "a😀b", "\xed\xa0\xbd", false, true},
		{"supplementary low surrogate", "a😀b", "\xed\xb8\x80", false, true},
		{"supplementary wrong low surrogate", "a😀b", "\xed\xb8\x81", false, false},
		{"match starts inside surrogate pair", "😀b", "\xed\xb8\x80b", false, true},
		{"WTF8 pair equals supplementary", "\xed\xa0\xbd\xed\xb8\x80", "😀", false, true},
		{"expected WTF8 pair equals supplementary", "😀", "\xed\xa0\xbd\xed\xb8\x80", false, true},
		{"lone surrogate preserved through lower", "A\xed\xa0\x80Z", "a\xed\xa0\x80z", true, true},
		{"lone surrogate is not replacement", "\xed\xa0\x80", "�", false, false},
		{"WTF8 empty expected", "\xed\xa0\x80", "", false, true},
		{"supplementary ROOT lowercase", "A𐐀Z", "𐐨", true, true},
		{"supplementary expected unchanged", "A𐐀Z", "𐐀", true, false},
		{"lowered supplementary low surrogate", "𐐀", "\xed\xb0\xa8", true, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			atom := distinctStringAtom{property: "caller_name", op: "CONTAINS", term: tc.expected, lower: tc.lower}
			if got := mainMappedContains(atom, tc.actual); got != tc.want {
				t.Fatalf("actual %q, expected %q, lower %t: got %t, want %t", tc.actual, tc.expected, tc.lower, got, tc.want)
			}
		})
	}
}
