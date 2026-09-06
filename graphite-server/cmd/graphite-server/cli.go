package main

import (
	_ "embed"
	"flag"
	"fmt"
	"io"
	"strconv"
	"strings"

	"github.com/johnsonlee/graphite/graphite-server/internal/javastring"
)

// The public JVM commands use Picocli's default 80-column usage layout. These
// text resources are captured from pinned main and tested against its oracle.
//
//go:embed serve-usage.txt
var unifiedServeUsage string

//go:embed explore-usage.txt
var legacyExploreUsage string

func serveUsage(args []string) string {
	if len(args) > 0 && args[0] == "serve" {
		return unifiedServeUsage
	}
	return legacyExploreUsage
}

type cliParseError struct {
	message string
	usage   bool
}

func (e *cliParseError) Error() string { return e.message }
func parseError(format string, args ...any) error {
	return &cliParseError{fmt.Sprintf(format, args...), true}
}

type serveOption struct{ name, parameter, kind string }

var serveOptions = []serveOption{
	{"--data", "data", "string"}, {"--id", "graphId", "string"}, {"--load-mode", "loadMode", "mode"},
	{"--topology", "topology", "string"}, {"--port", "port", "int"}, {"--max-concurrent-cypher", "maxConcurrentCypher", "int"},
	{"--cypher-work-budget", "cypherWorkBudget", "long"}, {"--cypher-max-timeout-ms", "cypherMaxTimeoutMillis", "long"},
	{"--metrics", "", "bool"}, {"--graph", "graphSpecs", "strings"}, {"--help", "", "help"}, {"--version", "", "version"},
}

func findServeOption(name string) (serveOption, bool) {
	switch name {
	case "-p":
		name = "--port"
	case "-h":
		name = "--help"
	case "-V":
		name = "--version"
	}
	for _, o := range serveOptions {
		if o.name == name {
			return o, true
		}
	}
	return serveOption{}, false
}

