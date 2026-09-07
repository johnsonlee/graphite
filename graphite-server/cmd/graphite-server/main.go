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
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/johnsonlee/graphite/graphite-server/internal/server"
)

type graphSpecs []string

// Set with -ldflags '-X main.version=...' when producing a release artifact.
var version = "unknown"

func (g *graphSpecs) String() string         { return strings.Join(*g, ",") }
func (g *graphSpecs) Set(value string) error { *g = append(*g, value); return nil }

type config struct {
	data, id, loadMode, topology  string
	port, maxConcurrent           int
	timeoutMillis, workBudget     int64
	metrics                       bool
	graphs                        graphSpecs
	positional                    string
	hasData, hasID, hasPositional bool
}

func parseConfig(args []string, output io.Writer) (config, error) {
	c, err := parseServeOptions(args, output)
	if err != nil {
		return c, err
	}
	if c.maxConcurrent <= 0 || c.timeoutMillis <= 0 {
		return c, errors.New("Cypher concurrency and maximum timeout must be positive")
	}
	if c.port < 0 || c.port > 65535 {
		return c, errors.New("HTTP port must be between 0 and 65535")
	}
	if !c.hasPositional && len(c.graphs) == 0 && !c.hasData {
		return c, errors.New("--data is required when starting without an initial graph")
	}
	if c.hasPositional && !c.hasID {
		return c, errors.New("--id is required when a positional graph directory is provided")
	}
	if err := server.ValidateLoadMode(c.loadMode); err != nil {
		return c, err
	}
	if c.hasPositional && c.positional == "" {
		c.positional = "."
	}
	if c.data == "" {
		c.data = "."
		if c.hasPositional && !c.hasData {
			absolute, err := filepath.Abs(c.positional)
			if err != nil {
				return c, err
			}
			c.data = filepath.Dir(absolute)
		}
	}
	return c, nil
}

func run(ctx context.Context, args []string, stdout, stderr io.Writer) error {
	c, err := parseConfig(args, stdout)
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
	topologyQueries, err := server.LoadTopologyQueries(c.topology)
	if err != nil {
		return err
	}
	topology, err := server.NewTopologyService(r, topologyQueries)
	if err != nil {
		return err
	}
	s := &server.Server{Registry: r, Guard: g, Version: version, Topology: topology}
	if c.metrics {
		s.Metrics = server.NewPerformanceMetrics(c.maxConcurrent)
		g.Metrics = s.Metrics
	}
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
	code := executeWithSignals(os.Args[1:], os.Stdout, os.Stderr, os.Getenv)
	if code != 0 {
		os.Exit(code)
	}
}

func execute(ctx context.Context, args []string, stdout, stderr io.Writer) int {
	if err := run(ctx, args, stdout, stderr); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return 0
		}
		var parseError *cliParseError
		if errors.As(err, &parseError) {
			fmt.Fprintln(stderr, parseError.message)
			if parseError.usage {
				fmt.Fprint(stderr, serveUsage(args))
			}
			return 2
		}
		fmt.Fprintln(stderr, "Error:", err)
		return 1
	}
	return 0
}
