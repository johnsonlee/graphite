// Package benchmarkcase preserves the workload exported by the pinned main
// benchmark. It describes execution inputs; it does not claim runtime parity.
package benchmarkcase

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"strings"
)

type Workload struct {
	MainRevision string   `json:"mainRevision"`
	Scope        string   `json:"scope"`
	SourceOrder  []string `json:"sourceOrder"`
	Cases        []Case   `json:"cases"`
}
type RowRange struct {
	First int64 `json:"first"`
	Last  int64 `json:"last"`
}
type Case struct {
	ID                      string         `json:"id"`
	Family                  string         `json:"family"`
	Shape                   string         `json:"shape"`
	Selectivity             string         `json:"selectivity"`
	Operator                string         `json:"operator"`
	Boundary                string         `json:"boundary"`
	Projection              string         `json:"projection"`
	Limit                   int64          `json:"limit"`
	Query                   string         `json:"query"`
	Parameters              map[string]any `json:"parameters"`
	ExpectZeroRows          bool           `json:"expectZeroRows"`
	TargetGraphID           *string        `json:"targetGraphId"`
	TargetGraphIDs          []string       `json:"targetGraphIds"`
	WorkloadIdentity        *string        `json:"workloadIdentity"`
	RequestGraphIDs         []string       `json:"requestGraphIds"`
	ExpectedRowCountRange   *RowRange      `json:"expectedRowCountRange"`
	ConfiguredTimeoutMillis *int64         `json:"configuredTimeoutMillis"`
	FixtureDistributionID   *string        `json:"fixtureDistributionId"`
}

// Decode rejects unrecognized fields rather than silently losing new main inputs.
// JSON numbers stay exact until the execution adapter deliberately chooses their
// runtime representation. Null and empty selections remain distinct.
func Decode(data []byte) (Workload, error) {
	var w Workload
	d := json.NewDecoder(bytes.NewReader(data))
	d.UseNumber()
	d.DisallowUnknownFields()
	if err := d.Decode(&w); err != nil {
		return w, err
	}
	var extra any
	if err := d.Decode(&extra); err != io.EOF {
		return w, fmt.Errorf("expected one workload document")
	}
	if err := w.Validate(); err != nil {
		return w, err
	}
	return w, nil
}
func (w Workload) Validate() error {
	if w.MainRevision == "" {
		return fmt.Errorf("missing main revision")
	}
	if len(w.SourceOrder) != 64 {
		return fmt.Errorf("expected64 graph identities, got%d", len(w.SourceOrder))
	}
	graphs := map[string]bool{}
	for _, id := range w.SourceOrder {
		if id == "" || graphs[id] {
			return fmt.Errorf("empty or duplicate graph identity %q", id)
		}
		graphs[id] = true
	}
	if len(w.Cases) == 0 {
		return fmt.Errorf("missing cases")
	}
	ids := map[string]bool{}
	for _, c := range w.Cases {
		if c.ID == "" || ids[c.ID] {
			return fmt.Errorf("empty or duplicate case identity %q", c.ID)
		}
		ids[c.ID] = true
		if strings.TrimSpace(c.Query) == "" || c.Parameters == nil {
			return fmt.Errorf("%s: missing query or parameter map", c.ID)
		}
		if c.Selectivity != "zero" && c.Selectivity != "targeted" && c.Selectivity != "dense" {
			return fmt.Errorf("%s: unknown selectivity %q", c.ID, c.Selectivity)
		}
		for _, selection := range [][]string{c.RequestGraphIDs, c.TargetGraphIDs} {
			seen := map[string]bool{}
			for _, id := range selection {
				if !graphs[id] || seen[id] {
					return fmt.Errorf("%s: unknown or duplicate selected graph %q", c.ID, id)
				}
				seen[id] = true
			}
		}
		if c.TargetGraphID != nil && !graphs[*c.TargetGraphID] {
			return fmt.Errorf("%s: unknown target graph", c.ID)
		}
		if c.ConfiguredTimeoutMillis != nil && *c.ConfiguredTimeoutMillis <= 0 {
			return fmt.Errorf("%s: invalid timeout", c.ID)
		}
	}
	return nil
}

type ExecutionInput struct {
	GraphIDs           []string       `json:"graphIds"`
	SourceScopeApplied bool           `json:"sourceScopeApplied"`
	Query              string         `json:"query"`
	Parameters         map[string]any `json:"parameters"`
	TimeoutMillis      int64          `json:"timeoutMillis"`
}

// Input follows main's request-selected path. Query predicates do not preselect
// inputs here: all sources must enter Cypher for predicate-routing cases.
func (w Workload) Input(c Case, defaultTimeoutMillis int64) (ExecutionInput, error) {
	if defaultTimeoutMillis <= 0 {
		return ExecutionInput{}, fmt.Errorf("invalid default timeout")
	}
	ids := w.SourceOrder
	scoped := c.RequestGraphIDs != nil
	if scoped {
		ids = c.RequestGraphIDs
	}
	timeout := defaultTimeoutMillis
	if c.ConfiguredTimeoutMillis != nil {
		timeout = *c.ConfiguredTimeoutMillis
	}
	return ExecutionInput{append([]string{}, ids...), scoped, c.Query, c.Parameters, timeout}, nil
}
