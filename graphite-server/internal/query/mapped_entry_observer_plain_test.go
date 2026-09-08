//go:build !mapped_entry_instrumented

package query

const mappedEntryInstrumented = false

func mappedEntryInstallObserver(observer func(*ExecutionContext, int64, string, any)) {}
