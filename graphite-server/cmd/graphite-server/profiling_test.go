package main

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestProfilingDisabledPreservesCLI(t *testing.T) {
	for _, enabled := range []string{"", "0", "true", "01"} {
		t.Run(enabled, func(t *testing.T) {
			var stdout, stderr, wantout, wanterr bytes.Buffer
			args := []string{"--help"}
			want := execute(context.Background(), args, &wantout, &wanterr)
			got := executeProfiled(context.Background(), args, &stdout, &stderr, func(k string) string {
				if k == "GRAPHITE_NATIVE_CPU_PROFILE" {
					return enabled
				}
				return "/does-not-exist/report.html"
			})
			if got != want || stdout.String() != wantout.String() || stderr.String() != wanterr.String() {
				t.Fatalf("CLI changed: %d %q %q", got, stdout.String(), stderr.String())
			}
		})
	}
}
func TestProfilingHelpAndOutputFailure(t *testing.T) {
	for _, invalid := range []bool{false, true} {
		t.Run(map[bool]string{false: "help", true: "invalid-output"}[invalid], func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "profile.html")
			if invalid {
				path = filepath.Join(path, "missing", "profile.html")
			}
			var stdout, stderr bytes.Buffer
			code := executeProfiled(context.Background(), []string{"--help"}, &stdout, &stderr, func(k string) string {
				if k == "GRAPHITE_NATIVE_CPU_PROFILE" {
					return "1"
				}
				return path
			})
			if invalid {
				if code == 0 || !strings.Contains(stderr.String(), "start CPU profile") {
					t.Fatalf("%d/%s", code, stderr.String())
				}
				return
			}
			data, err := os.ReadFile(path)
			if code != 0 || err != nil || !bytes.Contains(data, []byte("profile-data")) {
				t.Fatalf("help profile %d/%v/%s", code, err, stderr.String())
			}
		})
	}
}
