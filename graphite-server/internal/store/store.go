package store

import (
	"bufio"
	"bytes"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

var ErrNodeNotFound = errors.New("node not found")

type nodeLocation struct {
	offset int64
	tag    byte
}

// Store owns an open node-data file. Its read methods are safe for concurrent
// callers until Close. Public tables are read-only by convention. Resource and
// class-overview records are loaded lazily when their accessors are called.
type Store struct {
	dir                string
	callSiteIndex      callSiteIndexState
	candidateProof     candidateCertificateState
	trigramProof       candidateCertificateState
	overview           lazyClassOverview
	Mode               string
	mappedData         []byte
	eagerNodes         map[int32]Node
	resources          lazyResources
	EdgeCount          int64
	NodeSpan           int32
	outgoing, incoming map[int32][]Edge
	Strings            []string
	Metadata           Metadata
	Comparisons        map[uint64]Comparison
	NodeCount          int
	FormatVersion      int
	file               *os.File
	size               int64
	ids                []int32
	locations          map[int32]nodeLocation
	byKind             map[string][]int32
}

func Open(dir string) (*Store, error) { return OpenMode(dir, "MAPPED") }

// OpenMode matches GraphStore's node-storage modes. AUTO selects EAGER below
// one million node records and MAPPED at or above that threshold.
func OpenMode(dir, mode string) (*Store, error) {
	mode = strings.ToUpper(mode)
	if mode != "MAPPED" && mode != "EAGER" && mode != "AUTO" {
		return nil, fmt.Errorf("unsupported graph load mode %q", mode)
	}
	table, err := LoadStrings(filepath.Join(dir, "graph.strings"))
	if err != nil {
		return nil, fmt.Errorf("graph.strings: %w", err)
	}
	file, err := os.Open(filepath.Join(dir, "graph.nodedata"))
	if err != nil {
		return nil, err
	}
	s := &Store{dir: dir, overview: lazyClassOverview{dir: dir}, resources: lazyResources{dir: dir}, Strings: table, file: file, locations: map[int32]nodeLocation{}, byKind: map[string][]int32{}}
	fail := func(err error) (*Store, error) { s.Close(); return nil, err }
	stat, err := file.Stat()
	if err != nil {
		return fail(err)
	}
	s.size = stat.Size()
	d := newDecoder(io.NewSectionReader(file, 0, s.size), s.size, table)
	d.header(0x47524e00)
	s.NodeCount = d.count()
	s.FormatVersion = d.version
	if d.err != nil {
		return fail(d.err)
	}
	s.Mode = resolveLoadMode(mode, s.NodeCount)
	if s.Mode == "MAPPED" {
		s.mappedData, err = mapNodeData(file, s.size)
		if err != nil {
			return fail(err)
		}
	}
	if s.Mode == "MAPPED" {
		err = decodeFile(filepath.Join(dir, "graph.nodeindex"), nil, func(d *decoder) {
			d.header(0x47524900)
			count := d.count()
			if count != s.NodeCount {
				d.fail("node index count %d differs from data count %d", count, s.NodeCount)
			}
			for i := 0; i < count && d.err == nil; i++ {
				id := d.i32()
				tag := d.u8()
				offset := d.i64()
				if id < 0 || int(tag) >= len(nodeKinds) || offset < 8 || offset >= s.size {
					d.fail("invalid node index record id=%d tag=%d offset=%d", id, tag, offset)
					break
				}
				if _, ok := s.locations[id]; ok {
					d.fail("duplicate node id %d", id)
					break
				}
				s.ids = append(s.ids, id)
				s.locations[id] = nodeLocation{offset, tag}
				s.byKind[nodeKinds[tag]] = append(s.byKind[nodeKinds[tag]], id)
			}
		})
		if errors.Is(err, os.ErrNotExist) { // Older stores may omit the optional index.
			d = newDecoder(io.NewSectionReader(file, 8, s.size-8), s.size-8, table)
			d.version = s.FormatVersion
			for i := 0; i < s.NodeCount && d.err == nil; i++ {
				offset := s.size - d.remaining
				n := d.node()
				if n.ID < 0 {
					d.fail("negative node id %d", n.ID)
				}
				if _, ok := s.locations[n.ID]; ok {
					d.fail("duplicate node id %d", n.ID)
				}
				if d.err != nil {
					break
				}
				var tag byte
				for i, k := range nodeKinds {
					if k == n.Kind {
						tag = byte(i)
					}
				}
				s.ids = append(s.ids, n.ID)
				s.locations[n.ID] = nodeLocation{offset, tag}
				s.byKind[n.Kind] = append(s.byKind[n.Kind], n.ID)
			}
			err = d.err
			if err == nil && d.remaining != 0 {
				err = fmt.Errorf("trailing node data bytes")
			}
		}
		if err != nil {
			return fail(fmt.Errorf("graph.nodeindex: %w", err))
		}
	}
	err = decodeFile(filepath.Join(dir, "graph.metadata"), table, func(d *decoder) { s.Metadata = d.metadata() })
	if err != nil {
		return fail(fmt.Errorf("graph.metadata: %w", err))
	}
	s.Comparisons = map[uint64]Comparison{}
	err = decodeFile(filepath.Join(dir, "graph.comparisons"), nil, func(d *decoder) {
		d.header(0x47524300)
		count := d.count()
		for i := 0; i < count; i++ {
			key := uint64(d.i64())
			s.Comparisons[key] = d.comparison()
		}
	})
	if err != nil {
		return fail(fmt.Errorf("graph.comparisons: %w", err))
	}
	if s.Mode == "EAGER" {
		s.eagerNodes = make(map[int32]Node, s.NodeCount)
		stream := newDecoder(bufio.NewReader(io.NewSectionReader(file, 8, s.size-8)), s.size-8, table)
		stream.version = s.FormatVersion
		for i := 0; i < s.NodeCount && stream.err == nil; i++ {
			offset := s.size - stream.remaining
			n := stream.node()
			if _, exists := s.eagerNodes[n.ID]; exists {
				stream.fail("duplicate node id %d", n.ID)
			}
			if n.ID < 0 {
				stream.fail("negative node id %d", n.ID)
			}
			s.ids = append(s.ids, n.ID)
			s.byKind[n.Kind] = append(s.byKind[n.Kind], n.ID)
			s.eagerNodes[n.ID] = n
			var tag byte
			for i, k := range nodeKinds {
				if k == n.Kind {
					tag = byte(i)
					break
				}
			}
			s.locations[n.ID] = nodeLocation{offset, tag}
		}
		if stream.err != nil {
			return fail(stream.err)
		}
		if stream.remaining != 0 {
			return fail(fmt.Errorf("trailing node data bytes"))
		}
	}
	if err := s.loadEdges(dir); err != nil {
		return fail(fmt.Errorf("forward adjacency: %w", err))
	}
	if s.Mode == "EAGER" {
		if err := s.file.Close(); err != nil {
			return fail(err)
		}
		s.file = nil
	}
	return s, nil
}
func decodeFile(path string, table []string, f func(*decoder)) error {
	file, err := os.Open(path)
	if err != nil {
		return err
	}
	defer file.Close()
	stat, err := file.Stat()
	if err != nil {
		return err
	}
	d := newDecoder(bufio.NewReader(file), stat.Size(), table)
	f(d)
	if d.err != nil {
		return d.err
	}
	if d.remaining != 0 {
		return fmt.Errorf("trailing bytes: %d", d.remaining)
	}
	return nil
}
func resolveLoadMode(mode string, nodeCount int) string {
	if mode == "AUTO" {
		if nodeCount < 1_000_000 {
			return "EAGER"
		}
		return "MAPPED"
	}
	return mode
}
func (s *Store) Close() error {
	st := &s.callSiteIndex
	st.mu.Lock()
	if st.closed {
		pending := st.loading
		st.mu.Unlock()
		if pending != nil {
			<-pending
		}
		return nil
	}
	st.closed = true
	if st.closing != nil {
		close(st.closing)
	}
	var err error
	if st.view != nil {
		err = unmapNodeData(st.view.data)
		st.view.data = nil
	}
	if s.mappedData != nil {
		err = errors.Join(err, unmapNodeData(s.mappedData))
		s.mappedData = nil
	}
	if s.file != nil {
		err = errors.Join(err, s.file.Close())
		s.file = nil
	}
	pending := st.loading
	st.mu.Unlock()
	if pending != nil {
		<-pending
	}
	return err
}
func (s *Store) NodeIDs() []int32                { return append([]int32(nil), s.ids...) }
func (s *Store) NodesOfKind(kind string) []int32 { return append([]int32(nil), s.byKind[kind]...) }
func (s *Store) Node(id int32) (Node, error) {
	if s.eagerNodes != nil {
		n, ok := s.eagerNodes[id]
		if !ok {
			return Node{}, fmt.Errorf("%w: %d", ErrNodeNotFound, id)
		}
		return n, nil
	}
	loc, ok := s.locations[id]
	if !ok {
		return Node{}, fmt.Errorf("%w: %d", ErrNodeNotFound, id)
	}
	var reader io.Reader
	if s.mappedData != nil {
		reader = bytes.NewReader(s.mappedData[loc.offset:])
	} else {
		reader = bufio.NewReader(io.NewSectionReader(s.file, loc.offset, s.size-loc.offset))
	}
	d := newDecoder(reader, s.size-loc.offset, s.Strings)
	d.version = s.FormatVersion
	n := d.node()
	if d.err != nil {
		return Node{}, fmt.Errorf("node %d: %w", id, d.err)
	}
	if n.ID != id || n.Kind != nodeKinds[loc.tag] {
		return Node{}, fmt.Errorf("node index mismatch for %d", id)
	}
	return n, nil
}
