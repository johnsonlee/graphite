package query

// Raw storage scans submit work in the same 1,024-node batches as main's
// BufferedGraphWorkConsumer. Each iterator flushes before yielding a node and
// on exhaustion or failure; an abandoned iterator therefore has no pending work.
type bufferedGraphWork struct {
	work    *ExecutionContext
	pending int64
}

func (b *bufferedGraphWork) consume() {
	if b.work == nil {
		return
	}
	b.pending++
	if b.pending >= 1024 {
		b.flush()
	}
}

func (b *bufferedGraphWork) flush() {
	if b.pending == 0 {
		return
	}
	batch := b.pending
	// A failed submission must not be submitted again by the deferred flush.
	b.pending = 0
	// Local worker cancellation does not cancel the shared request tracker.
	// Do not poll the worker context here, including for empty submissions.
	b.work.consume(batch)
}
