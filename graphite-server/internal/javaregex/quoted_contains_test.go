package javaregex

import (
	"context"
	"encoding/json"
	"errors"
	"math/rand"
	"os"
	"strings"
	"sync"
	"testing"
)

func quotedPattern(t *testing.T, literal string) *Pattern {
	t.Helper()
	p, err := Compile(`.*\Q` + literal + `\E.*`)
	if err != nil {
		t.Fatal(err)
	}
	if p.contains == nil {
		t.Fatalf("expected quoted plan for %q", literal)
	}
	return p
}

func TestQuotedContainsPreservesWholeStringLineRules(t *testing.T) {
	p := quotedPattern(t, "needle")
	for _, c := range []struct {
		text string
		want bool
	}{
		{"needle", true}, {"before needle after", true}, {"needleneedle", true},
		{"", false}, {"needl", false}, {"Needle", false},
		{"needle\n", false}, {"\rneedle", false}, {"before\r\nneedle after", false},
		{"before\tneedle\v\f\x00after", true},
	} {
		got, err := p.MatchesContext(context.Background(), c.text)
		if err != nil || got != c.want {
			t.Fatalf("%q: got %v %v want %v", c.text, got, err, c.want)
		}
	}
	// Default Java dot excludes CR/LF but allows every other ASCII code point.
	for b := 0; b < 128; b++ {
		text := string(byte(b)) + "needle" + string(byte(b))
		got, err := p.MatchesContext(context.Background(), text)
		want := b != '\r' && b != '\n'
		if err != nil || got != want {
			t.Fatalf("ASCII %d: got %v %v want %v", b, got, err, want)
		}
	}
}

