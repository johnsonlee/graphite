package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"github.com/johnsonlee/graphite/graphite-server/internal/query"
	"github.com/johnsonlee/graphite/graphite-server/internal/store"
	"os"
	"path/filepath"
	"runtime"
	"runtime/pprof"
	"syscall"
	"time"
)

type graphSpec struct {
	ID        string `json:"id"`
	Path      string `json:"path"`
	Nodes     int    `json:"nodes"`
	Edges     int64  `json:"edges"`
	Methods   int    `json:"methods"`
	CallSites int    `json:"callSites"`
}
type querySpec struct {
	Name     string         `json:"name"`
	Query    string         `json:"query"`
	Source   string         `json:"source"`
	Expected map[string]any `json:"expected"`
}
type config struct {
	Graphs  []graphSpec      `json:"graphs"`
	Totals  map[string]int64 `json:"totals"`
	Queries []querySpec      `json:"queries"`
}
type snapshot struct {
	Timestamp string
	Mem       runtime.MemStats
	Usage     syscall.Rusage
}

func snap() snapshot {
	s := snapshot{Timestamp: time.Now().UTC().Format(time.RFC3339Nano)}
	runtime.ReadMemStats(&s.Mem)
	must(syscall.Getrusage(syscall.RUSAGE_SELF, &s.Usage))
	return s
}
func must(e error) {
	if e != nil {
		panic(e)
	}
}
func save(path string, v any) {
	b, e := json.MarshalIndent(v, "", "  ")
	must(e)
	must(os.WriteFile(path, append(b, '\n'), 0644))
}
func heap(path string) {
	f, e := os.Create(path)
	must(e)
	must(pprof.WriteHeapProfile(f))
	must(f.Close())
}
func digest(b []byte) string            { s := sha256.Sum256(b); return hex.EncodeToString(s[:]) }
func seconds(t syscall.Timeval) float64 { return float64(t.Sec) + float64(t.Usec)/1e6 }
func main() {
	configPath := flag.String("config", "", "fixed configuration")
	out := flag.String("out", "", "new output directory")
	flag.Parse()
	if *out == "" || *configPath == "" {
		panic("config and out required")
	}
	must(os.MkdirAll(*out, 0755))
	b, e := os.ReadFile(*configPath)
	must(e)
	var cfg config
	must(json.Unmarshal(b, &cfg))
	if len(cfg.Graphs) != 64 {
		panic("exactly 64 real graphs required")
	}
	save(filepath.Join(*out, "runtime.json"), map[string]any{"goVersion": runtime.Version(), "GOOS": runtime.GOOS, "GOARCH": runtime.GOARCH, "NumCPU": runtime.NumCPU(), "GOMAXPROCS": runtime.GOMAXPROCS(0), "MemProfileRate": runtime.MemProfileRate, "GOGC": os.Getenv("GOGC"), "GOMEMLIMIT": os.Getenv("GOMEMLIMIT"), "pid": os.Getpid(), "scope": "loaded-64-store ExecuteCross plus root response JSON marshaling; not HTTP; one request at a time", "profiling": "CPU starts after full load/catalog/forced GC; heap alloc delta subtracts before profile; sampled allocation rate is default 512 KiB"})
	sources := []query.Graph{}
	actual := []graphSpec{}
	totals := map[string]int64{"nodes": 0, "edges": 0, "methods": 0, "callSites": 0}
	for i, want := range cfg.Graphs {
		g, e := store.OpenMode(want.Path, "MAPPED")
		must(e)
		got := graphSpec{ID: want.ID, Path: want.Path, Nodes: g.NodeCount, Edges: g.EdgeCount, Methods: len(g.Metadata.MethodList), CallSites: len(g.NodesOfKind("CallSiteNode"))}
		if got != want {
			panic(fmt.Sprintf("catalog mismatch: want %+v got %+v", want, got))
		}
		sources = append(sources, query.Graph{ID: want.ID, Store: g})
		actual = append(actual, got)
		totals["nodes"] += int64(got.Nodes)
		totals["edges"] += got.Edges
		totals["methods"] += int64(got.Methods)
		totals["callSites"] += int64(got.CallSites)
		fmt.Printf("loaded %d/64 %s\n", i+1, want.ID)
	}
	for k, want := range cfg.Totals {
		if totals[k] != want {
			panic(fmt.Sprintf("total %s mismatch %d != %d", k, totals[k], want))
		}
	}
	save(filepath.Join(*out, "catalog.json"), map[string]any{"count": len(actual), "graphs": actual, "totals": totals, "matchesMainCatalog": true})
	fmt.Println("CATALOG VERIFIED", totals)
	for _, q := range cfg.Queries {
		profile(*out, q, sources)
	}
	runtime.KeepAlive(sources)
	for _, s := range sources {
		must(s.Store.Close())
	}
	fmt.Println("COMPLETE")
}
func profile(out string, q querySpec, sources []query.Graph) {
	dir := filepath.Join(out, q.Name)
	must(os.Mkdir(dir, 0755))
	save(filepath.Join(dir, "query.json"), q)
	runtime.GC()
	runtime.GC()
	heap(filepath.Join(dir, "heap-before.pprof"))
	cpu, e := os.Create(filepath.Join(dir, "cpu.pprof"))
	must(e)
	must(pprof.StartCPUProfile(cpu))
	before := snap()
	fmt.Printf("PROFILE START %s %s\n", q.Name, before.Timestamp)
	start := time.Now()
	var result query.Result
	var queryErr error
	pprof.Do(context.Background(), pprof.Labels("phase", "execute", "shape", q.Name), func(ctx context.Context) { result, queryErr = query.ExecuteCross(ctx, sources, q.Query, nil, 1000) })
	querySeconds := time.Since(start).Seconds()
	var output []byte
	if queryErr == nil {
		pprof.Do(context.Background(), pprof.Labels("phase", "marshal", "shape", q.Name), func(context.Context) {
			output, e = json.Marshal(map[string]any{"columns": result.Columns, "rows": result.Rows, "rowCount": len(result.Rows), "graphCount": len(sources)})
		})
		must(e)
	}
	elapsed := time.Since(start).Seconds()
	after := snap()
	pprof.StopCPUProfile()
	must(cpu.Close())
	runtime.GC()
	runtime.GC()
	heap(filepath.Join(dir, "heap-after.pprof"))
	postGC := snap()
	runtime.KeepAlive(result)
	runtime.KeepAlive(output)
	must(os.WriteFile(filepath.Join(dir, "output.json"), output, 0644))
	expected, e := json.Marshal(q.Expected)
	must(e)
	equal := digest(expected) == digest(output)
	delta := map[string]any{"totalAllocBytes": after.Mem.TotalAlloc - before.Mem.TotalAlloc, "mallocs": after.Mem.Mallocs - before.Mem.Mallocs, "frees": after.Mem.Frees - before.Mem.Frees, "numGC": after.Mem.NumGC - before.Mem.NumGC, "pauseTotalNs": after.Mem.PauseTotalNs - before.Mem.PauseTotalNs, "userCPUSeconds": seconds(after.Usage.Utime) - seconds(before.Usage.Utime), "systemCPUSeconds": seconds(after.Usage.Stime) - seconds(before.Usage.Stime)}
	receipt := map[string]any{"name": q.Name, "query": q.Query, "source": q.Source, "querySecondsDiagnostic": querySeconds, "executeAndMarshalSecondsDiagnostic": elapsed, "outputBytes": len(output), "outputSha256": digest(output), "expectedSha256": digest(expected), "fullOutputMatchesExpected": equal, "rowCount": len(result.Rows), "before": before, "afterRequest": after, "afterForcedGC": postGC, "requestDeltas": delta, "notes": []string{"Not HTTP and not a P95 observation.", "CPU profiling and host co-tenancy affect observed durations.", "GC before and after profiling is excluded from request runtime deltas and CPU profile.", "heap-after minus heap-before contains profiler bookkeeping and post-request GC instrumentation in addition to request allocation."}}
	if queryErr != nil {
		receipt["error"] = queryErr.Error()
	}
	save(filepath.Join(dir, "receipt.json"), receipt)
	fmt.Printf("PROFILE DONE %s bytes=%d hash=%s equal=%v alloc=%d GC=%d\n", q.Name, len(output), digest(output), equal, after.Mem.TotalAlloc-before.Mem.TotalAlloc, after.Mem.NumGC-before.Mem.NumGC)
	if queryErr != nil || !equal {
		panic("query error or complete output mismatch")
	}
}
