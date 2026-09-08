package store

import (
	"context"
	"encoding/binary"
	"errors"
	"hash/crc32"
	"io"
	"math"
	"os"
	"path/filepath"
)

// WorkAbortedError distinguishes a rejected work callback from optional index
// corruption. Callers must propagate Cause rather than silently falling back.
type WorkAbortedError struct{ Cause error }

func (e *WorkAbortedError) Error() string {
	if e.Cause == nil {
		return "persisted index work aborted"
	}
	return e.Cause.Error()
}
func (e *WorkAbortedError) Unwrap() error { return e.Cause }

type persistentReadWork struct {
	ctx       context.Context
	closing   <-chan struct{}
	consumer  func(int64) error
	inspected uint64
	pending   int64
}

func (w *persistentReadWork) consume() error {
	// PersistentIndexReadWork checks interruption before the batch consumer.
	if w.inspected&1023 == 0 {
		if err := indexCheck(w.ctx, w.closing); err != nil {
			return err
		}
	}
	w.inspected++
	if w.consumer == nil {
		return nil
	}
	w.pending++
	if w.pending >= 1024 {
		return w.flush()
	}
	return nil
}
func (w *persistentReadWork) flush() error {
	if w.pending == 0 {
		return nil
	}
	n := w.pending
	w.pending = 0
	return submitPersistentWork(w.consumer, n)
}
func submitPersistentWork(consumer func(int64) error, units int64) error {
	if consumer != nil {
		if err := consumer(units); err != nil {
			return &WorkAbortedError{Cause: err}
		}
	}
	return nil
}

// loadMainRetainedIndex has main's optional-file boundary. Identity derivation
// is outside that boundary; rejected accounting and cancellation always escape.
// Strict candidate and mapped-view readers continue to use their own policies.
func (s *Store) loadMainRetainedIndex(ctx context.Context, closing <-chan struct{}, capacity int32, consumeWork func(int64) error) (*CallSiteStringIndex, error) {
	path := filepath.Join(s.dir, callSiteIndexFile)
	stat, err := os.Stat(path)
	if err != nil || !stat.Mode().IsRegular() || len(s.byKind["CallSiteNode"]) == 0 {
		return nil, nil
	}
	identity, err := s.mainPersistedContentIdentity(ctx, closing, consumeWork)
	if err != nil {
		return nil, err
	}
	file, err := os.Open(path)
	if err != nil {
		return nil, nil
	}
	defer file.Close()
	stat, err = file.Stat()
	if err != nil || !stat.Mode().IsRegular() {
		return nil, nil
	}
	var data []byte
	if stat.Size() > 0 {
		data, err = mapNodeData(file, stat.Size())
		if err != nil {
			return nil, nil
		}
	}
	keep := false
	defer func() {
		if !keep && len(data) != 0 {
			_ = unmapNodeData(data)
		}
	}()
	view, end, err := s.readMainRetainedIndex(ctx, closing, data, identity, capacity, consumeWork)
	if err == nil {
		// Main's separate EOF consumer intentionally has no finally flush.
		// A trailing byte rejects/closes the temporary index without submitting
		// its pending one unit. Successful EOF submits before publication.
		trailing := persistentReadWork{ctx: ctx, closing: closing, consumer: consumeWork}
		err = trailing.consume()
		if err == nil {
			if end != len(data) {
				err = errors.New("trailing data in persisted CallSite string index")
			} else {
				err = trailing.flush()
			}
		}
	}
	if err != nil {
		var aborted *WorkAbortedError
		if errors.As(err, &aborted) || errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) || errors.Is(err, ErrStoreClosed) {
			return nil, err
		}
		return nil, nil
	}
	keep = true
	return view, nil
}

// This private sentinel implements DataInput/require's throwing read sequence;
// it is recovered inside the reader, before the optional-file boundary above.
type retainedReadFailure struct{ err error }
type retainedReader struct {
	data []byte
	pos  int
	crc  uint32
	work *persistentReadWork
}

func (r *retainedReader) fail(err error) { panic(retainedReadFailure{err}) }
func (r *retainedReader) require(ok bool) {
	if !ok {
		r.fail(errors.New("invalid persisted CallSite string index"))
	}
}
func (r *retainedReader) take(n int) []byte {
	if n > len(r.data)-r.pos {
		r.fail(io.ErrUnexpectedEOF)
	}
	b := r.data[r.pos : r.pos+n]
	r.pos += n
	return b
}
func (r *retainedReader) consume() {
	if err := r.work.consume(); err != nil {
		r.fail(err)
	}
}
func (r *retainedReader) i32() int32 {
	r.consume()
	v := binary.BigEndian.Uint32(r.take(4))
	var bytes [4]byte
	binary.LittleEndian.PutUint32(bytes[:], v)
	r.crc = crc32.Update(r.crc, crc32.IEEETable, bytes[:])
	return int32(v)
}
func (r *retainedReader) i64() int64 {
	r.consume()
	v := binary.BigEndian.Uint64(r.take(8))
	var bytes [8]byte
	binary.LittleEndian.PutUint64(bytes[:], v)
	r.crc = crc32.Update(r.crc, crc32.IEEETable, bytes[:])
	return int64(v)
}

