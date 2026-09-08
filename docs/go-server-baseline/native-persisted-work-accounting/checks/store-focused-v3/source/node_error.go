package store

import "fmt"

// UnknownNodeTagError identifies a consumed node tag, independently of callers'
// wire formats. Keep the unsigned diagnostic used by the Store API; Java-facing
// boundaries separately render the signed byte and reference exception shape.
type UnknownNodeTagError struct{ Tag byte }

func (e *UnknownNodeTagError) Error() string { return fmt.Sprintf("unknown node tag %d", e.Tag) }
