package query

import (
	"slices"
	"strings"
	"unicode/utf8"

	"github.com/johnsonlee/graphite/graphite-server/internal/javastring"
)

// mainMappedContains mirrors main's reusableContains. Only the actual string
// receives the LOWERCASE transform; the expected string remains unchanged.
// Storage owns cancellation and work accounting around this pure matcher.
func mainMappedContains(atom distinctStringAtom, text string) bool {
	if atom.lower {
		text = javastring.Case(text, false, nil)
	}
	if utf8.ValidString(text) && utf8.ValidString(atom.term) {
		return strings.Contains(text, atom.term)
	}
	// Java's indexOf compares UTF-16 units, so an isolated surrogate can match
	// one half of a supplementary character even when the actual is valid UTF-8.
	actual, expected := javastring.UTF16(text), javastring.UTF16(atom.term)
	for start := 0; start+len(expected) <= len(actual); start++ {
		if slices.Equal(actual[start:start+len(expected)], expected) {
			return true
		}
	}
	return false
}