func parseServeOptions(original []string, output io.Writer) (config, error) {
	c := config{loadMode: "MAPPED", port: 8080, maxConcurrent: 4, timeoutMillis: 60000, workBudget: 1000000}
	args, err := expandArgumentFiles(original)
	if err != nil {
		return c, err
	}
	indexBase := 0
	if len(original) > 0 && original[0] == "serve" {
		args = args[1:]
		indexBase = 1
	}
	seen := map[string]bool{}
	help, version := false, false
	positionalSeen := false
	type unmatched struct {
		value  string
		index  int
		option bool
	}
	unmatchedArgs := []unmatched{}
	literal := false
	for i := 0; i < len(args); i++ {
		arg := args[i]
		if !literal && arg == "--" {
			literal = true
			continue
		}
		if literal || !strings.HasPrefix(arg, "-") || arg == "-" {
			if !positionalSeen {
				c.positional = arg
				c.hasPositional = true
				positionalSeen = true
			} else {
				unmatchedArgs = append(unmatchedArgs, unmatched{arg, i + indexBase, false})
			}
			continue
		}
		name, value, attached := strings.Cut(arg, "=")
		// Picocli's POSIX short options: -p8080 and -hV (help wins over
		// unknown trailing cluster letters). Parameter values remain unsplit data.
		if strings.HasPrefix(name, "-p") && !strings.HasPrefix(name, "--") && len(name) > 2 {
			value = name[2:]
			if attached {
				value += "=" + strings.SplitN(arg, "=", 2)[1]
			}
			name = "-p"
			attached = true
		} else if len(name) > 2 && !strings.HasPrefix(name, "--") && (name[1] == 'h' || name[1] == 'V') {
			help = strings.Contains(name[1:], "h")
			version = strings.Contains(name[1:], "V")
			continue
		}
		option, ok := findServeOption(name)
		if !ok {
			unmatchedArgs = append(unmatchedArgs, unmatched{arg, i + indexBase, true})
			continue
		}
		if seen[option.name] && option.kind != "strings" {
			label := option.name
			if option.parameter != "" {
				label += " (<" + option.parameter + ">)"
			}
			return c, parseError("option '%s'%s should be specified only once", option.name, strings.TrimPrefix(label, option.name))
		}
		seen[option.name] = true
		if option.kind == "help" {
			help = true
			continue
		}
		if option.kind == "version" {
			version = true
			continue
		}
		if option.kind == "bool" {
			if !attached {
				value = "true"
			}
			if !strings.EqualFold(value, "true") && !strings.EqualFold(value, "false") {
				return c, parseError("Invalid value for option '%s': '%s' is not a boolean", option.name, value)
			}
			c.metrics = strings.EqualFold(value, "true")
			continue
		}
		if !attached {
			if i+1 >= len(args) {
				return c, parseError("Missing required parameter for option '%s' (<%s>)", option.name, option.parameter)
			}
			i++
			value = args[i]
			if _, isOption := findServeOption(strings.SplitN(value, "=", 2)[0]); isOption || value == "--" {
				return c, parseError("Expected parameter for option '%s' but found '%s'", option.name, value)
			}
		}
		var number int64
		if option.kind == "int" || option.kind == "long" {
			bits := 64
			if option.kind == "int" {
				bits = 32
			}
			var err error
			number, err = strconv.ParseInt(decimalDigits(value), 10, bits)
			if err != nil {
				article := "an"
				if option.kind == "long" {
					article = "a"
				}
				return c, parseError("Invalid value for option '%s': '%s' is not %s %s", option.name, value, article, option.kind)
			}
		}
		switch option.name {
		case "--data":
			c.data = value
			c.hasData = true
		case "--id":
			c.id = value
			c.hasID = true
		case "--topology":
			c.topology = value
		case "--load-mode":
			if value != "EAGER" && value != "MAPPED" && value != "AUTO" {
				return c, parseError("Invalid value for option '--load-mode': expected one of [EAGER, MAPPED, AUTO] (case-sensitive) but was '%s'", value)
			}
			c.loadMode = value
		case "--graph":
			c.graphs = append(c.graphs, value)
		case "--port":
			c.port = int(number)
		case "--max-concurrent-cypher":
			c.maxConcurrent = int(number)
		case "--cypher-max-timeout-ms":
			c.timeoutMillis = number
		case "--cypher-work-budget":
			c.workBudget = number
		}
	}
	if help {
		fmt.Fprint(output, serveUsage(original))
		return c, flag.ErrHelp
	}
	if version {
		return c, flag.ErrHelp
	}
	if len(unmatchedArgs) > 0 {
		values := make([]string, len(unmatchedArgs))
		allOptions := true
		for i, a := range unmatchedArgs {
			values[i] = "'" + a.value + "'"
			allOptions = allOptions && a.option
		}
		if allOptions {
			noun := "option"
			if len(values) > 1 {
				noun = "options"
			}
			message := "Unknown " + noun + ": " + strings.Join(values, ", ")
			suggestions := optionSuggestions(unmatchedArgs[0].value)
			if len(suggestions) > 0 {
				return c, &cliParseError{message + "\nPossible solutions: " + strings.Join(suggestions, ", "), false}
			}
			return c, parseError("%s", message)
		}
		if len(values) == 1 {
			return c, parseError("Unmatched argument at index %d: %s", unmatchedArgs[0].index, values[0])
		}
		return c, parseError("Unmatched arguments from index %d: %s", unmatchedArgs[0].index, strings.Join(values, ", "))
	}
	return c, nil
}

// Java's integer converters consume Character.digit on UTF-16 chars. A
// supplementary digit is therefore rejected, whereas BMP decimal digits work.
func decimalDigits(value string) string {
	units := javastring.UTF16(value)
	normalized := make([]byte, len(units))
	for i, unit := range units {
		if i == 0 && (unit == '+' || unit == '-') {
			normalized[i] = byte(unit)
			continue
		}
		digit, ok := javastring.Digit(rune(unit))
		if !ok {
			return value
		}
		normalized[i] = byte('0' + digit)
	}
	return string(normalized)
}

// Picocli's unknown-option suggestions use the first two non-prefix chars,
// preserving option declaration order (not edit distance).
func optionSuggestions(value string) []string {
	prefix := strings.TrimLeft(value, "-")
	if len(prefix) > 2 {
		prefix = prefix[:2]
	}
	out := []string{}
	for _, option := range serveOptions {
		names := []string{option.name}
		switch option.name {
		case "--port":
			names = append(names, "-p")
		case "--help":
			names = []string{"-h", "--help"}
		case "--version":
			names = []string{"-V", "--version"}
		}
		for _, name := range names {
			if strings.HasPrefix(strings.TrimLeft(name, "-"), prefix) {
				out = append(out, name)
			}
		}
	}
	return out
}
