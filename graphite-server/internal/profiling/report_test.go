package profiling

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"strings"
	"testing"

	profile "github.com/google/pprof/profile"
)

func sampleProfile() *profile.Profile {
	functions := []*profile.Function{{ID: 1, Name: "main.root", Filename: "main.go"}, {ID: 2, Name: "main.worker", Filename: "work.go"}, {ID: 3, Name: "main.inline", Filename: "inline.go"}, {ID: 4, Name: "main.other", Filename: "other.go"}}
	locations := []*profile.Location{{ID: 1, Line: []profile.Line{{Function: functions[0], Line: 1}}}, {ID: 2, Line: []profile.Line{{Function: functions[2], Line: 9}, {Function: functions[1], Line: 20}}}, {ID: 3, Line: []profile.Line{{Function: functions[3], Line: 30}}}}
	return &profile.Profile{SampleType: []*profile.ValueType{{Type: "samples", Unit: "count"}, {Type: "cpu", Unit: "nanoseconds"}}, PeriodType: &profile.ValueType{Type: "cpu", Unit: "nanoseconds"}, Period: 10_000_000, DurationNanos: 2_000_000_000, Function: functions, Location: locations, Sample: []*profile.Sample{{Location: []*profile.Location{locations[1], locations[0]}, Value: []int64{3, 30_000_000}}, {Location: []*profile.Location{locations[2], locations[0]}, Value: []int64{2, 20_000_000}}}}
}
func TestReportInlineOrderAndCPUUnits(t *testing.T) {
	p := sampleProfile()
	r, err := buildReport(p)
	if err != nil {
		t.Fatal(err)
	}
	if r.Root.Value != 50_000_000 || r.DurationNanos != 2_000_000_000 || r.PeriodNanos != 10_000_000 {
		t.Fatalf("totals %+v", r)
	}
	root := r.Root.Children[0]
	if root.Name != "main.root" || root.Value != 50_000_000 {
		t.Fatalf("root %+v", root)
	}
	worker := root.Children[0]
	if worker.Name != "main.worker" || worker.Value != 30_000_000 || worker.Line != 20 {
		t.Fatalf("worker %+v", worker)
	}
	inline := worker.Children[0]
	if inline.Name != "main.inline" || inline.Value != 30_000_000 || inline.Line != 9 {
		t.Fatalf("inline %+v", inline)
	}
	if root.Children[1].Name != "main.other" || root.Children[1].Value != 20_000_000 {
		t.Fatalf("other %+v", root.Children[1])
	}
	p.PeriodType = &profile.ValueType{Type: "samples", Unit: "count"}
	r, err = buildReport(p)
	if err != nil || r.PeriodNanos != 0 {
		t.Fatalf("non-CPU period mislabeled: %+v %v", r, err)
	}
}
func TestReportInvalidEmptyAndOverflow(t *testing.T) {
	for _, c := range []struct {
		name    string
		mutate  func(*profile.Profile)
		message string
	}{
		{"nil type", func(p *profile.Profile) { p.SampleType[0] = nil }, "nil sample type"},
		{"unit", func(p *profile.Profile) { p.SampleType[1].Unit = "cycles" }, "no CPU nanosecond"},
		{"negative", func(p *profile.Profile) { p.Sample[0].Value[1] = -1 }, "invalid CPU sample total"},
		{"overflow", func(p *profile.Profile) { p.Sample[0].Value[1] = math.MaxInt64 }, "invalid CPU sample total"},
		{"duration", func(p *profile.Profile) { p.DurationNanos = -1 }, "duration or period"},
		{"period", func(p *profile.Profile) { p.Period = -1 }, "duration or period"},
		{"bad values", func(p *profile.Profile) { p.Sample[0].Value = nil }, "invalid CPU profile"},
	} {
		t.Run(c.name, func(t *testing.T) {
			p := sampleProfile()
			c.mutate(p)
			if _, err := buildReport(p); err == nil || !strings.Contains(err.Error(), c.message) {
				t.Fatalf("error %v want %s", err, c.message)
			}
		})
	}
	if _, err := buildReport(nil); err == nil {
		t.Fatal("nil accepted")
	}
	p := sampleProfile()
	p.Sample = nil
	r, err := buildReport(p)
	if err != nil || r.Root.Value != 0 || len(r.Root.Children) != 0 {
		t.Fatal(r, err)
	}
}
func embeddedReport(t *testing.T, data []byte) *report {
	t.Helper()
	start := bytes.Index(data, []byte(`<script id="profile-data" type="application/json">`))
	if start < 0 {
		t.Fatal("no embedded profile")
	}
	start += len(`<script id="profile-data" type="application/json">`)
	end := bytes.Index(data[start:], []byte("</script>"))
	if end < 0 {
		t.Fatal("unterminated data")
	}
	var result report
	if err := json.Unmarshal(data[start:start+end], &result); err != nil {
		t.Fatal(err)
	}
	return &result
}
func TestReportEscapingExactIntegersAndLabels(t *testing.T) {
	p := sampleProfile()
	payload := `</script><script>globalThis.INJECTED=1</script><img src=x onerror=alert(1)>`
	p.Function[2].Name = payload
	p.Function[2].Filename = payload
	p.Sample = p.Sample[:1]
	p.Sample[0].Value[1] = 9007199254740993
	p.Sample[0].Label = map[string][]string{"request": {"private label"}}
	var buf bytes.Buffer
	if err := Render(&buf, p); err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(buf.Bytes(), []byte(payload)) || bytes.Contains(buf.Bytes(), []byte("private label")) {
		t.Fatal("unsafe input or ignored label value leaked into HTML")
	}
	r := embeddedReport(t, buf.Bytes())
	if r.Root.Value != 9007199254740993 || !r.LabelsAggregated || r.Root.Children[0].Children[0].Children[0].Name != payload {
		t.Fatalf("data lost: %+v", r)
	}
	if !bytes.Contains(buf.Bytes(), []byte(`"value":"9007199254740993"`)) {
		t.Fatal("CPU precision is exposed to JS Number rounding")
	}
}

