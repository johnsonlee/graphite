// Copyright 2026 Graphite contributors. SPDX-License-Identifier: Apache-2.0
package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"runtime"
	"strings"
	"testing"
)

var probeBinary string

func TestMain(m *testing.M) {
	dir, err := os.MkdirTemp("", "native-package-probe-")
	if err != nil {
		panic(err)
	}
	source := filepath.Join(dir, "cmd", "graphite-server", "main.go")
	if err := os.MkdirAll(filepath.Dir(source), 0755); err != nil {
		panic(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "go.mod"), []byte("module github.com/johnsonlee/graphite/graphite-server\n\ngo 1.22\n"), 0644); err != nil {
		panic(err)
	}
	if err := os.WriteFile(source, []byte("package main\nimport \"fmt\"\nvar version = \"unknown\"\nfunc main() { fmt.Print(version) }\n"), 0644); err != nil {
		panic(err)
	}
	probeBinary = filepath.Join(dir, "probe")
	command := exec.Command("go", "build", "-trimpath", "-ldflags", "-X main.version=test-version", "-o", probeBinary, "./cmd/graphite-server")
	command.Dir = dir
	command.Env = buildEnvironment(os.Environ(), runtime.GOOS, runtime.GOARCH)
	if output, err := command.CombinedOutput(); err != nil {
		fmt.Fprintf(os.Stderr, "%s: %v\n", output, err)
		os.RemoveAll(dir)
		os.Exit(1)
	}
	code := m.Run()
	os.RemoveAll(dir)
	os.Exit(code)
}

func TestTargetsAndVersion(t *testing.T) {
	got, err := parseTargets("linux-arm64,darwin-amd64")
	if err != nil || !reflect.DeepEqual(got, []string{"darwin-amd64", "linux-arm64"}) {
		t.Fatalf("%v: %v", got, err)
	}
	host, err := parseTargets("host")
	if err != nil || len(host) != 1 || host[0] != runtime.GOOS+"-"+runtime.GOARCH {
		t.Fatalf("%v: %v", host, err)
	}
	for _, s := range []string{"", "windows-amd64", "linux-arm64,linux-arm64", "../linux-arm64", "linux-386", "linux-arm64,"} {
		if _, err := parseTargets(s); err == nil {
			t.Errorf("accepted %q", s)
		}
	}
	for _, s := range []string{"", "1.0 -X bad", "$(touch x)", "a\nb", "a/b"} {
		if validVersion(s) {
			t.Errorf("accepted version %q", s)
		}
	}
	if !validVersion("1.2.3-SNAPSHOT+abc") {
		t.Fatal("normal version rejected")
	}
}

func resourceFixture(t *testing.T) (string, []string) {
	t.Helper()
	dir := filepath.Join(t.TempDir(), "graphite-native")
	targets, err := parseTargets("host")
	if err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(dir, targets[0]), 0755); err != nil {
		t.Fatal(err)
	}
	if err := copyFile(probeBinary, filepath.Join(dir, targets[0], "graphite-server"), 0755); err != nil {
		t.Fatal(err)
	}
	manifest, err := checksums(dir, targets)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "SHA256SUMS"), []byte(manifest), 0644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "VERSION"), []byte("test-version\n"), 0644); err != nil {
		t.Fatal(err)
	}
	return dir, targets
}

func TestVerifiedPrebuiltCopyAndCorruption(t *testing.T) {
	source, targets := resourceFixture(t)
	if err := verify(source, "test-version", targets); err != nil {
		t.Fatal(err)
	}
	output := filepath.Join(t.TempDir(), "graphite-native")
	if err := prepare(output, "test-version", "host", source); err != nil {
		t.Fatal(err)
	}
	if err := verify(output, "test-version", targets); err != nil {
		t.Fatal(err)
	}
	before, _ := os.ReadFile(filepath.Join(output, "SHA256SUMS"))
	if err := os.WriteFile(filepath.Join(source, "SHA256SUMS"), []byte(strings.Repeat("0", 64)+"  "+targets[0]+"/graphite-server\n"), 0644); err != nil {
		t.Fatal(err)
	}
	if err := prepare(output, "test-version", "host", source); err == nil {
		t.Fatal("copied corrupt resource")
	}
	after, _ := os.ReadFile(filepath.Join(output, "SHA256SUMS"))
	if string(before) != string(after) {
		t.Fatal("failed verification destroyed previous complete output")
	}
}

func TestManifestRequiresExactVersionTargetsAndPathSet(t *testing.T) {
	source, targets := resourceFixture(t)
	if err := verify(source, "other-version", targets); err == nil {
		t.Fatal("accepted wrong version")
	}
	for _, mutate := range []func(string){
		func(dir string) { os.WriteFile(filepath.Join(dir, "unlisted"), []byte("unexpected"), 0644) },
		func(dir string) { os.Symlink("VERSION", filepath.Join(dir, "link")) },
		func(dir string) {
			file := filepath.Join(dir, "SHA256SUMS")
			b, _ := os.ReadFile(file)
			os.WriteFile(file, append(b, b...), 0644)
		},
		func(dir string) {
			os.WriteFile(filepath.Join(dir, targets[0], "graphite-server"), []byte("truncated"), 0755)
		},
	} {
		dir, ts := resourceFixture(t)
		mutate(dir)
		if err := verify(dir, "test-version", ts); err == nil {
			t.Fatal("accepted invalid resource")
		}
	}
}

func TestManifestCannotRelabelTheBinaryVersion(t *testing.T) {
	source, targets := resourceFixture(t)
	if err := os.WriteFile(filepath.Join(source, "VERSION"), []byte("next-version\n"), 0644); err != nil {
		t.Fatal(err)
	}
	// Same byte length exercises the actual string bytes, not only header length.
	if err := verify(source, "next-version", targets); err == nil || !strings.Contains(err.Error(), "binary version") {
		t.Fatalf("accepted a different version label over unchanged binary bytes: %v", err)
	}
	output := filepath.Join(t.TempDir(), "graphite-native")
	if err := prepare(output, "next-version", "host", source); err == nil {
		t.Fatal("copied a mislabeled native executable")
	}
	if _, err := os.Stat(output); !os.IsNotExist(err) {
		t.Fatalf("rejected input published output: %v", err)
	}
}

func TestArchitectureAndBuildEnvironment(t *testing.T) {
	source, targets := resourceFixture(t)
	wrongArch := "amd64"
	if runtime.GOARCH == "amd64" {
		wrongArch = "arm64"
	}
	if err := checkArchitecture(filepath.Join(source, targets[0], "graphite-server"), runtime.GOOS+"-"+wrongArch); err == nil {
		t.Fatal("accepted wrong architecture")
	}
	env := buildEnvironment([]string{"PATH=/bin", "CGO_ENABLED=1", "GOOS=wrong", "GOARCH=wrong", "GOAMD64=v4", "GOARM64=v9.0", "GOFLAGS=-race", "APP=ok"}, "linux", "arm64")
	want := []string{"PATH=/bin", "APP=ok", "CGO_ENABLED=0", "GOOS=linux", "GOARCH=arm64", "GOAMD64=v1", "GOARM64=v8.0", "GOFLAGS="}
	if !reflect.DeepEqual(env, want) {
		t.Fatalf("%v", env)
	}
	if err := prepare(t.TempDir(), "test", "host", ""); err == nil {
		t.Fatal("accepted unsafe generated directory name")
	}
	if err := prepare(source, "test-version", "host", source); err == nil {
		t.Fatal("allowed source/output alias")
	}
}
