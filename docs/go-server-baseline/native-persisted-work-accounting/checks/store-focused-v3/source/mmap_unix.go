//go:build darwin || linux

package store

import (
	"os"
	"syscall"
)

func mapNodeData(file *os.File, size int64) ([]byte, error) {
	if int64(int(size)) != size {
		return nil, syscall.EOVERFLOW
	}
	return syscall.Mmap(int(file.Fd()), 0, int(size), syscall.PROT_READ, syscall.MAP_SHARED)
}
func unmapNodeData(data []byte) error { return syscall.Munmap(data) }