func (s *Store) readMainRetainedIndex(ctx context.Context, closing <-chan struct{}, data []byte, identity [32]byte, capacity int32, consumeWork func(int64) error) (view *CallSiteStringIndex, end int, err error) {
	work := persistentReadWork{ctx: ctx, closing: closing, consumer: consumeWork}
	defer func() {
		failure := recover()
		// Finally's rejection replaces an earlier read/validation exception.
		if rejected := work.flush(); rejected != nil {
			view, err = nil, rejected
			return
		}
		if failure != nil {
			if failed, ok := failure.(retainedReadFailure); ok {
				view, err = nil, failed.err
			} else {
				panic(failure)
			}
		}
	}()
	r := retainedReader{data: data, work: &work}
	r.require(r.i32() == 0x47524353)
	r.require(r.i32() == 2)
	info := CallSiteStringIndexInfo{Version: 2}
	info.StringCount, info.CallSiteCount = r.i32(), r.i32()
	r.require(int64(info.StringCount) == int64(len(s.Strings)))
	r.require(int64(info.CallSiteCount) == int64(len(s.byKind["CallSiteNode"])))
	// readFully happens before any of the identity's 32 accounting events.
	copy(info.ContentIdentity[:], r.take(32))
	for range info.ContentIdentity {
		r.consume()
	}
	r.require(info.ContentIdentity == identity)
	r.crc = crc32.Update(r.crc, crc32.IEEETable, info.ContentIdentity[:])
	for p := range info.UniqueStringCounts {
		info.UniqueStringCounts[p] = r.i32()
	}
	var unique int64
	for _, count := range info.UniqueStringCounts {
		r.require(count >= 0 && count <= info.StringCount)
		unique += int64(count)
	}
	info.TrigramPostingCount = r.i32()
	r.require(info.TrigramPostingCount > 0)
	info.RetainedBytes = r.i64()
	expectedBytes := int64(480) + 8*unique + 16*int64(info.CallSiteCount) + 8*int64(info.StringCount) + 8*int64(info.TrigramPostingCount)
	r.require(info.RetainedBytes == expectedBytes)
	v := &CallSiteStringIndex{owner: s, data: data, info: info}
	for p, count := range info.UniqueStringCounts {
		region := callSiteIndexRegion{strings: r.pos}
		previousID := int32(-1)
		for row := int32(0); row < count; row++ {
			id := r.i32()
			r.require(id >= 0 && id < info.StringCount && id > previousID)
			previousID = id
		}
		region.ends = r.pos
		previousEnd := int32(0)
		for row := int32(0); row < count; row++ {
			postingEnd := r.i32()
			r.require(postingEnd > previousEnd && postingEnd <= info.CallSiteCount)
			previousEnd = postingEnd
		}
		r.require(previousEnd == info.CallSiteCount)
		region.nodes = r.pos
		row := int32(0)
		previousOrder := int64(math.MinInt64)
		for position := int32(0); position < info.CallSiteCount; position++ {
			nodeID := r.i32()
			r.require(nodeID >= 0 && nodeID < capacity)
			order, readErr := s.ProjectionNodeOrder(ctx, nodeID)
			if readErr != nil {
				r.fail(readErr)
			}
			r.require(order > previousOrder)
			previousOrder = order
			postingEnd := int32(binary.BigEndian.Uint32(data[region.ends+int(row)*4:]))
			if position+1 == postingEnd {
				row++
				previousOrder = math.MinInt64
			}
		}
		r.require(row == count)
		v.regions[p] = region
	}
	v.signatures = r.pos
	for i := int32(0); i < info.StringCount; i++ {
		r.i64()
	}
	v.trigrams = r.pos
	previous := int64(math.MinInt64)
	for i := int32(0); i < info.TrigramPostingCount; i++ {
		posting := r.i64()
		r.require(posting >= previous)
		r.require(int32(posting) >= 0 && int32(posting) < info.StringCount)
		previous = posting
	}
	checksum := int64(r.crc)
	r.require(r.i64() == checksum)
	if rejected := work.flush(); rejected != nil {
		r.fail(rejected)
	}
	return v, r.pos, nil
}
