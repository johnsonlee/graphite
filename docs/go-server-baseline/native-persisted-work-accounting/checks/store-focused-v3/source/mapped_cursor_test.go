package store

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"math"
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

type auditReader struct {
	data  []byte
	calls int
}

func (r *auditReader) Read(p []byte) (int, error) {
	r.calls++
	if len(r.data) == 0 {
		return 0, io.EOF
	}
	n := copy(p, r.data)
	r.data = r.data[n:]
	return n, nil
}
func TestDecoderAuditDeclaredBoundsAndStickyFirstError(t *testing.T) {
	r := &auditReader{data: []byte{1, 2, 3, 4}}
	d := newDecoder(r, 3, nil)
	if got := d.i32(); got != 0 || d.err.Error() != "truncated data: need 4 bytes, have 3" || r.calls != 0 || d.remaining != 3 {
		t.Fatalf("value=%d err=%v calls=%d remain=%d", got, d.err, r.calls, d.remaining)
	}
	first := d.err
	d.fail("secondary")
	if d.bytes(1) != nil || d.err != first || d.remaining != 3 {
		t.Fatal("first error or cursor changed")
	}
}
func TestDecoderAuditReaderShortReadDiffersFromDeclaredBounds(t *testing.T) {
	d := newDecoder(bytes.NewReader([]byte{1, 2}), 4, nil)
	b := d.bytes(4)
	if !errors.Is(d.err, io.ErrUnexpectedEOF) || !bytes.Equal(b, []byte{1, 2, 0, 0}) || d.remaining != 0 {
		t.Fatalf("bytes=%v err=%v remain=%d", b, d.err, d.remaining)
	}
	e := newDecoder(bytes.NewReader(nil), 4, nil)
	e.i32()
	if !errors.Is(e.err, io.EOF) {
		t.Fatal(e.err)
	}
}
func TestDecoderAuditReturnedBytesAreIndependent(t *testing.T) {
	input := []byte{1, 2, 3, 4}
	d := newDecoder(bytes.NewReader(input), 4, nil)
	empty := d.bytes(0)
	a := d.bytes(2)
	b := d.bytes(2)
	input[0] = 9
	b[0] = 8
	if empty == nil || !bytes.Equal(a, []byte{1, 2}) {
		t.Fatalf("empty=%v a=%v b=%v", empty, a, b)
	}
}
func TestDecoderAuditNodeSuffixHasNoNeighborBoundary(t *testing.T) {
	// Node1's value overlaps node2's id. Node() currently decodes until EOF,
	// not until the next indexed record's offset.
	data := []byte{0, 0, 0, 1, 0, 0, 0, 0, 42, 6}
	s := &Store{mappedData: data, size: int64(len(data)), locations: map[int32]nodeLocation{1: {0, 0}, 42: {5, 6}}}
	n, err := s.Node(1)
	if err != nil || n.Value != int32(42) {
		t.Fatalf("node=%#v err=%v", n, err)
	}
	d := newDecoder(bytes.NewReader(data[:5]), 5, nil)
	d.node()
	if d.err == nil {
		t.Fatal("tight neighbor-bound did not alter behavior")
	}
}
func TestDecoderAuditCompleteCallSiteTailAndTypedTag(t *testing.T) {
	data := make([]byte, 49)
	data[3] = 7
	data[4] = 12
	binary.BigEndian.PutUint32(data[37:], 0xffffffff)
	binary.BigEndian.PutUint32(data[41:], 0xffffffff)
	binary.BigEndian.PutUint32(data[45:], 0xffffffff)
	d := newDecoder(bytes.NewReader(data), 49, []string{"ok"})
	n := d.node()
	if n.Caller.Name != "ok" || n.Callee.Name != "ok" || d.err == nil || d.err.Error() != "invalid collection count -1 (0 bytes remain)" || d.remaining != 0 {
		t.Fatalf("node=%#v err=%v remain=%d", n, d.err, d.remaining)
	}
	tag := newDecoder(bytes.NewReader([]byte{0, 0, 0, 7, 255}), 5, nil)
	tag.node()
	var typed *UnknownNodeTagError
	if !errors.As(fmt.Errorf("node 7: %w", tag.err), &typed) || typed.Tag != 255 {
		t.Fatal(tag.err)
	}
}
func TestDecoderAuditAllFixtureNodesRemainOwnedAfterClose(t *testing.T) {
	for _, fixture := range []string{"testdata/jvm-v3", "testdata/callsite-index/store", "testdata/stringtables/node-store"} {
		t.Run(fixture, func(t *testing.T) {
			s, err := Open(fixture)
			if err != nil {
				t.Fatal(err)
			}
			var before []Node
			for _, id := range s.NodeIDs() {
				n, err := s.CandidateNode(context.Background(), id)
				if err != nil {
					t.Fatal(err)
				}
				before = append(before, n)
			}
			if err := s.Close(); err != nil {
				t.Fatal(err)
			}
			if _, err := s.CandidateNode(context.Background(), before[0].ID); !errors.Is(err, ErrStoreClosed) {
				t.Fatal(err)
			}
			eager, err := OpenMode(fixture, "EAGER")
			if err != nil {
				t.Fatal(err)
			}
			defer eager.Close()
			for _, old := range before {
				n, err := eager.Node(old.ID)
				if err != nil || !reflect.DeepEqual(n, old) {
					t.Fatalf("owned node changed after unmap: %d, %v", old.ID, err)
				}
			}
		})
	}
}

