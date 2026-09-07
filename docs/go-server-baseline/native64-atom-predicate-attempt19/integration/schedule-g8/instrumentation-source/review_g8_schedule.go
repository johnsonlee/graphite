package query

import "context"

// Test instrumentation only. The experiment installs this before ExecuteCross
// and removes it after all source tasks have joined. It never changes task inputs.
var reviewG8ScheduleHook func(context.Context, int, int) func()

func reviewG8TaskSchedule(ctx context.Context, count, index int) func() {
	if hook := reviewG8ScheduleHook; hook != nil {
		return hook(ctx, count, index)
	}
	return func() {}
}
