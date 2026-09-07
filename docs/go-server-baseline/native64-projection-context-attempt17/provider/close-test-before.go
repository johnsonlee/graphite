package store

import (
	"context"
	"errors"
	"math"
	"os"
	"path/filepath"
	"reflect"
	"runtime"
	"strings"
	"testing"
	"time"
)

// Observes the actual named checkpoint over an ordinary cancellable context.
// Both APIs delegate their real state, and Err after a closed Done is not a second poll.
type projectionCheckpointContext struct {
	context.Context
	target    string
	calls, at int
	action    func()
}

func (c *projectionCheckpointContext) observe() {
	if c.Context.Err() != nil {
		return
	}
	pc, _, _, _ := runtime.Caller(2)
	if f := runtime.FuncForPC(pc); f != nil && strings.HasSuffix(f.Name(), c.target) {
		c.calls++
		if c.calls == c.at && c.action != nil {
			c.action()
		}
	}
}
func (c *projectionCheckpointContext) Done() <-chan struct{} { c.observe(); return c.Context.Done() }
func (c *projectionCheckpointContext) Err() error            { c.observe(); return c.Context.Err() }

func TestProjectionContextStandardStatesAndPrecedence(t *testing.T) {
	s := openIndexFixture(t, copyIndexFixture(t), "MAPPED")
	want, err := s.Node(17)
	if err != nil {
		t.Fatal(err)
	}
	order := s.locations[17].offset
	cancelled, cancel := context.WithCancel(context.Background())
	cancel()
	deadline, stop := context.WithDeadline(context.Background(), time.Unix(1, 0))
	defer stop()
	cause := errors.New("distinct cause")
	caused, cancelCause := context.WithCancelCause(context.Background())
	cancelCause(cause)
	parent, cancelParent := context.WithCancel(context.Background())
	defer cancelParent()
	child, cancelChild := context.WithCancel(parent)
	cancelChild()
	states := []struct {
		name string
		ctx  context.Context
		want error
	}{
		{"background", context.Background(), nil}, {"todo", context.TODO(), nil}, {"cancelled", cancelled, context.Canceled},
		{"deadline", deadline, context.DeadlineExceeded}, {"cause", caused, context.Canceled},
		{"value-child", context.WithValue(child, struct{}{}, 1), context.Canceled}, {"live-parent", parent, nil},
		{"without-cancel", context.WithoutCancel(cancelled), nil},
	}
	for _, state := range states {
		t.Run(state.name, func(t *testing.T) {
			if err := s.prepareProjectionOffsets(state.ctx); err != state.want {
				t.Fatalf("prepare=%v want=%v", err, state.want)
			}
			got, e := s.ProjectionNodeOrder(state.ctx, 17)
			if e != state.want || e == nil && got != order {
				t.Fatalf("order=%v/%v want=%v/%v", got, e, order, state.want)
			}
			node, ok, e := s.ProjectionCandidateNode(state.ctx, 17)
			if e != state.want || e == nil && (!ok || !reflect.DeepEqual(node, want)) {
				t.Fatalf("node=%+v/%v/%v want=%+v/%v", node, ok, e, want, state.want)
			}
			if state.want != nil && ok {
				t.Fatal("canceled read published a node")
			}
		})
	}
	if context.Cause(caused) != cause || parent.Err() != nil {
		t.Fatal("cause or parent changed")
	}
	if _, err := s.ProjectionNodeOrder(cancelled, math.MaxInt32); err != context.Canceled {
		t.Fatal("offset error moved before cancellation", err)
	}
	if _, _, err := s.ProjectionCandidateNode(cancelled, math.MaxInt32); err != context.Canceled {
		t.Fatal("decoder offset error moved before cancellation", err)
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	if err := s.prepareProjectionOffsets(cancelled); err != ErrStoreClosed {
		t.Fatal("closed must precede cancel", err)
	}
	if _, err := s.ProjectionNodeOrder(cancelled, 17); err != ErrStoreClosed {
		t.Fatal(err)
	}
	if _, _, err := s.ProjectionCandidateNode(cancelled, 17); err != ErrStoreClosed {
		t.Fatal(err)
	}
}

func TestProjectionContextPreparationPublicationAndFormatOrder(t *testing.T) {
	t.Run("cancel after read before publication", func(t *testing.T) {
		s := openIndexFixture(t, copyIndexFixture(t), "MAPPED")
		base, cancel := context.WithCancel(context.Background())
		defer cancel()
		ctx := &projectionCheckpointContext{Context: base, target: ".prepareProjectionOffsets", at: 2, action: cancel}
		err := s.prepareProjectionOffsets(ctx)
		if err != context.Canceled || ctx.calls != 2 || base.Err() != context.Canceled || s.distinctProjection.offsetsLoaded {
			t.Fatalf("err=%v calls=%d state=%+v", err, ctx.calls, s.distinctProjection)
		}
		if err = s.prepareProjectionOffsets(context.Background()); err != nil || !s.distinctProjection.offsetsLoaded {
			t.Fatal("retry", err)
		}
	})
	t.Run("format failure before postread checkpoint", func(t *testing.T) {
		dir := copyIndexFixture(t)
		s := openIndexFixture(t, dir, "MAPPED")
		if err := os.WriteFile(filepath.Join(dir, "graph.nodeoffsets"), []byte{1}, 0600); err != nil {
			t.Fatal(err)
		}
		base, cancel := context.WithCancel(context.Background())
		defer cancel()
		ctx := &projectionCheckpointContext{Context: base, target: ".prepareProjectionOffsets", at: 2, action: cancel}
		err := s.prepareProjectionOffsets(ctx)
		var format *ProjectionReadError
		if !errors.As(err, &format) || format.Message != nil || ctx.calls != 1 || base.Err() != nil || s.distinctProjection.offsetsLoaded {
			t.Fatalf("err=%v calls=%d canceled=%v published=%v", err, ctx.calls, base.Err(), s.distinctProjection.offsetsLoaded)
		}
	})
}

func TestProjectionContextDecoderCancelAndFreshRequest(t *testing.T) {
	s := openIndexFixture(t, copyIndexFixture(t), "MAPPED")
	want, err := s.Node(17)
	if err != nil {
		t.Fatal(err)
	}
	base, cancel := context.WithCancel(context.Background())
	defer cancel()
	ctx := &projectionCheckpointContext{Context: base, target: ".ProjectionCandidateNode.func1", at: 2, action: cancel}
	node, ok, err := s.ProjectionCandidateNode(ctx, 17)
	if err != context.Canceled || ok || ctx.calls != 2 || base.Err() != context.Canceled {
		t.Fatalf("node=%+v ok=%v err=%v actual polls=%d", node, ok, err, ctx.calls)
	}
	node, ok, err = s.ProjectionCandidateNode(context.Background(), 17)
	if err != nil || !ok || !reflect.DeepEqual(node, want) {
		t.Fatalf("fresh request %+v %v %v", node, ok, err)
	}
}

func TestProjectionContextCloseWaitsForCanceledDecoder(t *testing.T) {
	s := openIndexFixture(t, copyIndexFixture(t), "MAPPED")
	base, cancel := context.WithCancel(context.Background())
	defer cancel()
	entered, release := make(chan struct{}), make(chan struct{})
	ctx := &projectionCheckpointContext{Context: base, target: ".ProjectionCandidateNode.func1", at: 1, action: func() { close(entered); <-release }}
	result := make(chan error, 1)
	go func() { _, _, err := s.ProjectionCandidateNode(ctx, 17); result <- err }()
	select {
	case <-entered:
	case <-time.After(5 * time.Second):
		t.Fatal("decoder checkpoint not observed")
	}
	closed := make(chan error, 1)
	go func() { closed <- s.Close() }()
	select {
	case <-s.callSiteIndex.closing:
	case <-time.After(5 * time.Second):
		t.Fatal("close did not start")
	}
	select {
	case err := <-closed:
		t.Fatal("close released in-flight decoder", err)
	default:
	}
	cancel()
	close(release)
	select {
	case err := <-result:
		if err != context.Canceled {
			t.Fatal(err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("decoder did not leave lifetime lock")
	}
	select {
	case err := <-closed:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Close did not join read")
	}
	if ctx.calls != 1 {
		t.Fatal("required read boundary not reached exactly once", ctx.calls)
	}
	if _, _, err := s.ProjectionCandidateNode(base, 17); err != ErrStoreClosed {
		t.Fatal("closed/canceled precedence", err)
	}
}
