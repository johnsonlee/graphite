package main

import (
	"bufio"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

type graphSource struct{ ID, Path string }

func graphSources(path string, order []string) ([]graphSource, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	scanner := bufio.NewScanner(f)
	sources := []graphSource{}
	paths := map[string]bool{}
	for scanner.Scan() {
		line := scanner.Text()
		if strings.HasPrefix(line, "#") || strings.TrimSpace(line) == "" {
			continue
		}
		cols := strings.Split(line, "\t")
		if len(cols) != 6 {
			return nil, fmt.Errorf("graph manifest requires six columns")
		}
		for _, v := range cols {
			if strings.TrimSpace(v) == "" {
				return nil, fmt.Errorf("empty graph manifest field")
			}
		}
		if len(sources) >= len(order) || cols[0] != order[len(sources)] {
			return nil, fmt.Errorf("source order differs from main at %d", len(sources))
		}
		if !filepath.IsAbs(cols[1]) {
			return nil, fmt.Errorf("graph path must be absolute")
		}
		path, err := filepath.EvalSymlinks(cols[1])
		if err != nil {
			return nil, err
		}
		if paths[path] {
			return nil, fmt.Errorf("repeated physical graph path %s", path)
		}
		paths[path] = true
		sources = append(sources, graphSource{cols[0], path})
	}
	if err := scanner.Err(); err != nil {
		return nil, err
	}
	if len(sources) != 64 {
		return nil, fmt.Errorf("expected 64 graphs, got %d", len(sources))
	}
	return sources, nil
}
