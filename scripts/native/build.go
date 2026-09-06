// Copyright 2026 Graphite contributors. SPDX-License-Identifier: Apache-2.0
// Run from the repository root: go run scripts/native/build.go --version VERSION
// This tool uses only the Go standard library; it does not execute cross-built binaries.
package main

import (
	"crypto/sha256"
	"debug/buildinfo"
	"debug/elf"
	"debug/macho"
	"encoding/binary"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
)

const allTargets = "darwin-amd64,darwin-arm64,linux-amd64,linux-arm64"

func main() {
	output := flag.String("output", "graphite-explore/build/generated/native-resources/graphite-native", "generated resource directory (must end in graphite-native)")
	version := flag.String("version", "", "required package version")
	targets := flag.String("targets", allTargets, "comma-separated OS-ARCH targets, or host")
	prebuilt := flag.String("prebuilt", "", "verify and copy a previously built graphite-native directory")
	flag.Parse()
	if flag.NArg() != 0 {
		fail(fmt.Errorf("unexpected arguments: %v", flag.Args()))
	}
	if err := prepare(*output, *version, *targets, *prebuilt); err != nil {
		fail(err)
	}
}

func fail(err error) { fmt.Fprintln(os.Stderr, "native packaging:", err); os.Exit(1) }

func parseTargets(value string) ([]string, error) {
	if value == "host" {
		value = runtime.GOOS + "-" + runtime.GOARCH
	}
	valid := strings.Split(allTargets, ",")
	seen := map[string]bool{}
	for _, target := range strings.Split(value, ",") {
		found := false
		for _, v := range valid {
			if v == target {
				found = true
			}
		}
		if !found || seen[target] {
			return nil, fmt.Errorf("unsupported or duplicate target %q (supported: %s)", target, allTargets)
		}
		seen[target] = true
	}
	result := make([]string, 0, len(seen))
	for v := range seen {
		result = append(result, v)
	}
	sort.Strings(result)
	return result, nil
}

func validVersion(value string) bool {
	if value == "" {
		return false
	}
	for _, c := range value {
		if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || strings.ContainsRune("._+-", c)) {
			return false
		}
	}
	return true
}

func prepare(output, version, targetArg, prebuilt string) error {
	if !validVersion(version) {
		return fmt.Errorf("invalid or empty version %q", version)
	}
	targets, err := parseTargets(targetArg)
	if err != nil {
		return err
	}
	output, err = filepath.Abs(output)
	if err != nil {
		return err
	}
	if filepath.Base(output) != "graphite-native" {
		return errors.New("output directory must be named graphite-native")
	}
	if prebuilt != "" {
		prebuilt, err = filepath.Abs(prebuilt)
		if err != nil {
			return err
		}
		if prebuilt == output {
			return errors.New("prebuilt and output directories must differ")
		}
		if err := verify(prebuilt, version, targets); err != nil {
			return fmt.Errorf("prebuilt verification: %w", err)
		}
	}
	if err := os.MkdirAll(filepath.Dir(output), 0755); err != nil {
		return err
	}
	staging, err := os.MkdirTemp(filepath.Dir(output), ".graphite-native-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(staging)
	for _, target := range targets {
		targetDir := filepath.Join(staging, target)
		if err := os.Mkdir(targetDir, 0755); err != nil {
			return err
		}
		binary := filepath.Join(targetDir, "graphite-server")
		if prebuilt == "" {
			parts := strings.Split(target, "-")
			cmd := exec.Command("go", "build", "-trimpath", "-buildvcs=false", "-ldflags", "-X main.version="+version, "-o", binary, "./cmd/graphite-server")
			cmd.Dir = "graphite-server"
			cmd.Env = buildEnvironment(os.Environ(), parts[0], parts[1])
			cmd.Stdout, cmd.Stderr = os.Stdout, os.Stderr
			fmt.Fprintln(os.Stderr, "Building native server", target, version)
			if err := cmd.Run(); err != nil {
				return fmt.Errorf("build %s: %w", target, err)
			}
		} else {
			if err := copyFile(filepath.Join(prebuilt, target, "graphite-server"), binary, 0755); err != nil {
				return err
			}
		}
	}
	manifest, err := checksums(staging, targets)
	if err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(staging, "SHA256SUMS"), []byte(manifest), 0644); err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(staging, "VERSION"), []byte(version+"\n"), 0644); err != nil {
		return err
	}
	if err := verify(staging, version, targets); err != nil {
		return err
	}
	// Replace only this task's generated directory, after every binary has passed
	// validation. Failed builds leave the last complete directory intact.
	if err := os.RemoveAll(output); err != nil {
		return err
	}
	return os.Rename(staging, output)
}

