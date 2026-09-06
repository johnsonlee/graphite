package main

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	"github.com/johnsonlee/graphite/graphite-server/internal/javastring"
)

// Picocli's default @file mode uses StreamTokenizer, not shell tokenization:
// whitespace through U+0020, # comments, separate quoted tokens, and quoted
// C/octal escapes. Nested paths resolve against the process working directory.
func expandArgumentFiles(args []string) ([]string, error) {
	out := []string{}
	visited := map[string]bool{}
	var expand func(string) error
	expand = func(arg string) error {
		if strings.HasPrefix(arg, "@@") {
			out = append(out, arg[1:])
			return nil
		}
		if !strings.HasPrefix(arg, "@") || arg == "@" {
			out = append(out, arg)
			return nil
		}
		name := arg[1:]
		file, err := os.Open(name)
		if err != nil {
			out = append(out, arg)
			return nil
		}
		defer file.Close()
		absolute := name
		if !filepath.IsAbs(name) {
			cwd, err := os.Getwd()
			if err != nil {
				return err
			}
			absolute = cwd + string(os.PathSeparator) + name
		}
		// Java File normalizes duplicate Unix separators but retains dot components.
		for strings.Contains(absolute, "//") {
			absolute = strings.ReplaceAll(absolute, "//", "/")
		}
		absolute = strings.TrimSuffix(absolute, "/")
		if visited[absolute] {
			return nil
		}
		visited[absolute] = true
		data, err := io.ReadAll(file)
		if err != nil {
			return fmt.Errorf("Could not read argument file @%s: %w", name, err)
		}
		for _, token := range tokenizeArgumentFile(string(data)) {
			if err := expand(token); err != nil {
				return err
			}
		}
		return nil
	}
	for _, arg := range args {
		visited = map[string]bool{} // Picocli starts a fresh cycle set for each top-level token.
		if err := expand(arg); err != nil {
			return nil, err
		}
	}
	return out, nil
}
func tokenizeArgumentFile(text string) []string {
	units := javastring.UTF16(text)
	out := []string{}
	for i := 0; i < len(units); {
		if units[i] <= 32 {
			i++
			continue
		}
		if units[i] == '#' {
			for i < len(units) && units[i] != '\r' && units[i] != '\n' {
				i++
			}
			continue
		}
		token := []uint16{}
		if units[i] == '\'' || units[i] == '"' {
			quote := units[i]
			i++
			for i < len(units) && units[i] != quote && units[i] != '\r' && units[i] != '\n' {
				c := units[i]
				i++
				if c == '\\' && i < len(units) {
					c = units[i]
					i++
					if c >= '0' && c <= '7' {
						first := c
						value := c - '0'
						limit := 2
						if first <= '3' {
							limit = 3
						}
						for n := 1; n < limit && i < len(units) && units[i] >= '0' && units[i] <= '7'; n++ {
							value = value*8 + units[i] - '0'
							i++
						}
						c = value
					} else {
						switch c {
						case 'a':
							c = 7
						case 'b':
							c = 8
						case 'f':
							c = 12
						case 'n':
							c = 10
						case 'r':
							c = 13
						case 't':
							c = 9
						case 'v':
							c = 11
						}
					}
				}
				token = append(token, c)
			}
			if i < len(units) && units[i] == quote {
				i++
			}
		} else {
			for i < len(units) && units[i] > 32 && units[i] != '#' && units[i] != '\'' && units[i] != '"' {
				token = append(token, units[i])
				i++
			}
		}
		out = append(out, javastring.FromUTF16(token))
	}
	return out
}
