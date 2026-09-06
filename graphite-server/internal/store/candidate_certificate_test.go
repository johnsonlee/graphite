package store

import (
	"context"
	"errors"
	"sync"
	"testing"
)

type certificateCountContext struct {
	context.Context
	checks, cancelAt int
}

func (c *certificateCountContext) Err() error {
	c.checks++
	if c.cancelAt > 0 && c.checks >= c.cancelAt {
		return context.Canceled
	}
	return nil
}
func certificateTestStore(t *testing.T, fixture string) (*Store, *CallSiteStringIndex) {
	t.Helper()
	g, err := OpenMode("../query/testdata/candidate-index/"+fixture, "MAPPED")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { g.Close() })
	v, ok, err := g.TryCallSiteStringIndex(context.Background())
	if err != nil || !ok {
		t.Fatalf("reader available %v, error %v", ok, err)
	}
	return g, v
}

func TestCandidateCertificatePublicationCancellation(t *testing.T) {
	for _, fixture := range []string{"clean", "bad-first-unmatched", "bad-last-unmatched", "bad-matched"} {
		t.Run(fixture, func(t *testing.T) {
			reference, view := certificateTestStore(t, fixture)
			counter := &certificateCountContext{Context: context.Background()}
			valid, err := reference.CertifyCallSiteCandidates(counter, view)
			if err != nil {
				t.Fatal(err)
			}
			if valid != (fixture == "clean") {
				t.Fatalf("%s certificate got %v", fixture, valid)
			}
			if !reference.candidateProof.completed {
				t.Fatal("uncanceled result was not cached")
			}
			// Cancel on the final publication check, after the validation path has
			// produced either true or false. Neither result may poison the cache.
			g, v := certificateTestStore(t, fixture)
			canceled := &certificateCountContext{Context: context.Background(), cancelAt: counter.checks}
			_, err = g.CertifyCallSiteCandidates(canceled, v)
			if !errors.Is(err, context.Canceled) {
				t.Fatalf("final check %d got %v", counter.checks, err)
			}
			if g.candidateProof.completed || g.candidateProof.pending != nil {
				t.Fatal("cancellation published or left pending state")
			}
			retry, err := g.CertifyCallSiteCandidates(context.Background(), v)
			if err != nil || retry != valid {
				t.Fatalf("retry got %v/%v want %v", retry, err, valid)
			}
		})
	}
}
func TestCandidateCertificateStoreLifetime(t *testing.T) {
	g, v := certificateTestStore(t, "clean")
	if valid, err := g.CertifyCallSiteCandidates(context.Background(), v); err != nil || !valid {
		t.Fatalf("certificate %v/%v", valid, err)
	}
	g.Close()
	if _, err := g.CertifyCallSiteCandidates(context.Background(), v); !errors.Is(err, ErrStoreClosed) {
		t.Fatalf("cached certificate after close: %v", err)
	}
	if _, err := g.CandidateNode(context.Background(), 17); !errors.Is(err, ErrStoreClosed) {
		t.Fatalf("node after close: %v", err)
	}
}
func TestCandidateCertificateConcurrentClose(t *testing.T) {
	g, v := certificateTestStore(t, "clean")
	start := make(chan struct{})
	var group sync.WaitGroup
	for i := 0; i < 12; i++ {
		group.Add(1)
		go func() {
			defer group.Done()
			<-start
			valid, err := g.CertifyCallSiteCandidates(context.Background(), v)
			if err != nil && !errors.Is(err, ErrStoreClosed) {
				t.Errorf("certificate error %v", err)
			}
			if err == nil && !valid {
				t.Error("valid immutable graph rejected")
			}
		}()
	}
	group.Add(1)
	go func() { defer group.Done(); <-start; g.Close() }()
	close(start)
	group.Wait()
	if _, err := g.CertifyCallSiteCandidates(context.Background(), v); !errors.Is(err, ErrStoreClosed) {
		t.Fatalf("post-close result %v", err)
	}
}
