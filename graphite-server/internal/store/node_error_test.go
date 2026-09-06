package store

import (
	"bytes"
	"errors"
	"fmt"
	"testing"
)

func TestUnknownNodeTagKeepsStoreDiagnostic(t *testing.T) {
	for _, tag := range []byte{16, 127, 128, 255} {
		d := newDecoder(bytes.NewReader([]byte{0, 0, 0, 7, tag}), 5, nil)
		d.node()
		var typed *UnknownNodeTagError
		wrapped := fmt.Errorf("node 7: %w", d.err)
		if !errors.As(wrapped, &typed) || typed.Tag != tag || wrapped.Error() != fmt.Sprintf("node 7: unknown node tag %d", tag) {
			t.Fatalf("tag %d: %v", tag, wrapped)
		}
	}
	for _, data := range [][]byte{nil, {0, 0}, {0, 0, 0, 7}, {0, 0, 0, 7, 0}} {
		d := newDecoder(bytes.NewReader(data), int64(len(data)), nil)
		d.node()
		var typed *UnknownNodeTagError
		if d.err == nil || errors.As(d.err, &typed) {
			t.Fatalf("truncation misclassified: %x: %v", data, d.err)
		}
	}
}
