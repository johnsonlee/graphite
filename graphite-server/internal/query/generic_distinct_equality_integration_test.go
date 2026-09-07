package query

import "testing"

func TestGenericDistinctJavaEquivalentStringEncodings(t *testing.T) {
	a := "\xed\xa0\xbd\xed\xb8\x80"
	b := "😀"
	if genericJavaHash(a) != genericJavaHash(b) {
		t.Fatal("equal Java strings have different hashes")
	}
	if !genericJavaEqual(a, b) {
		t.Fatal("identical Java UTF16 units must compare equal: main String.equals true and compareTo 0")
	}
	rows := newGenericDistinctRows(true)
	rows.add([]any{a}, map[string]any{"x": a})
	if _, ok := rows.find([]any{b}); !ok {
		t.Fatal("DISTINCT retained a duplicate Java string")
	}
}
