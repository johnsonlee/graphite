package main

import (
	"context"
	"fmt"
	"github.com/johnsonlee/graphite/graphite-server/internal/profiling"
	"io"
)

// Profile startup/help/parser work too. Stop runs before main considers os.Exit,
// including after a handled termination signal has made execute return.
func executeProfiled(ctx context.Context, args []string, stdout, stderr io.Writer, getenv func(string) string) (code int) {
	if getenv("GRAPHITE_NATIVE_CPU_PROFILE") != "1" {
		return execute(ctx, args, stdout, stderr)
	}
	path := getenv("GRAPHITE_PROFILE")
	if path == "" {
		path = "profile.html"
	}
	session, err := profiling.Start(path)
	if err != nil {
		fmt.Fprintln(stderr, "Error:", err)
		return 1
	}
	defer func() {
		if err := session.Stop(); err != nil {
			fmt.Fprintln(stderr, "Error:", err)
			code = 1
		}
	}()
	return execute(ctx, args, stdout, stderr)
}