type failedWriter struct{ err error }

func (w failedWriter) Write([]byte) (int, error) { return 0, w.err }
func TestReportWriterFailure(t *testing.T) {
	err := errors.New("write failed")
	if got := Render(failedWriter{err}, sampleProfile()); !errors.Is(got, err) {
		t.Fatal(got)
	}
}
func TestReportAtomicReplacement(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "report.html")
	old := []byte("previous complete report")
	if err := os.WriteFile(path, old, 0600); err != nil {
		t.Fatal(err)
	}
	invalid := sampleProfile()
	invalid.Sample[0].Value[1] = -1
	if err := writeReport(path, invalid); err == nil {
		t.Fatal("invalid profile replaced report")
	}
	got, _ := os.ReadFile(path)
	if !bytes.Equal(got, old) {
		t.Fatal("old report changed")
	}
	if err := writeReport(path, sampleProfile()); err != nil {
		t.Fatal(err)
	}
	got, _ = os.ReadFile(path)
	if r := embeddedReport(t, got); r.Root.Value != 50_000_000 {
		t.Fatal(r.Root.Value)
	}
	target := filepath.Join(dir, "existing-directory")
	os.Mkdir(target, 0700)
	if err := writeReport(target, sampleProfile()); err == nil {
		t.Fatal("directory replacement succeeded")
	}
	matches, _ := filepath.Glob(filepath.Join(dir, ".graphite-cpu-*"))
	if len(matches) != 0 {
		t.Fatal("private temps remain", matches)
	}
}
func TestReportLargeFanout(t *testing.T) {
	p := sampleProfile()
	p.Sample = nil
	p.Location = nil
	p.Function = nil
	for i := 1; i <= 20000; i++ {
		f := &profile.Function{ID: uint64(i), Name: fmt.Sprintf("branch-%05d", i)}
		l := &profile.Location{ID: uint64(i), Line: []profile.Line{{Function: f}}}
		p.Function = append(p.Function, f)
		p.Location = append(p.Location, l)
		p.Sample = append(p.Sample, &profile.Sample{Location: []*profile.Location{l}, Value: []int64{1, 1}})
	}
	var buf bytes.Buffer
	if err := Render(&buf, p); err != nil {
		t.Fatal(err)
	}
	r := embeddedReport(t, buf.Bytes())
	if r.Root.Value != 20000 || len(r.Root.Children) != 20000 {
		t.Fatal("fanout truncated")
	}
}

// Optional fixture export for independent real-browser interaction checks. These
// synthetic profiles test report format and controls, never performance.
func TestProfileBrowserFixtures(t *testing.T) {
	dir := os.Getenv("GRAPHITE_PROFILE_UI_FIXTURES")
	if dir == "" {
		t.Skip("browser fixture export not requested")
	}
	if err := os.MkdirAll(dir, 0700); err != nil {
		t.Fatal(err)
	}
	write := func(name string, p *profile.Profile) {
		t.Helper()
		if err := writeReport(filepath.Join(dir, name), p); err != nil {
			t.Fatal(err)
		}
	}
	write("example.html", sampleProfile())
	empty := sampleProfile()
	empty.Sample = nil
	write("empty.html", empty)
	escaped := sampleProfile()
	escaped.Sample = escaped.Sample[:1]
	escaped.Sample[0].Value[1] = 9007199254740993
	escaped.Function[2].Name = `</script><img src=x onerror="globalThis.INJECTED=1">`
	write("escape.html", escaped)
	many := sampleProfile()
	many.Function = nil
	many.Location = nil
	many.Sample = nil
	for i := 1; i <= 20000; i++ {
		f := &profile.Function{ID: uint64(i), Name: fmt.Sprintf("branch-%05d", i)}
		l := &profile.Location{ID: uint64(i), Line: []profile.Line{{Function: f}}}
		many.Function = append(many.Function, f)
		many.Location = append(many.Location, l)
		many.Sample = append(many.Sample, &profile.Sample{Location: []*profile.Location{l}, Value: []int64{1, 1}})
	}
	write("fanout.html", many)
	deep := sampleProfile()
	deep.Function = nil
	deep.Location = nil
	deep.Sample = nil
	stack := []*profile.Location{}
	for i := 1; i <= 205; i++ {
		f := &profile.Function{ID: uint64(i), Name: fmt.Sprintf("deep-%03d", i)}
		l := &profile.Location{ID: uint64(i), Line: []profile.Line{{Function: f}}}
		deep.Function = append(deep.Function, f)
		deep.Location = append(deep.Location, l)
		stack = append(stack, l)
	}
	deep.Sample = []*profile.Sample{{Location: stack, Value: []int64{1, 1}}}
	write("deep.html", deep)
}
