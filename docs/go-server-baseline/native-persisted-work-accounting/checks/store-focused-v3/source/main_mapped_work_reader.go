package store

import (
	"context"
	"encoding/binary"
	"errors"
	"hash/crc32"
	"math"
	"os"
	"path/filepath"
)

// The mapped view has a different work protocol from the retained reader:
// validate a complete chunk, update its CRC, then charge that chunk. A failed
// chunk has no pending work, and neither validation nor EOF has a finally flush.
const mainViewChecksumChunkBytes = 1 << 20

func (s *Store) loadMainMappedIndex(ctx context.Context, closing <-chan struct{}, capacity int32, consumeWork func(int64) error) (view *CallSiteStringIndex, unavailable bool, err error) {
	path := filepath.Join(s.dir, callSiteIndexFile)
	stat, err := os.Stat(path)
	if err != nil || !stat.Mode().IsRegular() {
		return nil, false, nil
	}
	if len(s.byKind["CallSiteNode"]) == 0 {
		return nil, true, nil
	}
	identity, err := s.mainPersistedContentIdentity(ctx, closing, consumeWork)
	if err != nil {
		return nil, false, err
	}
	if err = indexCheck(ctx, closing); err != nil {
		return nil, false, err
	}
	f, err := os.Open(path)
	if err != nil {
		return nil, true, nil
	}
	defer f.Close()
	stat, err = f.Stat()
	if err != nil || !stat.Mode().IsRegular() || stat.Size() < 84 || stat.Size() > math.MaxInt32 {
		return nil, true, nil
	}
	data, err := mapNodeData(f, stat.Size())
	if err != nil {
		return nil, true, nil
	}
	keep := false
	defer func() {
		if !keep {
			_ = unmapNodeData(data)
		}
	}()
	view, err = s.readMainMappedIndex(ctx, closing, data, identity, capacity, consumeWork)
	if err != nil {
		var aborted *WorkAbortedError
		if errors.As(err, &aborted) || errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) || errors.Is(err, ErrStoreClosed) {
			return nil, false, err
		}
		return nil, true, nil
	}
	keep = true
	return view, false, nil
}

type mainViewValidator struct {
	ctx      context.Context
	closing  <-chan struct{}
	consumer func(int64) error
	data     []byte
	crc      uint32
}

func (v *mainViewValidator) require(ok bool) {
	if !ok {
		panic(retainedReadFailure{errors.New("invalid persisted CallSite string index view")})
	}
}
func (v *mainViewValidator) charge(n int64) {
	if err := submitPersistentWork(v.consumer, n); err != nil {
		panic(retainedReadFailure{err})
	}
}
func (v *mainViewValidator) updateInt(value uint32) {
	var word [4]byte
	binary.LittleEndian.PutUint32(word[:], value)
	v.crc = crc32.Update(v.crc, crc32.IEEETable, word[:])
}
func (v *mainViewValidator) updateLong(value uint64) {
	var word [8]byte
	binary.LittleEndian.PutUint64(word[:], value)
	v.crc = crc32.Update(v.crc, crc32.IEEETable, word[:])
}
func (v *mainViewValidator) array(offset, count, width int, validate func(int64)) {
	for start := 0; start < count; {
		if err := indexCheck(v.ctx, v.closing); err != nil {
			panic(retainedReadFailure{err})
		}
		end := min(count, start+mainViewChecksumChunkBytes/width)
		for i := start; i < end; i++ {
			at := offset + i*width
			if width == 4 {
				value := binary.BigEndian.Uint32(v.data[at:])
				validate(int64(int32(value)))
				v.updateInt(value)
			} else {
				value := binary.BigEndian.Uint64(v.data[at:])
				validate(int64(value))
				v.updateLong(value)
			}
		}
		v.charge(int64(end - start))
		start = end
	}
}

