package store

import (
	"context"
	"encoding/binary"
	"fmt"
	"math"
)

// MainMappedCapability reports representation capability, not Store lifetime.
// The immutable view metadata remains inspectable after Close; reads still fail.
func (i *DistinctStringIndex) MainMappedCapability() bool {
	return i != nil && i.view != nil && i.view.mainRanges != nil
}

func (i *DistinctStringIndex) MainMappedCallSiteCount() int {
	if !i.MainMappedCapability() {
		return 0
	}
	return int(i.view.info.CallSiteCount)
}

// These accessors deliberately separate Store lifetime checks from main's
// request interruption checkpoints. They never return borrowed mapped bytes.
func (v *CallSiteStringIndex) mainMappedLock() error {
	v.owner.callSiteIndex.mu.RLock()
	if v.owner.callSiteIndex.closed {
		v.owner.callSiteIndex.mu.RUnlock()
		return ErrStoreClosed
	}
	return nil
}
func (v *CallSiteStringIndex) mainMappedIntLocked(at int64) (int32, error) {
	if at < 0 || at > int64(len(v.data))-4 {
		return 0, &ProjectionReadError{}
	}
	return int32(binary.BigEndian.Uint32(v.data[int(at):])), nil
}

// Each property posting IntBuffer has exactly CallSiteCount elements. File
// bounds alone would incorrectly allow a read to spill into the next region.
func (v *CallSiteStringIndex) mainMappedNodeIDLocked(p CallSiteStringProperty, position int) (int32, error) {
	if position < 0 || int64(position) >= int64(v.info.CallSiteCount) {
		return 0, &ProjectionReadError{}
	}
	return v.mainMappedIntLocked(int64(v.regions[p].nodes) + int64(position)*4)
}

func mainMappedArrayError(index, size int) error {
	message := fmt.Sprintf("Index %d out of bounds for length %d", index, size)
	return &ProjectionReadError{Class: "ArrayIndexOutOfBoundsException", Message: &message}
}

func (v *CallSiteStringIndex) mainMappedPosting(pos int) (int64, error) {
	if err := v.mainMappedLock(); err != nil {
		return 0, err
	}
	defer v.owner.callSiteIndex.mu.RUnlock()
	if pos < 0 || int64(pos) >= int64(v.info.TrigramPostingCount) {
		return 0, &ProjectionReadError{}
	}
	at := int64(v.trigrams) + int64(pos)*8
	if at < 0 || at > int64(len(v.data))-8 {
		return 0, &ProjectionReadError{}
	}
	return int64(binary.BigEndian.Uint64(v.data[int(at):])), nil
}

// MainMappedTrigramSpan performs main's two bound searches without consuming
// the span. The caller owns the one logical lookup charge and absolute-index
// interruption polling while it later consumes its selected anchor.
func (i *DistinctStringIndex) MainMappedTrigramSpan(hash int32) (start, end int, found bool, err error) {
	v := i.view
	if v == nil {
		return 0, 0, false, nil
	}
	low, high := 0, int(v.info.TrigramPostingCount)
	for low < high {
		middle := (low + high) / 2
		posting, err := v.mainMappedPosting(middle)
		if err != nil {
			return 0, 0, false, err
		}
		if int32(posting>>32) < hash {
			low = middle + 1
		} else {
			high = middle
		}
	}
	start = low
	if start >= int(v.info.TrigramPostingCount) {
		return 0, 0, false, nil
	}
	posting, err := v.mainMappedPosting(start)
	if err != nil {
		return 0, 0, false, err
	}
	if int32(posting>>32) != hash {
		return 0, 0, false, nil
	}
	high = int(v.info.TrigramPostingCount)
	for low < high {
		middle := (low + high) / 2
		posting, err := v.mainMappedPosting(middle)
		if err != nil {
			return 0, 0, false, err
		}
		if int32(posting>>32) <= hash {
			low = middle + 1
		} else {
			high = middle
		}
	}
	return start, low, true, nil
}

func (i *DistinctStringIndex) MainMappedTrigramStringIDAt(pos int) (int32, error) {
	if i.view == nil {
		return 0, &ProjectionReadError{}
	}
	posting, err := i.view.mainMappedPosting(pos)
	return int32(posting), err
}