// Both readers run the complete Node decoder; compare partial values and errors.
func assertCursorNode(t *testing.T, data []byte, table []string, version int) Node {
	t.Helper()
	reader := newDecoder(bytes.NewReader(data), int64(len(data)), table)
	mapped := newMappedNodeDecoder(data, table)
	reader.version = version
	mapped.version = version
	want := reader.node()
	got := mapped.node()
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("v%d bytes=%x node mismatch\nreader=%#v\nmapped=%#v", version, data, want, got)
	}
	if reflect.TypeOf(reader.err) != reflect.TypeOf(mapped.err) || fmt.Sprint(reader.err) != fmt.Sprint(mapped.err) || reader.remaining != mapped.remaining {
		t.Fatalf("v%d bytes=%x reader=(%T %v remaining=%d) mapped=(%T %v remaining=%d)", version, data, reader.err, reader.err, reader.remaining, mapped.err, mapped.err, mapped.remaining)
	}
	if mapped.err != nil {
		first := mapped.err
		remaining := mapped.remaining
		mapped.i64()
		mapped.fail("secondary")
		if mapped.err != first || mapped.remaining != remaining {
			t.Fatal("mapped first error/position changed")
		}
	}
	return got
}
func TestMappedCursorEveryByteTruncationAllNodeKinds(t *testing.T) {
	s, err := Open("testdata/jvm-v3")
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	ids := s.NodeIDs()
	var prefixes int
	for i, id := range ids {
		start := s.locations[id].offset
		end := s.size
		for _, other := range s.locations {
			if other.offset > start && other.offset < end {
				end = other.offset
			}
		}
		full := s.mappedData[start:end]
		t.Run(fmt.Sprintf("%02d-%s", i, nodeKinds[s.locations[id].tag]), func(t *testing.T) {
			for length := 0; length <= len(full); length++ {
				assertCursorNode(t, full[:length], s.Strings, s.FormatVersion)
				prefixes++
			}
		})
	}
	if len(ids) != 16 {
		t.Fatalf("expected all16 kinds, got%d", len(ids))
	}
	t.Logf("all16 node kinds, %d byte prefixes", prefixes)
}
func auditInts(out *bytes.Buffer, values ...int32) {
	for _, v := range values {
		_ = binary.Write(out, binary.BigEndian, v)
	}
}
func TestMappedCursorLegacyAnnotationsAndScalarFallback(t *testing.T) {
	table := []string{"Ann", "C", "m", "k", "", "text"}
	for _, version := range []int{1, 2, 3} {
		for _, empty := range []bool{false, true} {
			var b bytes.Buffer
			auditInts(&b, 7)
			b.WriteByte(13)
			auditInts(&b, 0, 1, 2, 1, 3)
			value := int32(5)
			if empty {
				value = 4
			}
			if version > 1 {
				b.WriteByte(2)
			}
			auditInts(&b, value)
			for length := 0; length <= b.Len(); length++ {
				assertCursorNode(t, b.Bytes()[:length], table, version)
			}
			want := any(table[value])
			if version == 1 && empty {
				want = nil
			}
			n := assertCursorNode(t, b.Bytes(), table, version)
			if n.Kind != "AnnotationNode" || n.Name != "Ann" || n.ClassName != "C" || n.MemberName != "m" || !reflect.DeepEqual(n.Values, map[string]any{"k": want}) {
				t.Fatalf("v%d annotation=%#v", version, n)
			}
		}
	}
	for _, tag := range []byte{8, 255} {
		var b bytes.Buffer
		auditInts(&b, 7)
		b.WriteByte(13)
		auditInts(&b, 0, 1, 2, 1, 3)
		b.WriteByte(tag)
		if tag == 8 {
			auditInts(&b, 1)
			b.WriteByte(2)
		}
		auditInts(&b, 5)
		for _, version := range []int{1, 2, 3} {
			for length := 0; length <= b.Len(); length++ {
				assertCursorNode(t, b.Bytes()[:length], table, version)
			}
		}
	}
}
func TestMappedCursorMalformedNodeAndRemainingParity(t *testing.T) {
	var cases [][]byte
	for _, tag := range []byte{16, 127, 128, 255} {
		cases = append(cases, []byte{0, 0, 0, 7, tag})
	}
	for _, sid := range []int32{-1, 1, 2147483647} {
		var b bytes.Buffer
		auditInts(&b, 7)
		b.WriteByte(1)
		auditInts(&b, sid)
		cases = append(cases, b.Bytes())
	}
	for _, count := range []int32{-1, 2147483647, 1} {
		var b bytes.Buffer
		auditInts(&b, 7)
		b.WriteByte(13)
		auditInts(&b, 0, 0, 0, count)
		cases = append(cases, b.Bytes())
	}
	var deep bytes.Buffer
	auditInts(&deep, 7)
	deep.WriteByte(13)
	auditInts(&deep, 0, 0, 0, 1, 0)
	for i := 0; i < 258; i++ {
		deep.WriteByte(8)
		auditInts(&deep, 1)
	}
	deep.WriteByte(6)
	cases = append(cases, deep.Bytes())
	for i, data := range cases {
		t.Run(fmt.Sprint(i), func(t *testing.T) {
			for length := 0; length <= len(data); length++ {
				assertCursorNode(t, data[:length], []string{"ok"}, 3)
			}
		})
	}
}
func TestMappedCursorScalarBoundsAndBitParity(t *testing.T) {
	for _, data := range [][]byte{nil, {}, {0xff}, {0x80, 0}, {0xff, 0xff, 0xff, 0xff}, {0x80, 0, 0, 0, 0, 0, 0, 0}} {
		for _, kind := range []string{"u8", "u16", "i32", "i64"} {
			a := newDecoder(bytes.NewReader(data), int64(len(data)), nil)
			b := newMappedNodeDecoder(data, nil)
			scalar := func(d *decoder) any {
				switch kind {
				case "u8":
					return d.u8()
				case "u16":
					return d.u16()
				case "i32":
					return d.i32()
				default:
					return d.i64()
				}
			}
			x, y := scalar(a), scalar(b)
			if x != y || fmt.Sprint(a.err) != fmt.Sprint(b.err) || a.remaining != b.remaining {
				t.Fatalf("%s %x reader=%v,%v mapped=%v,%v", kind, data, x, a.err, y, b.err)
			}
		}
	}
	for _, count := range []int{-1, 0, 1, 4, 5} {
		a := newDecoder(bytes.NewReader([]byte{1, 2, 3, 4}), 4, nil)
		b := newMappedNodeDecoder([]byte{1, 2, 3, 4}, nil)
		x, y := a.bytes(count), b.scalarBytes(count)
		if !reflect.DeepEqual(x, y) || fmt.Sprint(a.err) != fmt.Sprint(b.err) || a.remaining != b.remaining {
			t.Fatalf("count%d: reader%v,%v mapped%v,%v", count, x, a.err, y, b.err)
		}
	}
}