func TestQuotedContainsLiteralAndOverlap(t *testing.T) {
	for _, literal := range []string{"a", "ababaca", "aaaaab", `a.*+?|()[]{}^$\z`, "\x00\v\f\t", `trailing\`} {
		p := quotedPattern(t, literal)
		for _, offset := range []int{0, 1, 1023, 1024, 4095, 4096, 8191} {
			text := strings.Repeat("a", offset) + literal + "suffix"
			got, err := p.MatchesContext(context.Background(), text)
			if err != nil || !got {
				t.Fatalf("literal %q offset %d: %v %v", literal, offset, got, err)
			}
		}
	}
	// No literal-size cutoff: a long partial match can back up through many
	// prefixes without quadratic rescanning or shared mutable matcher state.
	literal := strings.Repeat("a", 20000) + "b"
	p := quotedPattern(t, literal)
	for _, ending := range []string{"b", "c"} {
		text := strings.Repeat("a", 40000) + ending
		got, err := p.MatchesContext(context.Background(), text)
		if err != nil || got != (ending == "b") {
			t.Fatalf("long prefix ending %s: %v %v", ending, got, err)
		}
	}
}

func TestQuotedContainsFallsBackWithoutChangingOtherPatterns(t *testing.T) {
	for _, pattern := range []string{
		`.*\Q\E.*`, `(?s).*\Qx\E.*`, `(?i).*\Qx\E.*`, `^.*\Qx\E.*$`,
		`.*\Qx\Ey\Qz\E.*`, `.*\Qx\E.*|other`, `.*\Qé\E.*`, ".*\\Qx\ny\\E.*",
	} {
		p, err := Compile(pattern)
		if err != nil {
			t.Fatal(err)
		}
		if p.contains != nil {
			t.Fatalf("admitted broader pattern %q", pattern)
		}
	}
	p := quotedPattern(t, "x")
	ordinary := *p
	ordinary.contains = nil
	for _, text := range []string{"éx", "😀x", "\u0085x", "x\u2028", "x\u2029", "\xed\xa0\x80x", "x\xed\xb0\x80", "\xffx", "\r\xffx"} {
		_, handled, err := p.contains.matches(context.Background(), text)
		if handled || err != nil {
			t.Fatalf("non-ASCII input handled by ASCII plan: %q %v %v", text, handled, err)
		}
		got, err := p.MatchesContext(context.Background(), text)
		want, originalErr := ordinary.MatchesContext(context.Background(), text)
		if err != nil || originalErr != nil || got != want {
			t.Fatalf("fallback %q: %v %v, original %v %v", text, got, err, want, originalErr)
		}
	}
	for _, source := range []string{`.*\Qx\E.*(`, `.*\Qx\E.*[`, `.*\Qx\E.*\q`} {
		_, err := Compile(source)
		var syntax *SyntaxError
		if !errors.As(err, &syntax) {
			t.Fatalf("invalid expression bypassed parser: %q %v", source, err)
		}
	}
}

func TestQuotedContainsAgreesWithOrdinaryMatcherOnASCIICorpus(t *testing.T) {
	random := rand.New(rand.NewSource(23))
	for i := 0; i < 1000; i++ {
		literal := make([]byte, 1+random.Intn(20))
		for j := range literal {
			literal[j] = byte('a' + random.Intn(4))
		}
		text := make([]byte, random.Intn(80))
		for j := range text {
			text[j] = byte('a' + random.Intn(4))
		}
		if i%3 == 0 {
			text = append(text, literal...)
		}
		if i%5 == 0 {
			text = append(text, '\n')
		}
		p := quotedPattern(t, string(literal))
		ordinary := *p
		ordinary.contains = nil
		got, err := p.MatchesContext(context.Background(), string(text))
		original, originalErr := ordinary.MatchesContext(context.Background(), string(text))
		want := strings.Contains(string(text), string(literal)) && !strings.ContainsAny(string(text), "\r\n")
		if err != nil || originalErr != nil || got != want || original != want {
			t.Fatalf("literal %q text %q: optimized %v %v ordinary %v %v want %v", literal, text, got, err, original, originalErr, want)
		}
	}
}

func TestQuotedContainsCancellationBeforeAndDuringMatch(t *testing.T) {
	p := quotedPattern(t, "needle")
	for _, text := range []string{strings.Repeat("x", 100000), "needle" + strings.Repeat("x", 100000), strings.Repeat("x", 100000) + "needle"} {
		for _, after := range []int{1, 10} {
			base, cancel := context.WithCancel(context.Background())
			ctx := &checkpointContext{Context: base, cancel: cancel, after: after}
			got, err := p.MatchesContext(ctx, text)
			cancel()
			if got || !errors.Is(err, context.Canceled) {
				t.Fatalf("checkpoint %d: %v %v", after, got, err)
			}
		}
	}
	if got, err := p.MatchesContext(context.Background(), "needle"); !got || err != nil {
		t.Fatalf("canceled matcher poisoned pattern: %v %v", got, err)
	}
	// Cancellation must also be polled in a long prefix-fallback chain.
	p = quotedPattern(t, strings.Repeat("a", 20000)+"b")
	base, cancel := context.WithCancel(context.Background())
	defer cancel()
	ctx := &checkpointContext{Context: base, cancel: cancel, after: 25}
	if got, err := p.MatchesContext(ctx, strings.Repeat("a", 20000)+"c"); got || !errors.Is(err, context.Canceled) {
		t.Fatalf("prefix fallback cancellation: %v %v", got, err)
	}
}

func TestQuotedContainsPlanCompilationCancellation(t *testing.T) {
	// Exercise the new compilation work directly after ordinary regex parsing;
	// earlier lexer/parser cancellation is covered by the general regex tests.
	for _, after := range []int{30, 50} {
		pattern := `.*\Q` + strings.Repeat("a", 20000) + `b\E.*`
		base, cancel := context.WithCancel(context.Background())
		ctx := &checkpointContext{Context: base, cancel: cancel, after: after}
		p, err := compileQuotedContains(ctx, pattern)
		cancel()
		if p != nil || !errors.Is(err, context.Canceled) {
			t.Fatalf("prefix plan compilation ignored cancellation: %v %v", p, err)
		}
	}
}

func TestQuotedContainsActualJava17Oracle(t *testing.T) {
	data, err := os.ReadFile("../../../docs/go-server-baseline/native-regex-quoted-contains/pattern-main.json")
	if err != nil {
		t.Fatal(err)
	}
	var oracle struct {
		Cases []struct {
			Name                                string
			PatternUTF16, TextUTF16, ErrorUTF16 []uint16
			CompileSucceeded, Matches           bool
			Spec                                struct{ EligiblePattern, EligibleInput bool }
		}
	}
	if err := json.Unmarshal(data, &oracle); err != nil {
		t.Fatal(err)
	}
	if len(oracle.Cases) != 609 {
		t.Fatalf("incomplete Java oracle: %d", len(oracle.Cases))
	}
	for _, c := range oracle.Cases {
		t.Run(c.Name, func(t *testing.T) {
			pattern, text := wtf8Units(c.PatternUTF16), wtf8Units(c.TextUTF16)
			p, err := Compile(pattern)
			if !c.CompileSucceeded {
				want := wtf8Units(c.ErrorUTF16)
				if err == nil || err.Error() != want {
					t.Fatalf("compile error %v; actual Java %q", err, want)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if (p.contains != nil) != c.Spec.EligiblePattern {
				t.Fatalf("fast plan admission differs for %q", pattern)
			}
			got, err := p.MatchesContext(context.Background(), text)
			if err != nil || got != c.Matches {
				t.Fatalf("matches %v %v; actual Java %v", got, err, c.Matches)
			}
		})
	}
}

func TestQuotedContainsPatternConcurrentMatches(t *testing.T) {
	p := quotedPattern(t, "ababaca")
	var done sync.WaitGroup
	for i := 0; i < 16; i++ {
		done.Add(1)
		go func() {
			defer done.Done()
			for _, c := range []struct {
				text string
				want bool
			}{{"ababababacax", true}, {"ababababac", false}, {"ababaca\n", false}, {"éababaca", true}} {
				got, err := p.MatchesContext(context.Background(), c.text)
				if err != nil || got != c.want {
					t.Errorf("%q: %v %v want %v", c.text, got, err, c.want)
				}
			}
		}()
	}
	done.Wait()
}
