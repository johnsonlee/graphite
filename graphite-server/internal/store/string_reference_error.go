package store

import "fmt"

// StringTableReferenceError records a consumed string-table index without
// changing Store diagnostics. Query boundaries render Java's List exception.
type StringTableReferenceError struct {
	Index int32
	Size  int
}

func (e *StringTableReferenceError) Error() string {
	return fmt.Sprintf("string index %d outside table of %d", e.Index, e.Size)
}
