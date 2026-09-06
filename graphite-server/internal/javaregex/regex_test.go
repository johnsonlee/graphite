package javaregex

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"strings"
	"sync"
	"testing"
	"time"
)

type oracleCase struct {
	Name, Pattern, Text, Error, Description string
	Index                                   int
	Matches, KnownGap                       bool
}

func readOracle(t *testing.T) []oracleCase {
	t.Helper()
	data, err := os.ReadFile("testdata/java17-oracle.json")
	if err != nil {
		t.Fatal(err)
	}
	var all struct{ Cases []oracleCase }
	if err = json.Unmarshal(data, &all); err != nil {
		t.Fatal(err)
	}
	return all.Cases
}
func TestJava17Oracle(t *testing.T) {
	for _, c := range readOracle(t) {
		t.Run(c.Name, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			defer cancel()
			got, err := MatchesContext(ctx, c.Pattern, c.Text)
			if c.KnownGap {
				var unsupported *UnsupportedError
				if !errors.As(err, &unsupported) {
					t.Fatalf("known gap must remain explicit: %q got%v err%v", c.Pattern, got, err)
				}
				return
			}
			if c.Error != "" {
				var syntax *SyntaxError
				if !errors.As(err, &syntax) || syntax.Error() != c.Error {
					t.Fatalf("pattern %q: want error %q; got %v", c.Pattern, c.Error, err)
				}
				return
			}
			if err != nil || got != c.Matches {
				t.Fatalf("pattern %q text %q: want %v; got %v err %v", c.Pattern, c.Text, c.Matches, got, err)
			}
		})
	}
}
func TestContextCancellationStopsBacktracking(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	started := make(chan struct{})
	finished := make(chan error, 1)
	go func() {
		close(started)
		_, err := MatchesContext(ctx, "(a+)+$", strings.Repeat("a", 40)+"!")
		finished <- err
	}()
	<-started
	cancel()
	select {
	case err := <-finished:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("expected cancellation, got%v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("backtracking did not exit after cancellation")
	}
}
func TestContextCancellationAlreadyCancelled(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := CompileContext(ctx, "(")
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("cancelled parse returned%v", err)
	}
}
func TestCompiledPatternConcurrent(t *testing.T) {
	p, err := Compile("(?<x>[a-z]+)-\\k<x>")
	if err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	for i := 0; i < 16; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for _, s := range []string{"abc-abc", "abc-def"} {
				got, e := p.MatchesContext(context.Background(), s)
				if e != nil || got != (s == "abc-abc") {
					t.Errorf("%q: %v %v", s, got, e)
				}
			}
		}()
	}
	wg.Wait()
}

// checkpointContext cancels only after many matcher checkpoints have occurred,
// making this an in-flight cancellation test instead of an admission-only test.
type checkpointContext struct {
	context.Context
	calls  int
	cancel context.CancelFunc
	after  int
}

func (c *checkpointContext) Err() error {
	c.calls++
	if c.calls == c.after {
		c.cancel()
	}
	return c.Context.Err()
}
func TestCancellationDuringBacktracking(t *testing.T) {
	p, err := Compile("(a+)+$")
	if err != nil {
		t.Fatal(err)
	}
	base, cancel := context.WithCancel(context.Background())
	defer cancel()
	ctx := &checkpointContext{Context: base, cancel: cancel, after: 80}
	matched, err := p.MatchesContext(ctx, strings.Repeat("a", 32)+"!")
	if matched || !errors.Is(err, context.Canceled) || ctx.calls < ctx.after {
		t.Fatalf("in-flight match=%v err=%v checkpoints=%d", matched, err, ctx.calls)
	}
	// A cancelled execution must not poison the immutable compiled expression.
	matched, err = p.MatchesContext(context.Background(), "aaa")
	if err != nil || !matched {
		t.Fatalf("subsequent execution=%v %v", matched, err)
	}
}
func TestCancellationDuringCompile(t *testing.T) {
	base, cancel := context.WithCancel(context.Background())
	defer cancel()
	ctx := &checkpointContext{Context: base, cancel: cancel, after: 20}
	_, err := CompileContext(ctx, strings.Repeat("(?:a|b)", 10000))
	if !errors.Is(err, context.Canceled) || ctx.calls < ctx.after {
		t.Fatalf("compile err=%v checkpoints=%d", err, ctx.calls)
	}
}
func TestJava17UnicodeVersionAndCanonicalBehavior(t *testing.T) {
	for _, c := range []struct {
		pattern, text string
		want          bool
	}{
		{`\p{Cn}`, "🫠", true}, // Unicode14 addition is unassigned in Java17 Unicode13.
		{`(?c)é`, "e\u0301", false},
		{`(?c)[é]`, "e\u0301", true},
		{`(?i)\p{Lu}`, "é", true},
		{`(?iu)\w`, "K", false},
		{`(?iu)[a-z]`, "K", true},
	} {
		got, err := MatchesContext(context.Background(), c.pattern, c.text)
		if err != nil || got != c.want {
			t.Fatalf("%q %q want%v got%v err%v", c.pattern, c.text, c.want, got, err)
		}
	}
}
