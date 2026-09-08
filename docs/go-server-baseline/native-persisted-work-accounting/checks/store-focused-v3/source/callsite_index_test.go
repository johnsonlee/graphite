package store

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"hash/crc32"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
)

const indexFixture = "testdata/callsite-index/store"

func copyIndexFixture(t *testing.T) string {
	t.Helper()
	dst := t.TempDir()
	entries, err := os.ReadDir(indexFixture)
	if err != nil {
		t.Fatal(err)
	}
	for _, entry := range entries {
		b, err := os.ReadFile(filepath.Join(indexFixture, entry.Name()))
		if err != nil {
			t.Fatal(err)
		}
		if err = os.WriteFile(filepath.Join(dst, entry.Name()), b, 0600); err != nil {
			t.Fatal(err)
		}
	}
	return dst
}
func openIndexFixture(t *testing.T, path, mode string) *Store {
	t.Helper()
	s, err := OpenMode(path, mode)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}
func requireIndex(t *testing.T, s *Store) *CallSiteStringIndex {
	t.Helper()
	v, ok, err := s.TryCallSiteStringIndex(context.Background())
	if err != nil || !ok || v == nil {
		t.Fatalf("index available=%v err=%v reason=%s", ok, err, s.CallSiteStringIndexUnavailableReason())
	}
	return v
}
func TestMainCallSiteStringIndex(t *testing.T) {
	var expected struct {
		Nodes []struct {
			ID        int32
			StringIDs [4]int32
		}
		Properties        [4]map[string][]int32
		MainAcceptedIndex bool
		Signatures        []string
		Trigrams          map[string][]int32
		CRC32             string
	}
	b, err := os.ReadFile("testdata/callsite-index/expected.json")
	if err != nil {
		t.Fatal(err)
	}
	if err = json.Unmarshal(b, &expected); err != nil {
		t.Fatal(err)
	}
	if !expected.MainAcceptedIndex {
		t.Fatal("main did not load the sidecar")
	}
	data, err := os.ReadFile(filepath.Join(indexFixture, callSiteIndexFile))
	if err != nil {
		t.Fatal(err)
	}
	footer := binary.BigEndian.Uint64(data[len(data)-8:])
	if strconv.FormatUint(footer, 16) != expected.CRC32 || uint64(crc32.ChecksumIEEE(data[:len(data)-8])) == footer {
		t.Fatal("main's numeric checksum must match oracle and differ from raw-file CRC")
	}
	for _, mode := range []string{"MAPPED", "EAGER"} {
		t.Run(mode, func(t *testing.T) {
			s := openIndexFixture(t, indexFixture, mode)
			v := requireIndex(t, s)
			info, err := v.Info(context.Background())
			if err != nil {
				t.Fatal(err)
			}
			if info.Version != 2 || info.CallSiteCount != 4 || info.StringCount != int32(len(s.Strings)) {
				t.Fatalf("header = %+v", info)
			}
			for id, want := range expected.Signatures {
				got, err := v.Signature(context.Background(), int32(id))
				if err != nil || strconv.FormatUint(got, 16) != want {
					t.Fatalf("signature %d: %x err=%v want=%s", id, got, err, want)
				}
			}
			total := 0
			for key, want := range expected.Trigrams {
				hash, err := strconv.ParseInt(key, 10, 32)
				if err != nil {
					t.Fatal(err)
				}
				got, err := v.TrigramStringIDs(context.Background(), int32(hash))
				if err != nil || !reflect.DeepEqual(got, want) {
					t.Fatalf("trigram %s: %v err=%v want=%v", key, got, err, want)
				}
				total += len(got)
			}
			if int32(total) != info.TrigramPostingCount {
				t.Fatalf("trigram count %d want=%d", total, info.TrigramPostingCount)
			}
			previous := int64(-1)
			for _, node := range expected.Nodes {
				raw, err := s.RawCallSiteStringIDs(context.Background(), node.ID)
				if err != nil {
					t.Fatal(err)
				}
				if raw.StringIDs != node.StringIDs || raw.Offset <= previous {
					t.Fatalf("node %d raw=%+v expected IDs=%v", node.ID, raw, node.StringIDs)
				}
				previous = raw.Offset
			}
			for p, property := range expected.Properties {
				directory, err := v.Directory(context.Background(), CallSiteStringProperty(p))
				if err != nil {
					t.Fatal(err)
				}
				want := []CallSiteStringDirectoryEntry{}
				for k, ids := range property {
					sid, _ := strconv.Atoi(k)
					want = append(want, CallSiteStringDirectoryEntry{int32(sid), int32(len(ids))})
					got, err := v.Postings(context.Background(), CallSiteStringProperty(p), int32(sid))
					if err != nil || !reflect.DeepEqual(got, ids) {
						t.Fatalf("p=%d string=%d: %v err=%v want=%v", p, sid, got, err, ids)
					}
				}
				sort.Slice(want, func(i, j int) bool { return want[i].StringID < want[j].StringID })
				if !reflect.DeepEqual(directory, want) {
					t.Fatalf("directory %d got=%v want=%v", p, directory, want)
				}
			}
			// 'inv' appears only in string ID 5 (invoke). Trigram hits are string IDs,
			// not proof of matching the entire predicate or a particular property.
			trigram := (int32('i')*31+int32('n'))*31 + int32('v')
			ids, err := v.TrigramStringIDs(context.Background(), trigram)
			if err != nil || !reflect.DeepEqual(ids, []int32{5}) {
				t.Fatalf("inv trigram %v %v", ids, err)
			}
			signature, err := v.Signature(context.Background(), 5)
			if err != nil || signature == 0 {
				t.Fatalf("invoke signature %x %v", signature, err)
			}
			missing, err := v.Postings(context.Background(), CallerClass, -1)
			if err != nil || len(missing) != 0 {
				t.Fatalf("missing row %v %v", missing, err)
			}
		})
	}
}
func TestCallSiteIndexDerivesMissingIdentity(t *testing.T) {
	for _, mode := range []string{"MAPPED", "EAGER"} {
		for _, stringsToo := range []bool{false, true} {
			t.Run(mode+strconv.FormatBool(stringsToo), func(t *testing.T) {
				dir := copyIndexFixture(t)
				if err := os.Remove(filepath.Join(dir, "graph.callsite-string-content.identity")); err != nil {
					t.Fatal(err)
				}
				if stringsToo {
					if err := os.WriteFile(filepath.Join(dir, "graph.strings.identity"), []byte{1, 2}, 0600); err != nil {
						t.Fatal(err)
					}
				}
				s := openIndexFixture(t, dir, mode)
				requireIndex(t, s)
				if !strings.Contains(s.Strings[3], "\xed\xa0\x80") {
					t.Fatal("oracle must exercise an isolated surrogate in semantic UTF8 hashing")
				}
			})
		}
	}
}
func fixIndexCRC(b []byte) {
	info := CallSiteStringIndexInfo{CallSiteCount: int32(binary.BigEndian.Uint32(b[12:16]))}
	for p := range info.UniqueStringCounts {
		info.UniqueStringCounts[p] = int32(binary.BigEndian.Uint32(b[48+p*4 : 52+p*4]))
	}
	value, err := callSiteIndexCRC(context.Background(), nil, b, info)
	if err != nil {
		panic(err)
	}
	binary.BigEndian.PutUint64(b[len(b)-8:], uint64(value))
}
func indexRegions(b []byte) [4]callSiteIndexRegion {
	var regions [4]callSiteIndexRegion
	at := 76
	calls := int(binary.BigEndian.Uint32(b[12:16]))
	for p := range regions {
		n := int(binary.BigEndian.Uint32(b[48+p*4 : 52+p*4]))
		regions[p] = callSiteIndexRegion{at, at + 4*n, at + 8*n}
		at += 8*n + 4*calls
	}
	return regions
}
func TestCallSiteIndexOptionalCorruption(t *testing.T) {
	cases := map[string]func([]byte) []byte{
		"crc":             func(b []byte) []byte { b[len(b)-1] ^= 1; return b },
		"version":         func(b []byte) []byte { binary.BigEndian.PutUint32(b[4:8], 99); return b },
		"truncated":       func(b []byte) []byte { return b[:len(b)-1] },
		"trailing":        func(b []byte) []byte { return append(b, 0) },
		"identity":        func(b []byte) []byte { b[16] ^= 1; fixIndexCRC(b); return b },
		"header-overflow": func(b []byte) []byte { binary.BigEndian.PutUint32(b[64:68], 0x7fffffff); fixIndexCRC(b); return b },
		"retained":        func(b []byte) []byte { b[75] ^= 1; fixIndexCRC(b); return b },
		"duplicate-offset": func(b []byte) []byte {
			at := indexRegions(b)[2].nodes
			copy(b[at+4:at+8], b[at:at+4])
			fixIndexCRC(b)
			return b
		},
		"missing-node": func(b []byte) []byte {
			at := indexRegions(b)[0].nodes
			binary.BigEndian.PutUint32(b[at:at+4], 89)
			fixIndexCRC(b)
			return b
		},
		"bad-directory": func(b []byte) []byte {
			at := indexRegions(b)[0].strings
			copy(b[at+4:at+8], b[at:at+4])
			fixIndexCRC(b)
			return b
		},
		"bad-ends": func(b []byte) []byte {
			at := indexRegions(b)[0].ends
			binary.BigEndian.PutUint32(b[at:at+4], 0)
			fixIndexCRC(b)
			return b
		},
		"bad-trigram": func(b []byte) []byte {
			at := len(b) - 16
			binary.BigEndian.PutUint32(b[at+4:at+8], 0x7fffffff)
			fixIndexCRC(b)
			return b
		},
	}
	for name, mutate := range cases {
		t.Run(name, func(t *testing.T) {
			dir := copyIndexFixture(t)
			path := filepath.Join(dir, callSiteIndexFile)
			b, err := os.ReadFile(path)
			if err != nil {
				t.Fatal(err)
			}
			if err = os.WriteFile(path, mutate(b), 0600); err != nil {
				t.Fatal(err)
			}
			s := openIndexFixture(t, dir, "MAPPED")
			v, ok, err := s.TryCallSiteStringIndex(context.Background())
			if v != nil || ok || err != nil || s.CallSiteStringIndexUnavailableReason() == "" {
				t.Fatalf("corruption available=%v err=%v reason=%s", ok, err, s.CallSiteStringIndexUnavailableReason())
			}
			n, err := s.Node(17)
			if err != nil || n.ID != 17 {
				t.Fatalf("optional index damaged normal graph: %v %v", n, err)
			}
		})
	}
}
func TestCallSiteIndexMissingCanAppear(t *testing.T) {
	dir := copyIndexFixture(t)
	path := filepath.Join(dir, callSiteIndexFile)
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if err = os.Remove(path); err != nil {
		t.Fatal(err)
	}
	s := openIndexFixture(t, dir, "MAPPED")
	v, ok, err := s.TryCallSiteStringIndex(context.Background())
	if v != nil || ok || err != nil {
		t.Fatalf("missing %v %v", ok, err)
	}
	if err = os.WriteFile(path, b, 0600); err != nil {
		t.Fatal(err)
	}
	requireIndex(t, s)
}
func TestCallSiteIndexIdentityUsesRawOffsetsBeforeCoreConsumption(t *testing.T) {
	dir := copyIndexFixture(t)
	s := openIndexFixture(t, dir, "MAPPED")
	offset := s.locations[17].offset
	s.Close()
	path := filepath.Join(dir, "graph.nodedata")
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	binary.BigEndian.PutUint32(b[int(offset)+13:int(offset)+17], 0xffffffff)
	if err = os.WriteFile(path, b, 0600); err != nil {
		t.Fatal(err)
	}
	if err = os.Remove(filepath.Join(dir, "graph.callsite-string-content.identity")); err != nil {
		t.Fatal(err)
	}
	s = openIndexFixture(t, dir, "MAPPED")
	_, ok, err := s.TryCallSiteStringIndex(context.Background())
	if ok || err != nil {
		t.Fatalf("changed raw identity available=%v err=%v", ok, err)
	}
	if !s.callSiteIndex.unavailable || s.callSiteIndex.view != nil {
		t.Fatal("identity mismatch must reject optional persisted index")
	}
	if _, err := s.RawCallSiteStringIDs(context.Background(), 17); !errors.Is(err, ErrInvalidGraphData) {
		t.Fatalf("strict core consumption=%v", err)
	}
}

