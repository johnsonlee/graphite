// Command normalize removes ANTLR 4.13.2's unreachable label-use sentinels so
// generated code passes go vet without disabling the unreachable-code check.
package main

import (
	"bytes"
	"fmt"
	"os"
	"regexp"
)

func main() {
	if len(os.Args) != 2 {
		panic("usage: normalize generated/cypher_parser.go")
	}
	path := os.Args[1]
	source, err := os.ReadFile(path)
	if err != nil {
		panic(err)
	}
	rules := regexp.MustCompile(`(?ms)^func \(p \*CypherParser\) \w+\(\) .*?^}`)
	sentinel := []byte("\tgoto errorExit // Trick to prevent compiler error if the label is not used\n")
	count := 0
	result := rules.ReplaceAllFunc(source, func(rule []byte) []byte {
		if !bytes.Contains(rule, sentinel) {
			return rule
		}
		count++
		rule = bytes.ReplaceAll(rule, sentinel, nil)
		if !bytes.Contains(rule, []byte("goto errorExit")) {
			rule = bytes.ReplaceAll(rule, []byte("errorExit:\n"), nil)
		}
		return rule
	})
	if count == 0 {
		panic("ANTLR generated sentinel changed; review normalization")
	}
	if bytes.Contains(result, sentinel) {
		panic("ANTLR sentinel outside matched rule; review normalization")
	}
	if err = os.WriteFile(path, result, 0644); err != nil {
		panic(err)
	}
	fmt.Printf("Normalized %d ANTLR rule sentinels\n", count)
}
