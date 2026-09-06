package store

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"sync"
	"sync/atomic"
	"testing"
)

func trigramTestStore(t *testing.T, variant string) (*Store, *CallSiteStringIndex) {
	t.Helper()
	root := "../query/testdata/trigram-index"
	dir := filepath.Join(root, "store")
	if variant != "" {
		dst := t.TempDir()
		entries, err := os.ReadDir(dir)
		if err != nil {
			t.Fatal(err)
		}
		for _, entry := range entries {
			raw, err := os.ReadFile(filepath.Join(dir, entry.Name()))
			if err != nil {
				t.Fatal(err)
			}
			if entry.Name() == "graph.callsite-string-index" {
				raw, err = os.ReadFile(filepath.Join(root, variant+".callsite-string-index"))
				if err != nil {
					t.Fatal(err)
				}
			}
			if err = os.WriteFile(filepath.Join(dst, entry.Name()), raw, 0600); err != nil {
				t.Fatal(err)
			}
		}
		dir = dst
	}
	g := openIndexFixture(t, dir, "MAPPED")
	v := requireIndex(t, g)
	if valid, err := g.CertifyCallSiteCandidates(context.Background(), v); err != nil || !valid {
		t.Fatalf("A6 proof %v/%v", valid, err)
	}
	return g, v
}
func TestTrigramCertificateCompleteMissingExtra(t *testing.T) {
	for _, variant := range []string{"", "missing", "extra"} {
		t.Run(variant, func(t *testing.T) {
			g, v := trigramTestStore(t, variant)
			want := variant != "missing"
			valid, err := g.CertifyCallSiteTrigrams(context.Background(), v)
			if err != nil || valid != want {
				t.Fatalf("proof %v/%v want %v", valid, err, want)
			}
			if !g.trigramProof.completed || g.trigramProof.valid != want {
				t.Fatal("valid/invalid proof not cached")
			}
			counter := &certificateCountContext{Context: context.Background()}
			if valid, err = g.CertifyCallSiteTrigrams(counter, v); err != nil || valid != want {
				t.Fatalf("cached %v/%v", valid, err)
			}
			if counter.checks != 2 {
				t.Fatalf("cached lookup unexpectedly repeated cold work: %d checks", counter.checks)
			}
		})
	}
}
func TestTrigramAnchorShortestMissingAndDetached(t *testing.T) {
	g, v := trigramTestStore(t, "")
	if valid, err := g.CertifyCallSiteTrigrams(context.Background(), v); err != nil || !valid {
		t.Fatal(valid, err)
	}
	// Main oracle: aaa and acc collide only in the signature; aaz and ab[ collide in the full hash.
	for _, c := range []struct {
		hashes []int32
		values []string
	}{
		{[]int32{96346}, []string{"aaz", "ab["}},
		{[]int32{96321}, []string{"aaa"}},
		{[]int32{96385}, []string{"acc"}},
		{[]int32{96346, 96321}, []string{"aaa"}},
		{[]int32{96346, 0}, []string{}},
		{[]int32{2147483647}, []string{}},
	} {
		ids, err := v.TrigramAnchor(context.Background(), c.hashes)
		if err != nil {
			t.Fatal(err)
		}
		values := []string{}
		for _, id := range ids {
			values = append(values, g.Strings[id])
		}
		// Compare sets, since SID order follows the actual writer's string table.
		got := map[string]bool{}
		want := map[string]bool{}
		for _, s := range values {
			got[s] = true
		}
		for _, s := range c.values {
			want[s] = true
		}
		if !reflect.DeepEqual(got, want) || len(values) != len(c.values) {
			t.Fatalf("hashes%v values%v want%v", c.hashes, values, c.values)
		}
	}
	ids, err := v.TrigramAnchor(context.Background(), []int32{96346})
	if err != nil {
		t.Fatal(err)
	}
	ids[0] = -1
	again, err := v.TrigramAnchor(context.Background(), []int32{96346})
	if err != nil || again[0] < 0 {
		t.Fatal("mapped span escaped", again, err)
	}
	if err = g.Close(); err != nil {
		t.Fatal(err)
	}
	if len(again) != 2 {
		t.Fatal("copied result changed after close")
	}
	if _, err = v.TrigramAnchor(context.Background(), []int32{96346}); !errors.Is(err, ErrStoreClosed) {
		t.Fatal(err)
	}
	if _, err = g.CertifyCallSiteTrigrams(context.Background(), v); !errors.Is(err, ErrStoreClosed) {
		t.Fatal(err)
	}
}
func TestTrigramProofPublicationCancellation(t *testing.T) {
	for _, variant := range []string{"", "missing"} {
		t.Run(variant, func(t *testing.T) {
			reference, v := trigramTestStore(t, variant)
			count := &certificateCountContext{Context: context.Background()}
			want, err := reference.CertifyCallSiteTrigrams(count, v)
			if err != nil {
				t.Fatal(err)
			}
			g, v := trigramTestStore(t, variant)
			cancel := &certificateCountContext{Context: context.Background(), cancelAt: count.checks}
			valid, err := g.CertifyCallSiteTrigrams(cancel, v)
			if !errors.Is(err, context.Canceled) || valid {
				t.Fatalf("publication got %v/%v", valid, err)
			}
			if g.trigramProof.completed || g.trigramProof.pending != nil {
				t.Fatal("cancellation poisoned proof state")
			}
			if valid, err = g.CertifyCallSiteTrigrams(context.Background(), v); err != nil || valid != want {
				t.Fatal(valid, err)
			}
		})
	}
}

