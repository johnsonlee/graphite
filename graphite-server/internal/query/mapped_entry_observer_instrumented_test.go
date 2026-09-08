//go:build mapped_entry_instrumented

package query

const mappedEntryInstrumented = true

// The symbol is supplied exclusively by the documented frozen-copy overlay.
func mappedEntryInstallObserver(observer func(*ExecutionContext, int64, string, any)) {
	mappedEntryConsumeObserver = observer
}