func (s *Store) MainMappedString(sid int32) (string, error) {
	s.callSiteIndex.mu.RLock()
	defer s.callSiteIndex.mu.RUnlock()
	if s.callSiteIndex.closed {
		return "", ErrStoreClosed
	}
	if sid < 0 || int64(sid) >= int64(len(s.Strings)) {
		return "", projectionStringError(sid, len(s.Strings))
	}
	return s.Strings[sid], nil
}

type mainMappedWork struct {
	consumeWork func(int64) error
	pending     int64
}

func (w *mainMappedWork) consume() error {
	if w.consumeWork == nil {
		return nil
	}
	w.pending++
	if w.pending >= 1024 {
		return w.flush()
	}
	return nil
}
func (w *mainMappedWork) flush() error {
	if w.pending == 0 {
		return nil
	}
	n := w.pending
	w.pending = 0
	return submitPersistentWork(w.consumeWork, n)
}

func (i *DistinctStringIndex) MainMappedPostingRangeWithWork(p CallSiteStringProperty, sid int32, consume func(int64) error) (start, end int, found bool, err error) {
	_, start, end, found, err = i.mainMappedPostingRangeWithWork(p, sid, consume)
	return
}

func (i *DistinctStringIndex) mainMappedPostingRangeWithWork(p CallSiteStringProperty, sid int32, consume func(int64) error) (row, start, end int, found bool, err error) {
	v := i.view
	if v == nil || p > CalleeName {
		return 0, 0, 0, false, nil
	}
	work := mainMappedWork{consumeWork: consume}
	defer func() {
		if rejected := work.flush(); rejected != nil {
			found = false
			err = rejected
		}
	}()
	r := v.regions[p]
	low, high := 0, int(v.info.UniqueStringCounts[p])-1
	for low <= high {
		if err = work.consume(); err != nil {
			return
		}
		middle := (low + high) / 2
		if err = v.mainMappedLock(); err != nil {
			return
		}
		value, readErr := v.mainMappedIntLocked(int64(r.strings) + int64(middle)*4)
		v.owner.callSiteIndex.mu.RUnlock()
		if readErr != nil {
			err = readErr
			return
		}
		if value < sid {
			low = middle + 1
			continue
		}
		if value > sid {
			high = middle - 1
			continue
		}
		row = middle
		found = true
		break
	}
	// The binarySearch finally flush precedes posting-end reads.
	if err = work.flush(); err != nil {
		found = false
		return
	}
	if !found {
		return
	}
	if err = v.mainMappedLock(); err != nil {
		found = false
		return
	}
	defer v.owner.callSiteIndex.mu.RUnlock()
	value, readErr := v.mainMappedIntLocked(int64(r.ends) + int64(row)*4)
	if readErr != nil {
		err = readErr
		found = false
		return
	}
	end = int(value)
	if row > 0 {
		value, readErr := v.mainMappedIntLocked(int64(r.ends) + int64(row-1)*4)
		if readErr != nil {
			err = readErr
			found = false
			return
		}
		start = int(value)
	}
	return
}

// MainMappedCursor owns its scalar current values and optional cold-validation
// orders. A warm cursor reads just its first entry, then one entry per Advance.
// Request polling belongs to the sequence owner, not these accessors.
type MainMappedCursor struct {
	view                 *CallSiteStringIndex
	property             CallSiteStringProperty
	first, position, end int
	orders               []int64
	nodeID               int32
	order                int64
}

func (c *MainMappedCursor) HasCurrent() bool { return c != nil && c.position < c.end }
func (c *MainMappedCursor) NodeID() int32    { return c.nodeID }
func (c *MainMappedCursor) Order() int64     { return c.order }
func (c *MainMappedCursor) Advance() (bool, error) {
	c.position++
	if !c.HasCurrent() {
		return false, nil
	}
	if err := c.readCurrent(); err != nil {
		return false, err
	}
	return true, nil
}
func (c *MainMappedCursor) readCurrent() error {
	v := c.view
	if err := v.mainMappedLock(); err != nil {
		return err
	}
	defer v.owner.callSiteIndex.mu.RUnlock()
	id, err := v.mainMappedNodeIDLocked(c.property, c.position)
	if err != nil {
		return err
	}
	c.nodeID = id
	if c.orders != nil {
		at := int(int32(c.position) - int32(c.first))
		if at < 0 || at >= len(c.orders) {
			return mainMappedArrayError(at, len(c.orders))
		}
		c.order = c.orders[at]
		return nil
	}
	order, err := v.owner.projectionOffsetLocked(id)
	if err != nil {
		return err
	}
	c.order = order
	return nil
}

