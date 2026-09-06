package main

import (
	"bytes"
	"errors"
	"flag"
	"io"
	"reflect"
	"testing"
)

func TestServeVersionAndOptionalServices(t *testing.T) {
	for _, option := range []string{"--version", "-V"} {
		var output bytes.Buffer
		_, err := parseConfig([]string{option}, &output)
		if !errors.Is(err, flag.ErrHelp) || output.String() != "" {
			t.Fatalf("version %s: %q %v", option, output.String(), err)
		}
	}
	c, err := parseConfig([]string{"--metrics", "--data", "/tmp", "--topology", "/queries"}, io.Discard)
	if err != nil || !c.metrics || c.topology != "/queries" {
		t.Fatalf("optional services: %+v %v", c, err)
	}
}

func TestServeOptionsAreInterspersedAndRepeatable(t *testing.T) {
	c, err := parseConfig([]string{"serve", "--id", "app", "/data/app", "--graph", "a:/graphs/a", "--graph=b:graphs/b", "-p", "9090", "--max-concurrent-cypher", "8", "--cypher-max-timeout-ms", "1200", "--cypher-work-budget", "0"}, io.Discard)
	if err != nil {
		t.Fatal(err)
	}
	if c.id != "app" || c.positional != "/data/app" || c.data != "/data" || c.port != 9090 || c.maxConcurrent != 8 || c.timeoutMillis != 1200 || c.loadMode != "MAPPED" || !reflect.DeepEqual(c.graphs, graphSpecs{"a:/graphs/a", "b:graphs/b"}) {
		t.Fatalf("config: %+v", c)
	}
}
func TestServeRejectsInvalidStartupConfiguration(t *testing.T) {
	for _, args := range [][]string{
		{}, {"/graph"}, {"--data", "/tmp", "--max-concurrent-cypher", "0"}, {"--data", "/tmp", "--cypher-max-timeout-ms", "-1"}, {"--data", "/tmp", "--load-mode", "bogus"}, {"--data", "/tmp", "a", "b"}, {"--data"},
	} {
		if _, err := parseConfig(args, io.Discard); err == nil {
			t.Errorf("accepted %q", args)
		}
	}
}
