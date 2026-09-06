package javaregex

import (
	"context"
	"encoding/json"
	"os"
	"testing"
	"unicode/utf8"
)

func wtf8Units(units []uint16) string {
	var b []byte
	for _, unit := range units {
		r := rune(unit)
		if r >= 0xD800 && r <= 0xDFFF {
			b = append(b, byte(0xE0|r>>12), byte(0x80|r>>6&63), byte(0x80|r&63))
		} else {
			b = utf8.AppendRune(b, r)
		}
	}
	return string(b)
}
func TestJava17WTF8Oracle(t *testing.T) {
	data, err := os.ReadFile("testdata/java17-wtf8-oracle.json")
	if err != nil {
		t.Fatal(err)
	}
	var oracle struct {
		Cases []struct {
			Name                                string
			PatternUTF16, TextUTF16, ErrorUTF16 []uint16
			Matches                             bool
		}
	}
	if err = json.Unmarshal(data, &oracle); err != nil {
		t.Fatal(err)
	}
	for _, c := range oracle.Cases {
		t.Run(c.Name, func(t *testing.T) {
			got, err := MatchesContext(context.Background(), wtf8Units(c.PatternUTF16), wtf8Units(c.TextUTF16))
			if len(c.ErrorUTF16) > 0 {
				want := wtf8Units(c.ErrorUTF16)
				if err == nil || err.Error() != want {
					t.Fatalf("error want%q got%v", want, err)
				}
				return
			}
			if err != nil || got != c.Matches {
				t.Fatalf("units pattern%v text%v want%v got%v err%v", c.PatternUTF16, c.TextUTF16, c.Matches, got, err)
			}
		})
	}
}