func buildEnvironment(env []string, goos, goarch string) []string {
	result := make([]string, 0, len(env)+5)
	for _, e := range env {
		key, _, _ := strings.Cut(e, "=")
		// Do not let a developer's cross-compile tuning or Go flags produce a
		// differently targeted or dynamically linked release binary.
		switch key {
		case "CGO_ENABLED", "GOOS", "GOARCH", "GOAMD64", "GOARM64", "GOFLAGS":
			continue
		}
		result = append(result, e)
	}
	return append(result, "CGO_ENABLED=0", "GOOS="+goos, "GOARCH="+goarch, "GOAMD64=v1", "GOARM64=v8.0", "GOFLAGS=")
}

func copyFile(source, dest string, mode os.FileMode) error {
	in, err := os.Open(source)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(dest, os.O_CREATE|os.O_EXCL|os.O_WRONLY, mode)
	if err != nil {
		return err
	}
	_, err = io.Copy(out, in)
	closeErr := out.Close()
	if err != nil {
		return err
	}
	return closeErr
}

func checksums(dir string, targets []string) (string, error) {
	var result strings.Builder
	for _, target := range targets {
		path := filepath.Join(dir, target, "graphite-server")
		info, err := os.Lstat(path)
		if err != nil {
			return "", err
		}
		if !info.Mode().IsRegular() || info.Size() <= 0 || info.Size() > 128<<20 {
			return "", fmt.Errorf("invalid native binary %s", path)
		}
		if err := checkArchitecture(path, target); err != nil {
			return "", err
		}
		file, err := os.Open(path)
		if err != nil {
			return "", err
		}
		h := sha256.New()
		_, err = io.Copy(h, file)
		closeErr := file.Close()
		if err != nil {
			return "", err
		}
		if closeErr != nil {
			return "", closeErr
		}
		fmt.Fprintf(&result, "%x  %s/graphite-server\n", h.Sum(nil), target)
	}
	return result.String(), nil
}

func checkArchitecture(path, target string) error {
	parts := strings.Split(target, "-")
	if parts[0] == "linux" {
		file, err := elf.Open(path)
		if err != nil {
			return err
		}
		defer file.Close()
		machine := elf.EM_X86_64
		if parts[1] == "arm64" {
			machine = elf.EM_AARCH64
		}
		if file.Machine != machine || file.Class != elf.ELFCLASS64 {
			return fmt.Errorf("wrong ELF architecture for %s", target)
		}
		for _, p := range file.Progs {
			if p.Type == elf.PT_INTERP {
				return fmt.Errorf("%s has a dynamic interpreter", target)
			}
		}
	} else {
		file, err := macho.Open(path)
		if err != nil {
			return err
		}
		defer file.Close()
		cpu := macho.CpuAmd64
		if parts[1] == "arm64" {
			cpu = macho.CpuArm64
		}
		if file.Cpu != cpu {
			return fmt.Errorf("wrong Mach-O architecture for %s", target)
		}
	}
	return nil
}

