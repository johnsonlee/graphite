package javastring

import (
	"bufio"
	"compress/gzip"
	"encoding/binary"
	"encoding/hex"
	"os"
	"strings"
	"testing"
)

func TestASCIILowerExhaustiveJava17OracleAndCallback(t *testing.T) {
	file, err := os.Open("testdata/ascii-lower-java17.tsv.gz")
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	reader, err := gzip.NewReader(file)
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	decode := func(text string) string {
		b, err := hex.DecodeString(text)
		if err != nil || len(b)%2 != 0 {
			t.Fatalf("invalid oracle: %q", text)
		}
		units := make([]uint16, len(b)/2)
		for i := range units {
			units[i] = binary.BigEndian.Uint16(b[2*i:])
		}
		return FromUTF16(units)
	}
	scanner := bufio.NewScanner(reader)
	count := 0
	for scanner.Scan() {
		fields := strings.Split(scanner.Text(), "\t")
		if len(fields) != 2 {
			t.Fatal("invalid oracle row")
		}
		input, want := decode(fields[0]), decode(fields[1])
		calls := 0
		got := Case(input, false, func() { calls++ })
		if got != want || calls != 4 {
			t.Fatalf("input %q got %q callback %d want %q/4", input, got, calls, want)
		}
		count++
	}
	if err := scanner.Err(); err != nil {
		t.Fatal(err)
	}
	if count != 16384 {
		t.Fatalf("oracle rows %d", count)
	}
}

func TestASCIILowerFallbackBoundaries(t *testing.T) {
	for _, c := range []struct{ input, want string }{
		{"", ""}, {"already.lower_09", "already.lower_09"},
		{"ASCII İ ΟΣ", "ascii i̇ ος"}, {"A\xed\xa0\x80Z", "a\xed\xa0\x80z"},
		{"A\xffZ", "a�z"}, {"A𐐀Z", "a𐐨z"}, {"AΣ_A", "aσ_a"},
	} {
		if got := Case(c.input, false, nil); got != c.want {
			t.Errorf("%q got %q want %q", c.input, got, c.want)
		}
	}
	for _, text := range []string{"already.lower", "UPPERCASE"} {
		func() {
			calls := 0
			defer func() {
				if v := recover(); v != "cancel" || calls != 3 {
					t.Errorf("%q cancellation %v calls %d", text, v, calls)
				}
			}()
			Case(text, false, func() {
				calls++
				if calls == 3 {
					panic("cancel")
				}
			})
			t.Errorf("%q did not cancel", text)
		}()
	}
}
