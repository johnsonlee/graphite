package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestGraphManifestPreserves64DistinctSources(t *testing.T) {
	dir := t.TempDir()
	order := make([]string, 64)
	rows := make([][]string, 64)
	for i := range order {
		order[i] = fmt.Sprintf("g%d", i)
		path := filepath.Join(dir, order[i])
		if err := os.Mkdir(path, 0700); err != nil {
			t.Fatal(err)
		}
		rows[i] = []string{order[i], path, "absent", "target", "dense", "identity"}
	}
	write := func(rows [][]string) string {
		lines := []string{"# main source order"}
		for _, row := range rows {
			lines = append(lines, strings.Join(row, "\t"))
		}
		path := filepath.Join(t.TempDir(), "graphs.tsv")
		if err := os.WriteFile(path, []byte(strings.Join(lines, "\n")+"\n"), 0600); err != nil {
			t.Fatal(err)
		}
		return path
	}
	sources, err := graphSources(write(rows), order)
	if err != nil {
		t.Fatal(err)
	}
	for i, s := range sources {
		physical, err := filepath.EvalSymlinks(rows[i][1])
		if err != nil {
			t.Fatal(err)
		}
		if s.ID != order[i] || s.Path != physical {
			t.Fatalf("source %d changed: %+v", i, s)
		}
	}
	for _, name := range []string{"missing", "order", "columns", "relative", "duplicate", "symlink-alias", "empty"} {
		t.Run(name, func(t *testing.T) {
			changed := make([][]string, len(rows))
			for i, row := range rows {
				changed[i] = append([]string{}, row...)
			}
			switch name {
			case "missing":
				changed = changed[:63]
			case "order":
				changed[0], changed[1] = changed[1], changed[0]
			case "columns":
				changed[0] = changed[0][:5]
			case "relative":
				changed[0][1] = "g0"
			case "duplicate":
				changed[63][1] = changed[0][1]
			case "symlink-alias":
				path := filepath.Join(dir, "alias")
				if err := os.Symlink(changed[0][1], path); err != nil {
					t.Fatal(err)
				}
				changed[63][1] = path
			case "empty":
				changed[0][3] = ""
			}
			if _, err := graphSources(write(changed), order); err == nil {
				t.Fatal("accepted invalid source manifest")
			}
		})
	}
}
