// Child process correctness probe, never a performance fixture.
package main

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/signal"
	"syscall"
)

func main() {
	cwd, _ := os.Getwd()
	record := map[string]any{"pid": os.Getpid(), "executable": os.Args[0], "args": os.Args[1:], "cwd": cwd, "environment": os.Getenv("GRAPHITE_BRIDGE_TEST_VALUE")}
	mode := os.Getenv("GRAPHITE_BRIDGE_TEST_MODE")
	signals := make(chan os.Signal, 2)
	signal.Notify(signals, os.Interrupt, syscall.SIGTERM)
	if mode == "normal" {
		data, _ := io.ReadAll(os.Stdin)
		record["stdin"] = string(data)
	}
	data, _ := json.Marshal(record)
	_ = os.WriteFile(os.Getenv("GRAPHITE_BRIDGE_TEST_RECORD"), data, 0600)
	fmt.Fprintln(os.Stdout, "native child stdout")
	fmt.Fprintln(os.Stderr, "native child stderr")
	if mode == "normal" {
		os.Exit(37)
	}
	sig := <-signals
	_ = os.WriteFile(os.Getenv("GRAPHITE_BRIDGE_TEST_SIGNAL"), []byte(sig.String()), 0600)
	if mode == "ignore" {
		for {
			<-signals
		}
	}
	os.Exit(24)
}
