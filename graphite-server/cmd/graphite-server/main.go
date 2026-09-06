// graphite-server is the native Go implementation of graphite serve.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/johnsonlee/graphite/graphite-server/internal/server"
)

type graphSpecs []string

func (g *graphSpecs) String() string         { return strings.Join(*g, ",") }
func (g *graphSpecs) Set(value string) error { *g = append(*g, value); return nil }

type config struct {
	data, id, loadMode, topology string
	port, maxConcurrent          int
	timeoutMillis, workBudget    int64
	metrics                      bool
	graphs                       graphSpecs
	positional                   string
}

func parseConfig(args []string, output io.Writer) (config, error) {
	var c config
	f := flag.NewFlagSet("graphite-server", flag.ContinueOnError)
	f.SetOutput(output)
	f.StringVar(&c.data, "data", "", "Data directory for relative graph paths")
	f.StringVar(&c.id, "id", "", "Graph id for positional graph directory")
	f.StringVar(&c.loadMode, "load-mode", "MAPPED", "Graph load mode: AUTO, EAGER, MAPPED")
	f.StringVar(&c.topology, "topology", "", "Topology query file or directory")
	f.IntVar(&c.port, "port", 8080, "HTTP port")
	f.IntVar(&c.port, "p", 8080, "HTTP port")
	f.IntVar(&c.maxConcurrent, "max-concurrent-cypher", 4, "Maximum executing Cypher queries")
	f.Int64Var(&c.timeoutMillis, "cypher-max-timeout-ms", 60000, "Maximum Cypher timeout in milliseconds")
	f.Int64Var(&c.workBudget, "cypher-work-budget", 1000000, "Deprecated and ignored")
	f.BoolVar(&c.metrics, "metrics", false, "Expose Prometheus metrics")
	f.Var(&c.graphs, "graph", "Initial id:path mapping (repeatable)")
	if len(args) > 0 && args[0] == "serve" {
		args = args[1:]
	}
	// Picocli accepts options before and after the positional graph directory.
	var options, positionals []string
	for i := 0; i < len(args); i++ {
		a := args[i]
		if a == "--" {
			positionals = append(positionals, args[i+1:]...)
			break
		}
		if strings.HasPrefix(a, "-") {
			options = append(options, a)
			name := strings.TrimLeft(strings.SplitN(a, "=", 2)[0], "-")
			if opt := f.Lookup(name); opt != nil && !strings.Contains(a, "=") && name != "metrics" {
				if i+1 >= len(args) {
					return c, fmt.Errorf("flag needs an argument: %s", a)
				}
				i++
				options = append(options, args[i])
			}
		} else {
			positionals = append(positionals, a)
		}
	}
	if err := f.Parse(options); err != nil {
		return c, err
	}
	if len(positionals) > 1 {
		return c, errors.New("Expected at most one saved graph directory")
	}
	if len(positionals) == 1 {
		c.positional = positionals[0]
	}
	if c.maxConcurrent <= 0 || c.timeoutMillis <= 0 {
		return c, errors.New("Cypher concurrency and maximum timeout must be positive")
	}
	if c.port < 0 || c.port > 65535 {
		return c, errors.New("HTTP port must be between 0 and 65535")
	}
	if c.positional == "" && len(c.graphs) == 0 && c.data == "" {
		return c, errors.New("--data is required when starting without an initial graph")
	}
	if c.positional != "" && c.id == "" {
		return c, errors.New("--id is required when a positional graph directory is provided")
	}
	if err := server.ValidateLoadMode(c.loadMode); err != nil {
		return c, err
	}
	if c.data == "" {
		c.data = "."
		if c.positional != "" {
			absolute, err := filepath.Abs(c.positional)
			if err != nil {
				return c, err
			}
			c.data = filepath.Dir(absolute)
		}
	}
	// Missing implementations remain explicit until their parity gates pass.
	if c.topology != "" {
		return c, errors.New("Go topology queries are not implemented yet")
	}
	if c.metrics {
		return c, errors.New("Go Prometheus metrics are not implemented yet")
	}
	return c, nil
}

func run(ctx context.Context, args []string, stderr io.Writer) error {
	c, err := parseConfig(args, stderr)
	if err != nil {
		return err
	}
	if err = os.MkdirAll(c.data, 0755); err != nil {
		return err
	}
	r, err := server.NewRegistry(c.data, c.loadMode, server.OpenNativeGraph)
	if err != nil {
		return err
	}
	defer r.Close()
	seen := make(map[string]bool)
	load := func(id, path string) error {
		clean, err := server.ValidateGraphID(id)
		if err != nil {
			return err
		}
		if seen[clean] {
			return fmt.Errorf("Duplicate initial graph id: %s", clean)
		}
		seen[clean] = true
		_, err = r.Load(clean, path, c.loadMode)
		return err
	}
	if c.positional != "" {
		if err = load(c.id, c.positional); err != nil {
			return err
		}
	}
	for _, spec := range c.graphs {
		id, path, ok := strings.Cut(spec, ":")
		if !ok || id == "" || path == "" {
			return fmt.Errorf("Invalid --graph '%s'. Expected id:path.", spec)
		}
		if err = load(id, path); err != nil {
			return err
		}
	}
	// Saturate very large accepted millisecond values instead of overflowing a
	// time.Duration. Client timeouts are clamped by the same guard.
	timeout := time.Duration(c.timeoutMillis) * time.Millisecond
	if c.timeoutMillis > int64((time.Duration(1<<63-1))/time.Millisecond) {
		timeout = time.Duration(1<<63 - 1)
	}
	g, err := server.NewGuard(c.maxConcurrent, timeout)
	if err != nil {
		return err
	}
	defer g.Close()
	s := &server.Server{Registry: r, Guard: g}
	httpServer := &http.Server{Handler: s.Handler(), ConnContext: server.ConnectionContext}
	listener, err := net.Listen("tcp", ":"+strconv.Itoa(c.port))
	if err != nil {
		return err
	}
	fmt.Fprintf(stderr, "Graphite Go server (in development): http://localhost:%d\n", listener.Addr().(*net.TCPAddr).Port)
	finished := make(chan struct{})
	go func() {
		select {
		case <-ctx.Done():
			_ = g.Close()
			shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			if err := httpServer.Shutdown(shutdown); err != nil {
				_ = httpServer.Close()
			}
		case <-finished:
		}
	}()
	err = httpServer.Serve(server.ObserveListener(listener))
	close(finished)
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := run(ctx, os.Args[1:], os.Stderr); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return
		}
		fmt.Fprintln(os.Stderr, "Error:", err)
		os.Exit(1)
	}
}