func (i *DistinctStringIndex) MainMappedCursorWithWork(ctx context.Context, p CallSiteStringProperty, sid int32, consume func(int64) error) (*MainMappedCursor, bool, error) {
	v := i.view
	if v == nil || v.mainRanges == nil || p > CalleeName {
		return nil, false, nil
	}
	row, start, end, found, err := i.mainMappedPostingRangeWithWork(p, sid, consume)
	if err != nil {
		return nil, false, err
	}
	if !found {
		return nil, true, nil
	}
	key := uint64(p)<<32 | uint64(uint32(row))
	folded := key ^ (key >> 32)
	slot := (folded ^ (folded >> 16)) & 1023
	if err = v.mainMappedLock(); err != nil {
		return nil, false, err
	}
	state := uint8(0)
	if v.mainRanges.keys[slot] == key {
		state = v.mainRanges.states[slot]
	}
	v.owner.callSiteIndex.mu.RUnlock()
	if state == 2 {
		return nil, false, nil
	}
	var orders []int64
	if state == 0 {
		var valid bool
		orders, valid, err = i.validateMainMappedRange(ctx, p, start, end, consume)
		if err != nil {
			return nil, false, err
		}
		v.owner.callSiteIndex.mu.Lock()
		if v.owner.callSiteIndex.closed {
			v.owner.callSiteIndex.mu.Unlock()
			return nil, false, ErrStoreClosed
		}
		// Main has no request-interruption check between finally and cache put.
		if v.mainRanges.states[slot] != 0 && v.mainRanges.keys[slot] == key {
			valid = v.mainRanges.states[slot] == 1
		} else {
			v.mainRanges.keys[slot] = key
			v.mainRanges.states[slot] = 2
			if valid {
				v.mainRanges.states[slot] = 1
			}
		}
		v.owner.callSiteIndex.mu.Unlock()
		if !valid {
			return nil, false, nil
		}
	}
	cursor := &MainMappedCursor{view: v, property: p, first: start, position: start, end: end, orders: orders}
	// Main constructs its current node/order even for an empty range.
	if err = cursor.readCurrent(); err != nil {
		return nil, false, err
	}
	return cursor, true, nil
}

func (i *DistinctStringIndex) validateMainMappedRange(ctx context.Context, p CallSiteStringProperty, start, end int, consume func(int64) error) (orders []int64, valid bool, err error) {
	size := int32(end) - int32(start)
	if size < 0 {
		message := fmt.Sprint(size)
		return nil, false, &ProjectionReadError{Class: "NegativeArraySizeException", Message: &message}
	}
	orders = make([]int64, int(size))
	valid = true
	work := mainMappedWork{consumeWork: consume}
	defer func() {
		if rejected := work.flush(); rejected != nil {
			valid = false
			err = rejected
		}
	}()
	previous := int64(math.MinInt64)
	v := i.view
	for position := start; position < end; position++ {
		if position&1023 == 0 {
			if err = ctx.Err(); err != nil {
				return nil, false, err
			}
		}
		if err = work.consume(); err != nil {
			return nil, false, err
		}
		if err = v.mainMappedLock(); err != nil {
			return nil, false, err
		}
		id, readErr := v.mainMappedNodeIDLocked(p, position)
		var order int64
		if readErr == nil {
			order, readErr = v.owner.projectionOffsetLocked(id)
		}
		v.owner.callSiteIndex.mu.RUnlock()
		if readErr != nil {
			return nil, false, readErr
		}
		at := int(int32(position) - int32(start))
		if at < 0 || at >= len(orders) {
			return nil, false, mainMappedArrayError(at, len(orders))
		}
		orders[at] = order
		if order < 0 || order <= previous {
			valid = false
		}
		previous = order
	}
	return
}
