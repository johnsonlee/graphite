package profiling

import (
	_ "embed"
	"encoding/json"
	"fmt"
	"html/template"
	"io"
	"math"
	"sort"

	profile "github.com/google/pprof/profile"
)

type frameKey struct {
	Name string `json:"name"`
	File string `json:"file,omitempty"`
	Line int64  `json:"line,omitempty"`
}

type frame struct {
	frameKey
	Value    int64    `json:"value,string"`
	Children []*frame `json:"children,omitempty"`
	byKey    map[frameKey]*frame
}

type report struct {
	Root             *frame `json:"root"`
	DurationNanos    int64  `json:"durationNanos,string"`
	PeriodNanos      int64  `json:"periodNanos,string"`
	LabelsAggregated bool   `json:"labelsAggregated"`
}

//go:embed report.html
var reportHTML string

var page = template.Must(template.New("cpu-profile").Parse(reportHTML))

// Render emits HTML with embedded data and controls, requiring no Go tools,
// Graphviz, JavaScript downloads, or network connection to inspect the result.
func Render(w io.Writer, p *profile.Profile) error {
	r, err := buildReport(p)
	if err != nil {
		return err
	}
	data, err := json.Marshal(r)
	if err != nil {
		return err
	}
	// json.Marshal escapes HTML-sensitive characters and script terminators.
	// Function/file names are subsequently displayed using DOM textContent.
	return page.Execute(w, template.JS(data))
}

func buildReport(p *profile.Profile) (*report, error) {
	if p == nil {
		return nil, fmt.Errorf("invalid CPU profile: nil profile")
	}
	if err := p.CheckValid(); err != nil {
		return nil, fmt.Errorf("invalid CPU profile: %w", err)
	}
	cpu := -1
	for i, kind := range p.SampleType {
		if kind == nil {
			return nil, fmt.Errorf("invalid CPU profile: nil sample type")
		}
		if kind.Type == "cpu" && kind.Unit == "nanoseconds" {
			cpu = i
			break
		}
	}
	if cpu < 0 {
		return nil, fmt.Errorf("profile has no CPU nanosecond samples")
	}
	if p.DurationNanos < 0 || p.Period < 0 {
		return nil, fmt.Errorf("invalid CPU profile duration or period")
	}
	r := &report{Root: &frame{frameKey: frameKey{Name: "All CPU samples"}}, DurationNanos: p.DurationNanos}
	if p.PeriodType != nil && p.PeriodType.Type == "cpu" && p.PeriodType.Unit == "nanoseconds" {
		r.PeriodNanos = p.Period
	}
	for _, sample := range p.Sample {
		if len(sample.Label) > 0 || len(sample.NumLabel) > 0 {
			r.LabelsAggregated = true
		}
		value := sample.Value[cpu]
		if value < 0 || r.Root.Value > math.MaxInt64-value {
			return nil, fmt.Errorf("invalid CPU sample total")
		}
		if value == 0 {
			continue
		}
		current := r.Root
		current.Value += value
		appendFrame := func(key frameKey) {
			if current.byKey == nil {
				current.byKey = make(map[frameKey]*frame)
			}
			next := current.byKey[key]
			if next == nil {
				next = &frame{frameKey: key}
				current.byKey[key] = next
				current.Children = append(current.Children, next)
			}
			next.Value += value
			current = next
		}
		// Locations and each location's inlined frames are innermost first.
		for i := len(sample.Location) - 1; i >= 0; i-- {
			location := sample.Location[i]
			if len(location.Line) == 0 {
				key := frameKey{Name: fmt.Sprintf("[0x%x]", location.Address)}
				if location.Mapping != nil {
					key.File = location.Mapping.File
				}
				appendFrame(key)
			}
			for j := len(location.Line) - 1; j >= 0; j-- {
				line := location.Line[j]
				key := frameKey{Name: "[unknown]", Line: line.Line}
				if line.Function != nil {
					key.Name, key.File = line.Function.Name, line.Function.Filename
					if key.Name == "" {
						key.Name = line.Function.SystemName
					}
					if key.Name == "" {
						key.Name = "[unknown]"
					}
				}
				appendFrame(key)
			}
		}
	}
	pending := []*frame{r.Root}
	for len(pending) > 0 {
		parent := pending[len(pending)-1]
		pending = pending[:len(pending)-1]
		sort.Slice(parent.Children, func(i, j int) bool {
			a, b := parent.Children[i], parent.Children[j]
			if a.Value != b.Value {
				return a.Value > b.Value
			}
			if a.Name != b.Name {
				return a.Name < b.Name
			}
			if a.File != b.File {
				return a.File < b.File
			}
			return a.Line < b.Line
		})
		parent.byKey = nil
		pending = append(pending, parent.Children...)
	}
	return r, nil
}
