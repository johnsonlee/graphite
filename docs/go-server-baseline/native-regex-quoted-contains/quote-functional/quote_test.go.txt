package javaregex

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"strings"
	"testing"
)

func TestQuotePreprocessingAndDiagnosticsMatchActualJava(t *testing.T) {
	for _, corpus := range []struct {
		path  string
		count int
	}{
		{"../../../docs/go-server-baseline/native-regex-quoted-contains/pattern-main.json", 609},
		{"../../../docs/go-server-baseline/native-regex-quoted-contains/quote-index-controls/pattern-main.json", 77},
	} {
		data, err := os.ReadFile(corpus.path)
		if err != nil {
			t.Fatal(err)
		}
		var oracle struct {
			Cases []struct {
				Name, Description                   string
				Index                               int
				PatternUTF16, TextUTF16, ErrorUTF16 []uint16
				CompileSucceeded, Matches           bool
			}
		}
		if err := json.Unmarshal(data, &oracle); err != nil {
			t.Fatal(err)
		}
		if len(oracle.Cases) != corpus.count {
			t.Fatalf("incomplete Java corpus: %d != %d", len(oracle.Cases), corpus.count)
		}
		for _, c := range oracle.Cases {
			t.Run(c.Name, func(t *testing.T) {
				p, err := Compile(wtf8Units(c.PatternUTF16))
				if !c.CompileSucceeded {
					var syntax *SyntaxError
					if !errors.As(err, &syntax) || syntax.Error() != wtf8Units(c.ErrorUTF16) || syntax.Index != c.Index || syntax.Description != c.Description {
						t.Fatalf("compile diagnostic %v; actual Java index %d description %q message %q", err, c.Index, c.Description, wtf8Units(c.ErrorUTF16))
					}
					return
				}
				if err != nil {
					t.Fatal(err)
				}
				got, err := p.MatchesContext(context.Background(), wtf8Units(c.TextUTF16))
				if err != nil || got != c.Matches {
					t.Fatalf("got %v %v; actual Java %v", got, err, c.Matches)
				}
			})
		}
	}
}

func TestQuotePreprocessingUsesEscapedCodePoints(t *testing.T) {
	for _, c := range []struct{ source, transformed string }{
		{`plain\\Qtext`, `plain\\Qtext`},
		{`\Qabc\E`, `abc`},
		{`\Q1x\E`, `\x31x`},
		{`\Q.$\E`, `\.\$`},
		{`(?x)\Qa b#c\E`, `(?x)a\ b\#c`},
		{`\Q\E*`, `*`},
		{`\Qabc\`, `abc\\`},
		{`\Qone\E\Q2\E`, `one\x32`},
		{`\Q😀\E[`, `😀[`},
	} {
		got, err := removeQEQuoting(context.Background(), []rune(c.source))
		if err != nil || string(got) != c.transformed {
			t.Fatalf("%q transformed to %q %v; want %q", c.source, string(got), err, c.transformed)
		}
	}
}

func TestQuotePreprocessingCancellationInScanAndExpansion(t *testing.T) {
	for _, source := range []string{strings.Repeat(`\\`, 20000), `\Q` + strings.Repeat("!", 20000) + `\E`} {
		base, cancel := context.WithCancel(context.Background())
		ctx := &checkpointContext{Context: base, cancel: cancel, after: 5}
		got, err := removeQEQuoting(ctx, []rune(source))
		cancel()
		if got != nil || !errors.Is(err, context.Canceled) {
			t.Fatalf("preprocessing ignored cancellation: %v", err)
		}
	}
}