// Inject cancellation at a deterministic poll, without sleeps or test-only
// production hooks. Later attempts must still be able to validate the index.
type cancelAtPoll struct {
	context.Context
	cancel context.CancelFunc
	count  atomic.Int32
	at     int32
}

func (c *cancelAtPoll) Err() error {
	if c.count.Add(1) == c.at {
		c.cancel()
	}
	return c.Context.Err()
}
func TestCallSiteIndexCancellationDoesNotPublish(t *testing.T) {
	for _, at := range []int32{1, 3, 5, 7, 10} {
		t.Run(strconv.Itoa(int(at)), func(t *testing.T) {
			s := openIndexFixture(t, indexFixture, "MAPPED")
			base, cancel := context.WithCancel(context.Background())
			defer cancel()
			ctx := &cancelAtPoll{Context: base, cancel: cancel, at: at}
			v, ok, err := s.TryCallSiteStringIndex(ctx)
			if v != nil || ok || !errors.Is(err, context.Canceled) {
				t.Fatalf("poll %d: available=%v err=%v polls=%d", at, ok, err, ctx.count.Load())
			}
			if s.callSiteIndex.view != nil || s.callSiteIndex.unavailable {
				t.Fatal("cancelled attempt published or poisoned cache")
			}
			requireIndex(t, s)
		})
	}
}

