package profiling

import (
	"crypto/sha256"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

var profileSink byte

//go:noinline
func profileCPUWork() {
	// Correctness only: keep the CPU active long enough to collect runtime samples.
	// No throughput, elapsed-time comparison, or performance assertion is made.
	deadline := time.Now().Add(300 * time.Millisecond)
	var input [4096]byte
	for time.Now().Before(deadline) {
		sum := sha256.Sum256(input[:])
		input[0] = sum[0]
	}
	profileSink = input[0]
}
func TestSessionRealCPUAndConcurrentStop(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "report.html")
	old := []byte("old report")
	os.WriteFile(path, old, 0600)
	s, err := Start(path)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Stop()
	info, err := os.Stat(s.raw.Name())
	if err != nil || info.Mode().Perm() != 0600 {
		t.Fatal("temporary profile is not private", info, err)
	}
	current, _ := os.ReadFile(path)
	if string(current) != string(old) {
		t.Fatal("old output changed before successful Stop")
	}
	profileCPUWork()
	var group sync.WaitGroup
	for i := 0; i < 4; i++ {
		group.Add(1)
		go func() {
			defer group.Done()
			if err := s.Stop(); err != nil {
				t.Error(err)
			}
		}()
	}
	group.Wait()
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	r := embeddedReport(t, raw)
	if r.Root.Value <= 0 {
		t.Fatal("real CPU workload produced no samples")
	}
	found := false
	nodes := []*frame{r.Root}
	for len(nodes) > 0 {
		n := nodes[len(nodes)-1]
		nodes = nodes[:len(nodes)-1]
		if strings.Contains(n.Name, "profileCPUWork") {
			found = true
		}
		nodes = append(nodes, n.Children...)
	}
	if !found {
		t.Fatal("sampled workload stack missing")
	}
	matches, _ := filepath.Glob(filepath.Join(dir, ".graphite-cpu-*"))
	if len(matches) != 0 {
		t.Fatal("private temporary files remain", matches)
	}
}
func TestSessionStartAndStopFailures(t *testing.T) {
	dir := t.TempDir()
	if _, err := Start(filepath.Join(dir, "missing", "report.html")); err == nil {
		t.Fatal("missing parent accepted")
	}
	path := filepath.Join(dir, "directory")
	if err := os.Mkdir(path, 0700); err != nil {
		t.Fatal(err)
	}
	s, err := Start(path)
	if err != nil {
		t.Fatal(err)
	}
	first := s.Stop()
	if first == nil || s.Stop() != first {
		t.Fatal("failed Stop not retained", first)
	}
	if info, err := os.Stat(path); err != nil || !info.IsDir() {
		t.Fatal("existing output directory changed", err)
	}
	matches, _ := filepath.Glob(filepath.Join(dir, ".graphite-cpu-*"))
	if len(matches) != 0 {
		t.Fatal(matches)
	}
}