func TestMappedCursorStoreErrorPrecedenceMatchesReader(t *testing.T) {
	cases := []struct {
		name   string
		data   []byte
		lookup int32
		tag    byte
		want   string
	}{
		{"decode-before-id-mismatch", []byte{0, 0, 0, 7, 1, 255, 255, 255, 255}, 8, 1, "node 8: string index -1 outside table of 1"},
		{"id-mismatch", []byte{0, 0, 0, 7, 1, 0, 0, 0, 0}, 8, 1, "node index mismatch for 8"},
		{"kind-mismatch", []byte{0, 0, 0, 7, 6}, 7, 1, "node index mismatch for 7"},
		{"unknown-before-mismatch", []byte{0, 0, 0, 7, 255}, 8, 1, "node 8: unknown node tag 255"},
		{"trunc-before-mismatch", []byte{0, 0, 0, 7, 1}, 8, 1, "node 8: truncated data: need 4 bytes, have 0"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "node.data")
			if err := os.WriteFile(path, c.data, 0600); err != nil {
				t.Fatal(err)
			}
			file, err := os.Open(path)
			if err != nil {
				t.Fatal(err)
			}
			defer file.Close()
			makeStore := func(mapped bool) *Store {
				s := &Store{file: file, size: int64(len(c.data)), Strings: []string{"ok"}, locations: map[int32]nodeLocation{c.lookup: {0, c.tag}}}
				if mapped {
					s.mappedData = c.data
				}
				return s
			}
			a, ea := makeStore(false).Node(c.lookup)
			b, eb := makeStore(true).Node(c.lookup)
			if !reflect.DeepEqual(a, b) || fmt.Sprint(ea) != c.want || fmt.Sprint(eb) != c.want || reflect.TypeOf(errors.Unwrap(ea)) != reflect.TypeOf(errors.Unwrap(eb)) {
				t.Fatalf("reader=%#v,%T %v; mapped=%#v,%T %v", a, ea, ea, b, eb, eb)
			}
			_, missing := makeStore(true).Node(-1)
			if !errors.Is(missing, ErrNodeNotFound) {
				t.Fatal(missing)
			}
		})
	}
}
func TestMappedCursorFloatingPayloadBits(t *testing.T) {
	for _, bits := range []uint64{0, 0x8000000000000000, 0x7ff0000000000000, 0xfff0000000000000, 0x7ff8000000000001} {
		var data bytes.Buffer
		auditInts(&data, 7)
		data.WriteByte(4)
		_ = binary.Write(&data, binary.BigEndian, bits)
		a := newDecoder(bytes.NewReader(data.Bytes()), int64(data.Len()), nil)
		b := newMappedNodeDecoder(data.Bytes(), nil)
		x, y := a.node(), b.node()
		if a.err != nil || b.err != nil || math.Float64bits(x.Value.(float64)) != bits || math.Float64bits(y.Value.(float64)) != bits {
			t.Fatalf("float64 %x: reader%#v mapped%#v", bits, x, y)
		}
	}
	for _, bits := range []uint32{0, 0x80000000, 0x7f800000, 0xff800000, 0x7fc00001} {
		var data bytes.Buffer
		auditInts(&data, 7)
		data.WriteByte(3)
		_ = binary.Write(&data, binary.BigEndian, bits)
		a := newDecoder(bytes.NewReader(data.Bytes()), int64(data.Len()), nil)
		b := newMappedNodeDecoder(data.Bytes(), nil)
		x, y := a.node(), b.node()
		if a.err != nil || b.err != nil || math.Float32bits(x.Value.(float32)) != bits || math.Float32bits(y.Value.(float32)) != bits {
			t.Fatalf("float32 %x: reader%#v mapped%#v", bits, x, y)
		}
	}
}
