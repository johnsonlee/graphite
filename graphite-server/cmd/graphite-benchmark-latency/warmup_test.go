package main

import (
	"os"
	"testing"

	"github.com/johnsonlee/graphite/graphite-server/internal/benchmarkcase"
)

func TestDiagnosticWarmupContinuationRequiresExactOriginalFailure(t *testing.T) {
	data, err := os.ReadFile("../../internal/benchmarkcase/testdata/main64.json")
	if err != nil {
		t.Fatal(err)
	}
	workload, err := benchmarkcase.Decode(data)
	if err != nil {
		t.Fatal(err)
	}
	records := make([]caseRecord, len(workload.Cases))
	for i, c := range workload.Cases {
		records[i] = caseRecord{Index: i, ID: c.ID, Outcome: "SUCCESS"}
	}
	if knownOriginalWarmupFailure(records, workload) {
		t.Fatal("successful warmup is not the pinned failed warmup")
	}
	records[821].Outcome = "FAILED"
	records[821].Error = "IllegalStateException"
	records[821].Message = "Unsafe expression reached parallel string projection"
	records[821].Digest = "java.lang.IllegalStateException"
	if !knownOriginalWarmupFailure(records, workload) {
		t.Fatal("original failure rejected")
	}
	for name, mutate := range map[string]func([]caseRecord){
		"missing tail":   func(r []caseRecord) { r[1266] = caseRecord{} },
		"changed order":  func(r []caseRecord) { r[0], r[1] = r[1], r[0] },
		"new failure":    func(r []caseRecord) { r[0].Outcome = "FAILED" },
		"timeout":        func(r []caseRecord) { r[0].CensoredTimeout = true },
		"row constraint": func(r []caseRecord) { r[0].ValidationError = "expected zero rows" },
		"other message":  func(r []caseRecord) { r[821].Message = "another error" },
		"other class":    func(r []caseRecord) { r[821].Error = "CypherException" },
		"other digest":   func(r []caseRecord) { r[821].Digest = "timeout" },
	} {
		t.Run(name, func(t *testing.T) {
			copy := append([]caseRecord(nil), records...)
			mutate(copy)
			if knownOriginalWarmupFailure(copy, workload) {
				t.Fatal("invalid warmup permitted diagnostic replay")
			}
		})
	}
	if knownOriginalWarmupFailure(records[:1266], workload) {
		t.Fatal("incomplete warmup accepted")
	}
}
