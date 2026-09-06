package javastring

import (
	"compress/gzip"
	"encoding/binary"
	"io"
	"os"
	"reflect"
	"testing"
)

func TestJava17KotlinCharOracle(t *testing.T) {
	f, err := os.Open("testdata/java17-char.bin.gz")
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	z, err := gzip.NewReader(f)
	if err != nil {
		t.Fatal(err)
	}
	defer z.Close()
	for cp := 0; cp < 65536; cp++ {
		header := make([]byte, 2)
		if _, err := io.ReadFull(z, header); err != nil {
			t.Fatal(err)
		}
		expected := make([]uint16, int(header[1]))
		if err := binary.Read(z, binary.BigEndian, expected); err != nil {
			t.Fatal(err)
		}
		c := uint16(cp)
		if IsLowerChar(c) != (header[0]&1 != 0) || IsUpperChar(c) != (header[0]&2 != 0) || Blank(FromUTF16([]uint16{c})) != (header[0]&4 != 0) {
			t.Fatalf("U+%04X: property flags differ from main %d", cp, header[0])
		}
		if got := UTF16(TitleChar(c)); !reflect.DeepEqual(got, expected) {
			t.Fatalf("U+%04X title: got %04x; main %04x", cp, got, expected)
		}
	}
	var extra [1]byte
	if n, err := z.Read(extra[:]); n != 0 || err != io.EOF {
		t.Fatalf("trailing oracle bytes: n=%d err=%v", n, err)
	}
}

func TestFirstCharPreservesSupplementaryPair(t *testing.T) {
	s := FromUTF16([]uint16{0xd801, 0xdc28, 'a', 'b'})
	if got := UpperFirst(s); got != s {
		t.Fatalf("UpperFirst split a valid UTF16 pair: %x vs %x", got, s)
	}
	if got := TitleFirst(s); got != s {
		t.Fatalf("TitleFirst changed a surrogate char: %x vs %x", got, s)
	}
	if got := UpperFirst("\u00dfeta"); got != "SSeta" {
		t.Fatalf("upper expansion = %q", got)
	}
	if got := TitleFirst("\u00dfeta"); got != "Sseta" {
		t.Fatalf("title expansion = %q", got)
	}
	if got := Trim("\u001c\u00a0x\u0085\u2007"); got != "x\u0085" {
		t.Fatalf("Kotlin trim = %q", got)
	}
}
