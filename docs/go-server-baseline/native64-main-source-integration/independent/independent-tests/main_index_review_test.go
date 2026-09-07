package store

import (
	"context"
	"errors"
	"testing"
	"time"
)

func reviewReceive(t *testing.T, result <-chan error) error {
	t.Helper()
	select {
	case err := <-result:
		return err
	case <-time.After(5 * time.Second):
		t.Fatal("loader did not finish")
		return nil
	}
}

func TestReviewMainIndexCanceledWaiterDoesNotCancelOwner(t *testing.T) {
	for _, mapped := range []bool{false, true} {
		s := openIndexFixture(t, indexFixture, "MAPPED")
		owner := &mainIndexPause{Context: context.Background(), entered: make(chan struct{}), release: make(chan struct{})}
		loaded := make(chan error, 1)
		go func() {
			_, ok, err := s.tryMainCallSiteStringIndex(owner, mapped)
			if err == nil && !ok {
				err = errors.New("owner failed publication")
			}
			loaded <- err
		}()
		<-owner.entered
		waiter, cancel := context.WithCancel(context.Background())
		waited := make(chan error, 1)
		go func() { _, _, err := s.tryMainCallSiteStringIndex(waiter, mapped); waited <- err }()
		cancel()
		if err := reviewReceive(t, waited); !errors.Is(err, context.Canceled) {
			t.Fatal(err)
		}
		close(owner.release)
		if err := reviewReceive(t, loaded); err != nil {
			t.Fatal(err)
		}
		if _, ok, err := s.tryMainCallSiteStringIndex(context.Background(), mapped); err != nil || !ok {
			t.Fatal(ok, err)
		}
		s.Close()
	}
}

func TestReviewMainIndexCanceledOwnerAllowsWaitingRetry(t *testing.T) {
	for _, mapped := range []bool{false, true} {
		s := openIndexFixture(t, indexFixture, "MAPPED")
		base, cancel := context.WithCancel(context.Background())
		owner := &mainIndexPause{Context: base, entered: make(chan struct{}), release: make(chan struct{})}
		loaded := make(chan error, 1)
		go func() { _, _, err := s.tryMainCallSiteStringIndex(owner, mapped); loaded <- err }()
		<-owner.entered
		waited := make(chan error, 1)
		go func() {
			_, ok, err := s.tryMainCallSiteStringIndex(context.Background(), mapped)
			if err == nil && !ok {
				err = errors.New("waiter failed retry")
			}
			waited <- err
		}()
		cancel()
		close(owner.release)
		if err := reviewReceive(t, loaded); !errors.Is(err, context.Canceled) {
			t.Fatal(err)
		}
		if err := reviewReceive(t, waited); err != nil {
			t.Fatal(err)
		}
		s.Close()
	}
}

func TestReviewCloseJoinsAllThreeLoaderPolicies(t *testing.T) {
	s := openIndexFixture(t, indexFixture, "MAPPED")
	contexts := []*mainIndexPause{}
	loaded := make(chan error, 3)
	for policy := 0; policy < 3; policy++ {
		ctx := &mainIndexPause{Context: context.Background(), entered: make(chan struct{}), release: make(chan struct{})}
		contexts = append(contexts, ctx)
		go func(policy int) {
			var err error
			if policy == 0 {
				_, _, err = s.TryCallSiteStringIndex(ctx)
			} else {
				_, _, err = s.tryMainCallSiteStringIndex(ctx, policy == 2)
			}
			loaded <- err
		}(policy)
		<-ctx.entered
	}
	closed := make(chan error, 2)
	go func() { closed <- s.Close() }()
	<-s.callSiteIndex.closing
	go func() { closed <- s.Close() }()
	for _, ctx := range contexts[:2] {
		close(ctx.release)
	}
	for i := 0; i < 2; i++ {
		if err := reviewReceive(t, loaded); err != ErrStoreClosed {
			t.Fatal(err)
		}
	}
	select {
	case err := <-closed:
		t.Fatal("Close returned before final policy joined", err)
	default:
	}
	close(contexts[2].release)
	if err := reviewReceive(t, loaded); err != ErrStoreClosed {
		t.Fatal(err)
	}
	for i := 0; i < 2; i++ {
		if err := reviewReceive(t, closed); err != nil {
			t.Fatal(err)
		}
	}
	if s.callSiteIndex.view != nil {
		t.Fatal("strict publication after close")
	}
	for _, state := range s.callSiteIndex.main {
		if state.view != nil || state.loading != nil {
			t.Fatal("main publication after close")
		}
	}
}
