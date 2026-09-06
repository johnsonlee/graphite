package store

import (
	"context"
	"crypto/sha256"
	"encoding/binary"
	"fmt"
	"io"
	"math"
	"os"
	"path/filepath"

	"github.com/johnsonlee/graphite/graphite-server/internal/javastring"
)

type RawCallSiteStrings struct {
	StringIDs [4]int32
	Offset    int64
}

// RawCallSiteStringIDs reads the four serialized string-table references without
// constructing a Node or MethodDescriptor. It works in MAPPED and EAGER modes.
func (s *Store) RawCallSiteStringIDs(ctx context.Context, nodeID int32) (RawCallSiteStrings, error) {
	if err := ctx.Err(); err != nil {
		return RawCallSiteStrings{}, err
	}
	s.callSiteIndex.mu.RLock()
	defer s.callSiteIndex.mu.RUnlock()
	if s.callSiteIndex.closed {
		return RawCallSiteStrings{}, ErrStoreClosed
	}
	if err := ctx.Err(); err != nil {
		return RawCallSiteStrings{}, err
	}
	loc, ok := s.locations[nodeID]
	if !ok {
		return RawCallSiteStrings{}, fmt.Errorf("%w: %d", ErrNodeNotFound, nodeID)
	}
	if nodeKinds[loc.tag] != "CallSiteNode" {
		return RawCallSiteStrings{}, fmt.Errorf("node %d is not a CallSiteNode", nodeID)
	}
	fail := func(reason string) (RawCallSiteStrings, error) {
		return RawCallSiteStrings{}, fmt.Errorf("%w: CallSite %d: %s", ErrInvalidGraphData, nodeID, reason)
	}
	file := s.file
	if s.mappedData == nil && file == nil {
		var err error
		file, err = os.Open(filepath.Join(s.dir, "graph.nodedata"))
		if err != nil {
			return fail(err.Error())
		}
		defer file.Close()
	}
	read := func(at int64, dst []byte) error {
		if at < 8 || at > s.size-int64(len(dst)) {
			return fmt.Errorf("field outside node data")
		}
		if s.mappedData != nil {
			copy(dst, s.mappedData[int(at):int(at)+len(dst)])
			return nil
		}
		if file == nil {
			return fmt.Errorf("node data file unavailable")
		}
		_, err := file.ReadAt(dst, at)
		return err
	}
	var caller [17]byte
	if err := read(loc.offset, caller[:]); err != nil {
		return fail(err.Error())
	}
	if int32(binary.BigEndian.Uint32(caller[:4])) != nodeID || caller[4] != 12 {
		return fail("node header differs from index")
	}
	count := int32(binary.BigEndian.Uint32(caller[13:17]))
	if count < 0 {
		return fail("negative caller parameter count")
	}
	calleeOffset := loc.offset + 5 + (4+int64(count))*4
	var callee [8]byte
	if err := read(calleeOffset, callee[:]); err != nil {
		return fail(err.Error())
	}
	out := RawCallSiteStrings{Offset: loc.offset, StringIDs: [4]int32{int32(binary.BigEndian.Uint32(caller[5:9])), int32(binary.BigEndian.Uint32(caller[9:13])), int32(binary.BigEndian.Uint32(callee[:4])), int32(binary.BigEndian.Uint32(callee[4:]))}}
	for _, id := range out.StringIDs {
		if id < 0 || int64(id) >= int64(len(s.Strings)) {
			return fail("string ID outside table")
		}
	}
	return out, nil
}

func readIdentity(path string) (identity [32]byte, ok bool) {
	f, err := os.Open(path)
	if err != nil {
		return identity, false
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil || st.Size() != 32 || !st.Mode().IsRegular() {
		return identity, false
	}
	_, err = io.ReadFull(f, identity[:])
	return identity, err == nil
}
func (s *Store) callSiteContentIdentity(ctx context.Context, closing <-chan struct{}) ([32]byte, error) {
	if err := indexCheck(ctx, closing); err != nil {
		return [32]byte{}, err
	}
	if id, ok := readIdentity(filepath.Join(s.dir, "graph.callsite-string-content.identity")); ok {
		return id, nil
	}
	stringsID, ok := readIdentity(filepath.Join(s.dir, "graph.strings.identity"))
	var word [8]byte
	if !ok {
		digest := sha256.New()
		binary.BigEndian.PutUint32(word[:4], uint32(len(s.Strings)))
		digest.Write(word[:4])
		for _, s := range s.Strings {
			if err := indexCheck(ctx, closing); err != nil {
				return [32]byte{}, err
			}
			b := []byte(javastring.WireString(s))
			if len(b) > math.MaxInt32 {
				return [32]byte{}, fmt.Errorf("%w: string exceeds Java byte length", ErrInvalidGraphData)
			}
			binary.BigEndian.PutUint32(word[:4], uint32(len(b)))
			digest.Write(word[:4])
			digest.Write(b)
		}
		copy(stringsID[:], digest.Sum(nil))
	}
	digest := sha256.New()
	digest.Write(stringsID[:])
	ids := s.byKind["CallSiteNode"]
	binary.BigEndian.PutUint32(word[:4], uint32(len(ids)))
	digest.Write(word[:4])
	for _, nodeID := range ids {
		if err := indexCheck(ctx, closing); err != nil {
			return [32]byte{}, err
		}
		raw, err := s.RawCallSiteStringIDs(ctx, nodeID)
		if err != nil {
			return [32]byte{}, err
		}
		binary.BigEndian.PutUint32(word[:4], uint32(nodeID))
		digest.Write(word[:4])
		binary.BigEndian.PutUint64(word[:], uint64(raw.Offset))
		digest.Write(word[:])
		for _, id := range raw.StringIDs {
			binary.BigEndian.PutUint32(word[:4], uint32(id))
			digest.Write(word[:4])
		}
	}
	var result [32]byte
	copy(result[:], digest.Sum(nil))
	return result, nil
}