type pausePoll struct {
	context.Context
	count            atomic.Int32
	entered, release chan struct{}
}

func (c *pausePoll) Err() error {
	if c.count.Add(1) == 3 {
		close(c.entered)
		<-c.release
	}
	return c.Context.Err()
}
func TestCallSiteIndexCloseDuringFirstLoad(t *testing.T) {
	s := openIndexFixture(t, indexFixture, "MAPPED")
	ctx := &pausePoll{Context: context.Background(), entered: make(chan struct{}), release: make(chan struct{})}
	result := make(chan error, 1)
	go func() { _, _, err := s.TryCallSiteStringIndex(ctx); result <- err }()
	<-ctx.entered
	closed := make(chan error, 1)
	go func() { closed <- s.Close() }()
	<-s.callSiteIndex.closing
	select {
	case <-closed:
		t.Fatal("Close returned before in-flight first load finished")
	default:
	}
	close(ctx.release)
	if err := <-result; !errors.Is(err, ErrStoreClosed) {
		t.Fatalf("load raced Close: %v", err)
	}
	if err := <-closed; err != nil {
		t.Fatal(err)
	}
	if _, _, err := s.TryCallSiteStringIndex(context.Background()); !errors.Is(err, ErrStoreClosed) {
		t.Fatal(err)
	}
}
func TestCallSiteIndexConcurrentReadAndClose(t *testing.T) {
	s := openIndexFixture(t, indexFixture, "MAPPED")
	const readers = 24
	views := make(chan *CallSiteStringIndex, readers)
	errs := make(chan error, readers)
	var group sync.WaitGroup
	for i := 0; i < readers; i++ {
		group.Add(1)
		go func() {
			defer group.Done()
			v, ok, err := s.TryCallSiteStringIndex(context.Background())
			if err != nil {
				errs <- err
				return
			}
			if !ok {
				errs <- errors.New("unavailable")
				return
			}
			views <- v
		}()
	}
	group.Wait()
	close(views)
	close(errs)
	for err := range errs {
		t.Fatal(err)
	}
	var v *CallSiteStringIndex
	for current := range views {
		if v != nil && v != current {
			t.Fatal("published multiple views")
		}
		v = current
	}
	saved, err := v.Postings(context.Background(), CalleeClass, 2)
	if err != nil {
		t.Fatal(err)
	}
	start := make(chan struct{})
	for i := 0; i < readers; i++ {
		group.Add(1)
		go func() {
			defer group.Done()
			<-start
			for j := 0; j < 20; j++ {
				_, err := v.Postings(context.Background(), CalleeClass, 2)
				if err != nil && !errors.Is(err, ErrStoreClosed) {
					t.Errorf("concurrent read: %v", err)
				}
				_, err = s.RawCallSiteStringIDs(context.Background(), 17)
				if err != nil && !errors.Is(err, ErrStoreClosed) {
					t.Errorf("concurrent raw: %v", err)
				}
			}
		}()
	}
	close(start)
	if err = s.Close(); err != nil {
		t.Fatal(err)
	}
	group.Wait()
	if !reflect.DeepEqual(saved, []int32{17, 2, 41, 90}) {
		t.Fatal("returned copy changed after munmap", saved)
	}
	if _, err = v.Directory(context.Background(), CallerName); !errors.Is(err, ErrStoreClosed) {
		t.Fatalf("closed view: %v", err)
	}
	if _, err = v.Info(context.Background()); !errors.Is(err, ErrStoreClosed) {
		t.Fatal(err)
	}
	if _, err = v.Signature(context.Background(), 0); !errors.Is(err, ErrStoreClosed) {
		t.Fatal(err)
	}
	if _, err = v.TrigramStringIDs(context.Background(), 0); !errors.Is(err, ErrStoreClosed) {
		t.Fatal(err)
	}
}

