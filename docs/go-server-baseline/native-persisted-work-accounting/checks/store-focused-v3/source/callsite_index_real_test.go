package store

import (
	"bufio"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

// Opt-in correctness check only. Open and close one graph at a time; do not use
// t.Cleanup for these Stores, as that would retain the entire manifest's graphs.
func TestRealCallSiteIndexes(t *testing.T) {
	manifest := os.Getenv("GRAPHITE_TEST_CALLSITE_MANIFEST")
	if manifest == "" {
		t.Skip("set GRAPHITE_TEST_CALLSITE_MANIFEST to a real graph TSV")
	}
	file, err := os.Open(manifest)
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	type record struct {
		Graph           string                  `json:"graph"`
		Path            string                  `json:"path"`
		SHA256          string                  `json:"indexSHA256"`
		Bytes           int                     `json:"indexBytes"`
		Info            CallSiteStringIndexInfo `json:"info"`
		AnnotationNodes int                     `json:"annotationNodes"`
		Nodes           int                     `json:"nodes"`
		Methods         int                     `json:"methods"`
		Edges           int64                   `json:"edges"`
	}
	records := []record{}
	scan := bufio.NewScanner(file)
	for scan.Scan() {
		line := scan.Text()
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		fields := strings.Split(line, "\t")
		if len(fields) < 2 {
			t.Fatalf("invalid manifest line %q", line)
		}
		t.Run(fields[0], func(t *testing.T) {
			s, err := OpenMode(fields[1], "MAPPED")
			if err != nil {
				t.Fatal(err)
			}
			defer func() {
				if err := s.Close(); err != nil {
					t.Error(err)
				}
			}()
			// This performs the full CRC and CSR checks, not just a header inventory.
			v, ok, err := s.TryCallSiteStringIndex(context.Background())
			if err != nil || !ok {
				t.Fatalf("available=%v err=%v reason=%s", ok, err, s.CallSiteStringIndexUnavailableReason())
			}
			info, err := v.Info(context.Background())
			if err != nil {
				t.Fatal(err)
			}
			data, err := os.ReadFile(filepath.Join(fields[1], callSiteIndexFile))
			if err != nil {
				t.Fatal(err)
			}
			hash := sha256.Sum256(data)
			records = append(records, record{fields[0], fields[1], hex.EncodeToString(hash[:]), len(data), info, len(s.NodesOfKind("AnnotationNode")), s.NodeCount, len(s.Metadata.MethodList), s.EdgeCount})
		})
		runtime.GC()
	}
	if err := scan.Err(); err != nil {
		t.Fatal(err)
	}
	if len(records) == 0 {
		t.Fatal("manifest contained no validated graph")
	}
	output, err := json.Marshal(records)
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("validated_indexes=%s", output)
}
