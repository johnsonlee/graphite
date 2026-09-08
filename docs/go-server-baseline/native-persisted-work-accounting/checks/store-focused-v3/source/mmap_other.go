//go:build !darwin && !linux

package store

import (
	"fmt"
	"os"
)

func mapNodeData(file *os.File, size int64) ([]byte, error) {
	return nil, fmt.Errorf("MAPPED load mode is currently supported on Linux and macOS")
}
func unmapNodeData(data []byte) error { return nil }
