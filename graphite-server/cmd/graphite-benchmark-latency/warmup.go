package main

import "github.com/johnsonlee/graphite/graphite-server/internal/benchmarkcase"

// Only the unchanged pinned workload's original failure permits the separate
// diagnostic continuation. It never turns a failed warmup into a passed gate.
func knownOriginalWarmupFailure(records []caseRecord, workload benchmarkcase.Workload) bool {
	if len(records) != 1267 || len(workload.Cases) != len(records) {
		return false
	}
	for i, record := range records {
		if record.Index != i || record.ID != workload.Cases[i].ID || record.CensoredTimeout || record.ValidationError != "" {
			return false
		}
		if i == 821 {
			if record.ID != "four-or-graph-id-targeted" || record.Outcome != "FAILED" || record.Error != "IllegalStateException" || record.Message != "Unsafe expression reached parallel string projection" || record.Digest != "java.lang.IllegalStateException" {
				return false
			}
		} else if record.Outcome != "SUCCESS" {
			return false
		}
	}
	return true
}