type trigramPauseContext struct {
	context.Context
	checks           atomic.Int32
	at               int32
	entered, release chan struct{}
}

func (c *trigramPauseContext) Err() error {
	if c.checks.Add(1) == c.at {
		close(c.entered)
		<-c.release
	}
	return c.Context.Err()
}
func TestTrigramCloseBeforePublication(t *testing.T) {
	ref, v := trigramTestStore(t, "")
	counter := &certificateCountContext{Context: context.Background()}
	if valid, err := ref.CertifyCallSiteTrigrams(counter, v); err != nil || !valid {
		t.Fatal(valid, err)
	}
	g, v := trigramTestStore(t, "")
	// The final work check is outside the lifetime lock, immediately before publication.
	ctx := &trigramPauseContext{Context: context.Background(), at: int32(counter.checks - 1), entered: make(chan struct{}), release: make(chan struct{})}
	result := make(chan error, 1)
	go func() {
		valid, err := g.CertifyCallSiteTrigrams(ctx, v)
		if valid {
			result <- errors.New("closed proof published true")
		} else {
			result <- err
		}
	}()
	<-ctx.entered
	if err := g.Close(); err != nil {
		t.Fatal(err)
	}
	close(ctx.release)
	if err := <-result; !errors.Is(err, ErrStoreClosed) {
		t.Fatal(err)
	}
	if g.trigramProof.completed || g.trigramProof.pending != nil {
		t.Fatal("close published proof")
	}
}
func TestTrigramConcurrentCloseAndCanceledWaiter(t *testing.T) {
	g, v := trigramTestStore(t, "")
	ctx := &trigramPauseContext{Context: context.Background(), at: 5, entered: make(chan struct{}), release: make(chan struct{})}
	done := make(chan error, 1)
	go func() { _, err := g.CertifyCallSiteTrigrams(ctx, v); done <- err }()
	<-ctx.entered
	cancelCtx, cancel := context.WithCancel(context.Background())
	waiter := make(chan error, 1)
	go func() { _, err := g.CertifyCallSiteTrigrams(cancelCtx, v); waiter <- err }()
	cancel()
	if err := <-waiter; !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	close(ctx.release)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	var group sync.WaitGroup
	start := make(chan struct{})
	for i := 0; i < 12; i++ {
		group.Add(1)
		go func() {
			defer group.Done()
			<-start
			_, err := g.CertifyCallSiteTrigrams(context.Background(), v)
			if err != nil && !errors.Is(err, ErrStoreClosed) {
				t.Error(err)
			}
		}()
	}
	group.Add(1)
	go func() { defer group.Done(); <-start; g.Close() }()
	close(start)
	group.Wait()
}
func TestTrigramAnchorCancellationAtEveryCheck(t *testing.T) {
	_, v := trigramTestStore(t, "")
	hashes := []int32{96346, 96321}
	counter := &certificateCountContext{Context: context.Background()}
	if _, err := v.TrigramAnchor(counter, hashes); err != nil {
		t.Fatal(err)
	}
	for at := 1; at <= counter.checks; at++ {
		ctx := &certificateCountContext{Context: context.Background(), cancelAt: at}
		ids, err := v.TrigramAnchor(ctx, hashes)
		if !errors.Is(err, context.Canceled) || ids != nil {
			t.Fatalf("at%d got%v/%v", at, ids, err)
		}
	}
}

func TestTrigramAnchorCloseWaitsForCopy(t *testing.T) {
	g, v := trigramTestStore(t, "")
	// Two readLock checks, a hash lookup check, then the copy check under RLock.
	ctx := &trigramPauseContext{Context: context.Background(), at: 4, entered: make(chan struct{}), release: make(chan struct{})}
	result := make(chan []int32, 1)
	failure := make(chan error, 1)
	go func() { ids, err := v.TrigramAnchor(ctx, []int32{96346}); result <- ids; failure <- err }()
	<-ctx.entered
	closing := make(chan struct{})
	closed := make(chan error, 1)
	go func() { close(closing); closed <- g.Close() }()
	<-closing
	select {
	case err := <-closed:
		t.Fatalf("Close passed active reader lock: %v", err)
	default:
	}
	close(ctx.release)
	ids := <-result
	if err := <-failure; err != nil {
		t.Fatal(err)
	}
	if err := <-closed; err != nil {
		t.Fatal(err)
	}
	if len(ids) != 2 {
		t.Fatalf("copied result after close %v", ids)
	}
	if _, err := v.TrigramAnchor(context.Background(), []int32{96346}); !errors.Is(err, ErrStoreClosed) {
		t.Fatal(err)
	}
}
