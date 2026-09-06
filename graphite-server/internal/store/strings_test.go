package store

import (
	"bytes"
	"compress/gzip"
	"crypto/sha256"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"unicode/utf16"
	"unicode/utf8"
)

func TestJVMStringTableUTF16(t *testing.T) {
	const dir = "testdata/stringtables"
	data, err := os.ReadFile(filepath.Join(dir, "oracle.json"))
	if err != nil {
		t.Fatal(err)
	}
	var cases []struct {
		Name, UTF16SHA256, ErrorPhase, ErrorClass, ErrorMessage string
		Count                                                   int
	}
	if err = json.Unmarshal(data, &cases); err != nil {
		t.Fatal(err)
	}
	if len(cases) != 18 {
		t.Fatalf("expected 18 Java string table cases, got %d", len(cases))
	}
	for _, tc := range cases {
		t.Run(tc.Name, func(t *testing.T) {
			actual, err := LoadStrings(filepath.Join(dir, tc.Name+".strings"))
			if tc.ErrorClass != "" {
				if err == nil {
					t.Fatalf("Java %s fails with %s: %s", tc.ErrorPhase, tc.ErrorClass, tc.ErrorMessage)
				}
				if tc.ErrorClass == "InvalidClassException" && err.Error() != tc.ErrorMessage {
					t.Fatalf("class compatibility error %q, Java %q", err, tc.ErrorMessage)
				}
				if tc.ErrorPhase == "get" && !strings.Contains(err.Error(), "UTF-8 char array index") {
					t.Fatalf("unexpected UTF-8 decoding error %v", err)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if len(actual) != tc.Count {
				t.Fatalf("string count %d, Java %d", len(actual), tc.Count)
			}
			file, err := os.Open(filepath.Join(dir, tc.Name+".utf16.gz"))
			if err != nil {
				t.Fatal(err)
			}
			defer file.Close()
			z, err := gzip.NewReader(file)
			if err != nil {
				t.Fatal(err)
			}
			defer z.Close()
			wanted, err := io.ReadAll(z)
			if err != nil {
				t.Fatal(err)
			}
			if got := fmt.Sprintf("%x", sha256.Sum256(wanted)); got != tc.UTF16SHA256 {
				t.Fatalf("oracle fixture hash %s, want %s", got, tc.UTF16SHA256)
			}
			reader := bytes.NewReader(wanted)
			read32 := func() uint32 {
				var n uint32
				if err := binary.Read(reader, binary.BigEndian, &n); err != nil {
					t.Fatal(err)
				}
				return n
			}
			if n := read32(); n != uint32(tc.Count) {
				t.Fatalf("oracle count %d", n)
			}
			var observed bytes.Buffer
			binary.Write(&observed, binary.BigEndian, uint32(len(actual)))
			for index, text := range actual {
				length := read32()
				units := make([]uint16, length)
				if err := binary.Read(reader, binary.BigEndian, units); err != nil {
					t.Fatal(err)
				}
				got := stringUnits(t, text)
				if len(got) != len(units) {
					t.Fatalf("string %d has %d units, Java %d", index, len(got), len(units))
				}
				for i, u := range units {
					if got[i] != u {
						t.Fatalf("string %d unit %d: %04x, Java %04x", index, i, got[i], u)
					}
				}
				binary.Write(&observed, binary.BigEndian, uint32(len(got)))
				binary.Write(&observed, binary.BigEndian, got)
			}
			if reader.Len() != 0 {
				t.Fatal("trailing oracle units")
			}
			if got := fmt.Sprintf("%x", sha256.Sum256(observed.Bytes())); got != tc.UTF16SHA256 {
				t.Fatalf("complete table hash %s, Java %s", got, tc.UTF16SHA256)
			}
		})
	}
}

// Decode the actual output independently of the production encoder. An
// isolated surrogate must be exactly its three-byte WTF-8 form, not U+FFFD.
func stringUnits(t *testing.T, text string) []uint16 {
	t.Helper()
	var out []uint16
	for i := 0; i < len(text); {
		if i+2 < len(text) && text[i] == 0xed && text[i+1] >= 0xa0 && text[i+1] <= 0xbf && text[i+2] >= 0x80 && text[i+2] <= 0xbf {
			out = append(out, uint16(text[i]&15)<<12|uint16(text[i+1]&63)<<6|uint16(text[i+2]&63))
			i += 3
			continue
		}
		r, width := utf8.DecodeRuneInString(text[i:])
		if r == utf8.RuneError && width == 1 {
			t.Fatalf("invalid WTF-8 at byte %d: %x", i, text)
		}
		if r > 0xffff {
			a, b := utf16.EncodeRune(r)
			out = append(out, uint16(a), uint16(b))
		} else {
			out = append(out, uint16(r))
		}
		i += width
	}
	return out
}

func TestStoreSurrogatesUseWTF8(t *testing.T) {
	actual, err := LoadStrings("testdata/stringtables/char-ratio-3.strings")
	if err != nil {
		t.Fatal(err)
	}
	if actual[8] != "\xed\xa0\x80" || actual[9] != "\xed\xb0\x80" || actual[10] != "A\xed\xa0\x80B" {
		t.Fatalf("surrogate strings were changed: %x %x %x", actual[8], actual[9], actual[10])
	}
	if actual[4] != "😀" {
		t.Fatalf("valid surrogate pair %x", actual[4])
	}
	utf, err := LoadStrings("testdata/stringtables/utf8-ratio-3.strings")
	if err != nil {
		t.Fatal(err)
	}
	if utf[8] != "?" || utf[9] != "?" || utf[10] != "A?B" {
		t.Fatalf("Java UTF-8 encoder replacements %q %q %q", utf[8], utf[9], utf[10])
	}
}

func TestMappedAndEagerNodesPreserveStoredSurrogates(t *testing.T) {
	for _, mode := range []string{"MAPPED", "EAGER"} {
		t.Run(mode, func(t *testing.T) {
			s, err := OpenMode("testdata/stringtables/node-store", mode)
			if err != nil {
				t.Fatal(err)
			}
			defer s.Close()
			if s.NodeCount != 3 {
				t.Fatalf("node count %d", s.NodeCount)
			}
			for id, want := range []string{"A\xed\xa0\x80B", "\xed\xb0\x80", "😀"} {
				n, err := s.Node(int32(id))
				if err != nil {
					t.Fatal(err)
				}
				if n.Kind != "StringConstant" || n.Value != want {
					t.Fatalf("node %d value %v, want %x", id, n, want)
				}
			}
		})
	}
}