func (s *Store) readMainMappedIndex(ctx context.Context, closing <-chan struct{}, data []byte, identity [32]byte, capacity int32, consumeWork func(int64) error) (view *CallSiteStringIndex, err error) {
	defer func() {
		if failure := recover(); failure != nil {
			if failed, ok := failure.(retainedReadFailure); ok {
				view, err = nil, failed.err
			} else {
				panic(failure)
			}
		}
	}()
	v := mainViewValidator{ctx: ctx, closing: closing, consumer: consumeWork, data: data}
	v.require(len(data) >= 84)
	read32 := func(at int) int32 { return int32(binary.BigEndian.Uint32(data[at:])) }
	v.require(read32(0) == 0x47524353)
	v.require(read32(4) == 2)
	info := CallSiteStringIndexInfo{Version: 2, StringCount: read32(8), CallSiteCount: read32(12)}
	v.require(int64(info.StringCount) == int64(len(s.Strings)) && int64(info.CallSiteCount) == int64(len(s.byKind["CallSiteNode"])))
	copy(info.ContentIdentity[:], data[16:48])
	v.require(info.ContentIdentity == identity)
	for p := range info.UniqueStringCounts {
		info.UniqueStringCounts[p] = read32(48 + 4*p)
	}
	for _, n := range info.UniqueStringCounts {
		v.require(n >= 0 && n <= info.StringCount)
	}
	info.TrigramPostingCount = read32(64)
	v.require(info.TrigramPostingCount > 0)
	info.RetainedBytes = int64(binary.BigEndian.Uint64(data[68:76]))
	v.require(info.RetainedBytes > 0)
	index := &CallSiteStringIndex{owner: s, data: data, info: info}
	// Map/validate every region before the validator's first charge, as main's
	// mappedInts/mappedLongs and exact-size gate do. No node offsets are read.
	offset := int64(callSiteIndexHeaderBytes)
	region := func(count int32, width int64) int {
		start := offset
		bytes := int64(count) * width
		v.require(count >= 0 && bytes <= math.MaxInt32 && start+bytes <= int64(len(data)))
		offset += bytes
		return int(start)
	}
	for p, n := range info.UniqueStringCounts {
		index.regions[p] = callSiteIndexRegion{strings: region(n, 4), ends: region(n, 4), nodes: region(info.CallSiteCount, 4)}
	}
	index.signatures = int(offset)
	offset += int64(info.StringCount) * 8
	v.require(offset+int64(info.TrigramPostingCount)*8+8 == int64(len(data)))
	index.trigrams = region(info.TrigramPostingCount, 8)
	for at := 0; at < 16; at += 4 {
		v.updateInt(binary.BigEndian.Uint32(data[at:]))
		v.charge(1)
	}
	v.crc = crc32.Update(v.crc, crc32.IEEETable, identity[:])
	v.charge(32)
	for at := 48; at < 68; at += 4 {
		v.updateInt(binary.BigEndian.Uint32(data[at:]))
		v.charge(1)
	}
	v.updateLong(uint64(info.RetainedBytes))
	v.charge(1)
	for p, n := range info.UniqueStringCounts {
		r := index.regions[p]
		previous := int64(-1)
		v.array(r.strings, int(n), 4, func(id int64) { v.require(id >= 0 && id < int64(info.StringCount) && id > previous); previous = id })
		previous = 0
		v.array(r.ends, int(n), 4, func(end int64) { v.require(end > previous && end <= int64(info.CallSiteCount)); previous = end })
		v.require(previous == int64(info.CallSiteCount))
		v.array(r.nodes, int(info.CallSiteCount), 4, func(id int64) { v.require(id >= 0 && id < int64(capacity)) })
	}
	v.array(index.signatures, int(info.StringCount), 8, func(int64) {})
	previous := int64(math.MinInt64)
	v.array(index.trigrams, int(info.TrigramPostingCount), 8, func(posting int64) {
		v.require(posting >= previous && int32(posting) >= 0 && int32(posting) < info.StringCount)
		previous = posting
	})
	v.require(uint64(v.crc) == binary.BigEndian.Uint64(data[len(data)-8:]))
	index.mainRanges = &mainPostingRangeCache{}
	return index, nil
}
