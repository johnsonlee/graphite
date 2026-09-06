package main

import (
	"bytes"
	"context"
	"encoding/json"
	"flag"
	"os"
	"reflect"
	"testing"
)

func TestPinnedMainCLI(t *testing.T) {
	var cases []struct {
		Mode           string
		Args           []string
		ExitCode       int
		Stdout, Stderr string
	}
	data, err := os.ReadFile("testdata/cli/main.json")
	if err != nil {
		t.Fatal(err)
	}
	if err = json.Unmarshal(data, &cases); err != nil {
		t.Fatal(err)
	}
	for _, test := range cases {
		args := test.Args
		if test.Mode == "serve" {
			args = append([]string{"serve"}, args...)
		}
		t.Run(test.Mode+"/"+string(mustJSON(test.Args)), func(t *testing.T) {
			var stdout, stderr bytes.Buffer
			code := execute(context.Background(), args, &stdout, &stderr)
			if code != test.ExitCode || stdout.String() != test.Stdout || stderr.String() != test.Stderr {
				t.Errorf("args=%q\ncode=%d want=%d\nstdout=%q want=%q\nstderr=%q want=%q", args, code, test.ExitCode, stdout.String(), test.Stdout, stderr.String(), test.Stderr)
			}
		})
	}
}
func mustJSON(value any) []byte { b, _ := json.Marshal(value); return b }

func TestProvidedEmptyPathsAndIDsRemainProvided(t *testing.T) {
	c, err := parseConfig([]string{"--data", ""}, &bytes.Buffer{})
	if err != nil || c.data != "." || !c.hasData {
		t.Fatalf("empty data: %+v %v", c, err)
	}
	c, err = parseConfig([]string{"--id", "app", ""}, &bytes.Buffer{})
	if err != nil || c.positional != "." || !c.hasPositional {
		t.Fatalf("empty positional: %+v %v", c, err)
	}
	c, err = parseConfig([]string{"--id", "", "graph"}, &bytes.Buffer{})
	if err != nil || !c.hasID || c.id != "" {
		t.Fatalf("empty ID must reach graph-ID validation: %+v %v", c, err)
	}
}

func TestArgumentFileParsedValues(t *testing.T) {
	c, err := parseServeOptions([]string{"@testdata/cli/help.args"}, &bytes.Buffer{})
	if err != flag.ErrHelp || c.data != "space path" || !reflect.DeepEqual(c.graphs, graphSpecs{"a:another path"}) {
		t.Fatalf("argfile values: %+v %v", c, err)
	}
	c, err = parseServeOptions([]string{"@testdata/cli/quoted.args"}, &bytes.Buffer{})
	if err != flag.ErrHelp || c.port != 12 {
		t.Fatalf("argfile octal escape: %+v %v", c, err)
	}
	c, err = parseServeOptions([]string{"@@literal"}, &bytes.Buffer{})
	if err != nil || c.positional != "@literal" {
		t.Fatalf("escaped @: %+v %v", c, err)
	}
}
