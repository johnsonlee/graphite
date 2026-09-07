// graphite-benchmark-cases validates exported main benchmark inputs. It never
// opens graphs or reports execution correctness or performance.
package main

import (
	"crypto/sha256"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"reflect"

	"github.com/johnsonlee/graphite/graphite-server/internal/benchmarkcase"
	"github.com/johnsonlee/graphite/graphite-server/internal/cypher"
)

type caseRecord struct {
	Case       benchmarkcase.Case           `json:"case"`
	Input      benchmarkcase.ExecutionInput `json:"input"`
	AST        any                          `json:"ast,omitempty"`
	ParseError string                       `json:"parseError,omitempty"`
}
type report struct {
	MainRevision        string       `json:"mainRevision"`
	ManifestSHA256      string       `json:"manifestSHA256"`
	SourceOrder         []string     `json:"sourceOrder"`
	Cases               []caseRecord `json:"cases"`
	ParseFailures       int          `json:"parseFailures"`
	GraphsOpened        int          `json:"graphsOpened"`
	QueryExecutions     int          `json:"queryExecutions"`
	RuntimeParityProven bool         `json:"runtimeParityProven"`
	Limitations         []string     `json:"limitations"`
}

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
func run() error {
	manifest := flag.String("manifest", "", "Actual main-exported workload JSON")
	expected := flag.String("sha256", "", "Required SHA256 of the complete exported workload")
	output := flag.String("output", "", "New output file (existing evidence is never overwritten)")
	flag.Parse()
	if *manifest == "" || *expected == "" || *output == "" {
		return fmt.Errorf("require --manifest, --sha256 and --output")
	}
	b, err := os.ReadFile(*manifest)
	if err != nil {
		return err
	}
	hash := fmt.Sprintf("%x", sha256.Sum256(b))
	if hash != *expected {
		return fmt.Errorf("workload SHA256 mismatch")
	}
	w, err := benchmarkcase.Decode(b)
	if err != nil {
		return err
	}
	r := report{MainRevision: w.MainRevision, ManifestSHA256: hash, SourceOrder: w.SourceOrder, Cases: make([]caseRecord, 0, len(w.Cases)), Limitations: []string{"AST and execution-input validation only", "This validator does not invoke the Go execution API", "Cold/warm/startup-prepared state preparation is not implemented by this validator", "No query result or P95 claim"}}
	for _, c := range w.Cases {
		input, err := w.Input(c, 60000)
		if err != nil {
			return err
		}
		record := caseRecord{Case: c, Input: input}
		ast, err := cypher.Parse(c.Query)
		if err != nil {
			record.ParseError = err.Error()
			r.ParseFailures++
		} else {
			record.AST = typedAST(reflect.ValueOf(ast))
		}
		r.Cases = append(r.Cases, record)
	}
	f, err := os.OpenFile(*output, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return err
	}
	encoder := json.NewEncoder(f)
	err = encoder.Encode(r)
	closeErr := f.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	fmt.Printf("%d cases preserved; %d parse failures; no graph loads/query execution/performance measurement\n", len(r.Cases), r.ParseFailures)
	if r.ParseFailures != 0 {
		return fmt.Errorf("testcase syntax validation failed")
	}
	return nil
}

// Include concrete expression kinds: Parameter{Name} and Variable{Name} must
// remain distinguishable in a reviewable AST receipt.
func typedAST(v reflect.Value) any {
	if !v.IsValid() {
		return nil
	}
	switch v.Kind() {
	case reflect.Interface, reflect.Pointer:
		if v.IsNil() {
			return nil
		}
		return typedAST(v.Elem())
	case reflect.Struct:
		fields := map[string]any{}
		for i := 0; i < v.NumField(); i++ {
			if v.Type().Field(i).IsExported() {
				fields[v.Type().Field(i).Name] = typedAST(v.Field(i))
			}
		}
		return map[string]any{"kind": v.Type().Name(), "fields": fields}
	case reflect.Slice, reflect.Array:
		if v.Kind() == reflect.Slice && v.IsNil() {
			return nil
		}
		items := make([]any, v.Len())
		for i := range items {
			items[i] = typedAST(v.Index(i))
		}
		return items
	case reflect.Map:
		if v.IsNil() {
			return nil
		}
		items := map[string]any{}
		iter := v.MapRange()
		for iter.Next() {
			items[iter.Key().String()] = typedAST(iter.Value())
		}
		return items
	default:
		return v.Interface()
	}
}
