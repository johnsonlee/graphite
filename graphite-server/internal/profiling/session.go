// Package profiling records native CPU samples and writes a standalone HTML report.
package profiling

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime/pprof"
	"sync"

	profile "github.com/google/pprof/profile"
)

type Session struct {
	path string
	raw  *os.File
	once sync.Once
	err  error
}

// Start leaves an existing report untouched until a new complete report is ready.
// The temporary compressed profile is private and removed after Stop.
func Start(path string) (*Session, error) {
	raw, err := os.CreateTemp(filepath.Dir(path), ".graphite-cpu-*.pprof")
	if err != nil {
		return nil, fmt.Errorf("start CPU profile: %w", err)
	}
	if err = pprof.StartCPUProfile(raw); err != nil {
		raw.Close()
		os.Remove(raw.Name())
		return nil, fmt.Errorf("start CPU profile: %w", err)
	}
	return &Session{path: path, raw: raw}, nil
}

// Stop flushes the runtime's samples before parsing them. It is safe to call more
// than once; all callers receive the same final write result.
func (s *Session) Stop() error {
	s.once.Do(func() {
		pprof.StopCPUProfile()
		defer os.Remove(s.raw.Name())
		defer s.raw.Close()
		if _, err := s.raw.Seek(0, 0); err != nil {
			s.err = fmt.Errorf("seek CPU profile: %w", err)
			return
		}
		p, err := profile.Parse(s.raw)
		if err != nil {
			s.err = fmt.Errorf("read CPU profile: %w", err)
			return
		}
		if err = s.raw.Close(); err != nil {
			s.err = fmt.Errorf("close CPU profile: %w", err)
			return
		}
		s.err = writeReport(s.path, p)
	})
	return s.err
}

func writeReport(path string, p *profile.Profile) error {
	f, err := os.CreateTemp(filepath.Dir(path), ".graphite-cpu-*.html")
	if err != nil {
		return fmt.Errorf("create CPU report: %w", err)
	}
	defer os.Remove(f.Name())
	if err = Render(f, p); err != nil {
		f.Close()
		return err
	}
	if err = f.Close(); err != nil {
		return fmt.Errorf("close CPU report: %w", err)
	}
	if err = os.Rename(f.Name(), path); err != nil {
		return fmt.Errorf("save CPU report: %w", err)
	}
	return nil
}