func TestCallSiteIndexCancellationDuringIdentityDerivation(t *testing.T) {
	dir := copyIndexFixture(t)
	for _, name := range []string{"graph.callsite-string-content.identity", "graph.strings.identity"} {
		if err := os.Remove(filepath.Join(dir, name)); err != nil {
			t.Fatal(err)
		}
	}
	s := openIndexFixture(t, dir, "MAPPED")
	base, cancel := context.WithCancel(context.Background())
	defer cancel()
	ctx := &cancelAtPoll{Context: base, cancel: cancel, at: 8}
	if _, ok, err := s.TryCallSiteStringIndex(ctx); ok || !errors.Is(err, context.Canceled) {
		t.Fatalf("identity cancellation available=%v err=%v", ok, err)
	}
	if s.callSiteIndex.view != nil || s.callSiteIndex.unavailable {
		t.Fatal("identity cancellation poisoned state")
	}
	requireIndex(t, s)
}

type signalSecondPoll struct {
	context.Context
	count   atomic.Int32
	entered chan struct{}
}

func (c *signalSecondPoll) Err() error {
	if c.count.Add(1) == 2 {
		close(c.entered)
	}
	return c.Context.Err()
}
func TestCallSiteIndexWaitingCallerCanCancel(t *testing.T) {
	s := openIndexFixture(t, indexFixture, "MAPPED")
	first := &pausePoll{Context: context.Background(), entered: make(chan struct{}), release: make(chan struct{})}
	loaded := make(chan error, 1)
	go func() { _, _, err := s.TryCallSiteStringIndex(first); loaded <- err }()
	<-first.entered
	base, cancel := context.WithCancel(context.Background())
	defer cancel()
	second := &signalSecondPoll{Context: base, entered: make(chan struct{})}
	waited := make(chan error, 1)
	go func() { _, _, err := s.TryCallSiteStringIndex(second); waited <- err }()
	<-second.entered
	cancel()
	if err := <-waited; !errors.Is(err, context.Canceled) {
		t.Fatalf("waiting cancellation=%v", err)
	}
	close(first.release)
	if err := <-loaded; err != nil {
		t.Fatal(err)
	}
	requireIndex(t, s)
}