func verify(dir, version string, targets []string) error {
	versionBytes, err := os.ReadFile(filepath.Join(dir, "VERSION"))
	if err != nil {
		return err
	}
	if string(versionBytes) != version+"\n" {
		return errors.New("prebuilt version does not match requested version")
	}
	for _, target := range targets {
		if err := checkBinaryVersion(filepath.Join(dir, target, "graphite-server"), target, version); err != nil {
			return fmt.Errorf("%s version: %w", target, err)
		}
	}
	expected, err := checksums(dir, targets)
	if err != nil {
		return err
	}
	actual, err := os.ReadFile(filepath.Join(dir, "SHA256SUMS"))
	if err != nil {
		return err
	}
	if string(actual) != expected {
		return errors.New("SHA256SUMS does not match the exact requested targets and bytes")
	}
	wanted := map[string]bool{".": true, "VERSION": true, "SHA256SUMS": true}
	for _, target := range targets {
		wanted[target] = true
		wanted[target+"/graphite-server"] = true
	}
	return filepath.WalkDir(dir, func(path string, entry os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		rel, err := filepath.Rel(dir, path)
		if err != nil {
			return err
		}
		if !wanted[filepath.ToSlash(rel)] || entry.Type()&os.ModeSymlink != 0 {
			return fmt.Errorf("unexpected prebuilt resource %q", rel)
		}
		return nil
	})
}

type virtualSection struct {
	address, size uint64
	open          func() io.ReadSeeker
}

// Read the initial Go string value without executing a cross-built artifact.
// Go 1.22 omits linker flags from buildinfo when -trimpath is enabled, so the
// VERSION sidecar or recorded -ldflags alone cannot establish main.version.
// Our resource builder preserves symbols and produces only 64-bit executables.
func checkBinaryVersion(path, target, version string) error {
	info, err := buildinfo.ReadFile(path)
	if err != nil {
		return err
	}
	if info.Path != "github.com/johnsonlee/graphite/graphite-server/cmd/graphite-server" {
		return fmt.Errorf("unexpected Go command %q", info.Path)
	}
	var address uint64
	var order binary.ByteOrder
	var sections []virtualSection
	if strings.HasPrefix(target, "linux-") {
		file, err := elf.Open(path)
		if err != nil {
			return err
		}
		defer file.Close()
		symbols, err := file.Symbols()
		if err != nil {
			return err
		}
		for _, symbol := range symbols {
			if symbol.Name == "main.version" {
				address = symbol.Value
			}
		}
		order = file.ByteOrder
		for _, section := range file.Sections {
			sections = append(sections, virtualSection{section.Addr, section.Size, section.Open})
		}
	} else {
		file, err := macho.Open(path)
		if err != nil {
			return err
		}
		defer file.Close()
		if file.Symtab == nil {
			return errors.New("missing native version symbols")
		}
		for _, symbol := range file.Symtab.Syms {
			if symbol.Name == "main.version" || symbol.Name == "_main.version" {
				address = symbol.Value
			}
		}
		order = file.ByteOrder
		for _, section := range file.Sections {
			sections = append(sections, virtualSection{section.Addr, section.Size, section.Open})
		}
	}
	if address == 0 {
		return errors.New("missing main.version symbol")
	}
	read := func(address, size uint64) ([]byte, error) {
		for _, section := range sections {
			if address < section.address || address-section.address > section.size || size > section.size-(address-section.address) {
				continue
			}
			r := section.open()
			if _, err := r.Seek(int64(address-section.address), io.SeekStart); err != nil {
				return nil, err
			}
			data := make([]byte, size)
			_, err := io.ReadFull(r, data)
			return data, err
		}
		return nil, errors.New("native version points outside file sections")
	}
	header, err := read(address, 16)
	if err != nil {
		return err
	}
	if order.Uint64(header[8:]) != uint64(len(version)) {
		return errors.New("binary version does not match requested version")
	}
	value, err := read(order.Uint64(header[:8]), uint64(len(version)))
	if err != nil {
		return err
	}
	if string(value) != version {
		return errors.New("binary version does not match requested version")
	}
	return nil
}
