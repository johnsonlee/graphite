package main

import (
	"compress/gzip"
	"errors"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func TestMutexWindowGlobalBoundaryAndControl(t *testing.T) {
	old := runtime.SetMutexProfileFraction(0)
	defer runtime.SetMutexProfileFraction(old)
	for _, rate := range []int{0, 16} {
		dir := t.TempDir()
		capture, err := prepareMutexCapture(dir, rate)
		if err != nil {
			t.Fatal(err)
		}
		if got := runtime.SetMutexProfileFraction(-1); got != 0 {
			t.Fatal("sampling began during preparation", got)
		}
		capture.Start()
		if got := runtime.SetMutexProfileFraction(-1); got != rate {
			t.Fatal("wrong sampling rate", got)
		}
		capture.Stop()
		if got := runtime.SetMutexProfileFraction(-1); got != 0 {
			t.Fatal("sampling includes serialization", got)
		}
		if err := capture.Write(); err != nil {
			t.Fatal(err)
		}
		capture.Release()
		for _, name := range []string{"mutex-before.pprof", "mutex-after.pprof"} {
			f, err := os.Open(filepath.Join(dir, name))
			if err != nil {
				t.Fatal(err)
			}
			z, err := gzip.NewReader(f)
			if err != nil {
				t.Fatal(err)
			}
			b, err := io.ReadAll(z)
			z.Close()
			f.Close()
			if err != nil || len(b) == 0 {
				t.Fatal("missing profile protobuf", len(b), err)
			}
		}
	}
}
func TestMutexWindowRejectsForeignOwnerAndBadRate(t *testing.T) {
	old := runtime.SetMutexProfileFraction(7)
	defer runtime.SetMutexProfileFraction(old)
	if _, err := prepareMutexCapture(t.TempDir(), 16); err == nil {
		t.Fatal("foreign sampler overridden")
	}
	if got := runtime.SetMutexProfileFraction(-1); got != 7 {
		t.Fatal(got)
	}
	runtime.SetMutexProfileFraction(0)
	if _, err := prepareMutexCapture(t.TempDir(), -1); err == nil {
		t.Fatal("negative rate accepted")
	}
	capture, err := prepareMutexCapture(t.TempDir(), 16)
	if err != nil {
		t.Fatal(err)
	}
	defer capture.Release()
	if _, err := prepareMutexCapture(t.TempDir(), 1); err == nil {
		t.Fatal("concurrent owner accepted")
	}
}
func TestMutexWindowPanicRestoresSettingWithoutReplacingFailure(t *testing.T) {
	old := runtime.SetMutexProfileFraction(0)
	defer runtime.SetMutexProfileFraction(old)
	marker := errors.New("query failure")
	var failure any
	func() {
		defer func() { failure = recover() }()
		capture, err := prepareMutexCapture(t.TempDir(), 16)
		if err != nil {
			t.Fatal(err)
		}
		defer capture.Release()
		capture.Start()
		panic(marker)
	}()
	if failure != marker || runtime.SetMutexProfileFraction(-1) != 0 || mutexWindowActive.Load() {
		t.Fatal("failure/rate/ownership changed", failure)
	}
}
func TestMutexWindowPreparationFailureReleasesOwnership(t *testing.T) {
	old := runtime.SetMutexProfileFraction(0)
	defer runtime.SetMutexProfileFraction(old)
	bad := filepath.Join(t.TempDir(), "absent", "nested")
	if _, err := prepareMutexCapture(bad, 16); err == nil {
		t.Fatal("missing directory accepted")
	}
	if mutexWindowActive.Load() || runtime.SetMutexProfileFraction(-1) != 0 {
		t.Fatal("failed preparation retained global state")
	}
}
