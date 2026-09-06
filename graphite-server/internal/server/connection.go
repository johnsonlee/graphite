package server

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
)

// net/http cancels Request.Context on an input EOF, including a valid TCP
// SHUT_WR. Graphite's query contract preserves such requests until response
// write; resets, socket failures and an actual server-observed close cancel.
type connectionContextKey struct{}
type observedConn struct {
	net.Conn
	ctx    context.Context
	cancel context.CancelCauseFunc
}

func (c *observedConn) Read(p []byte) (int, error) {
	n, err := c.Conn.Read(p)
	if err != nil && !errors.Is(err, io.EOF) {
		c.cancel(err)
	}
	return n, err
}
func (c *observedConn) Write(p []byte) (int, error) {
	n, err := c.Conn.Write(p)
	if err != nil {
		c.cancel(err)
	}
	return n, err
}
func (c *observedConn) Close() error { c.cancel(net.ErrClosed); return c.Conn.Close() }

type observedListener struct{ net.Listener }

func (l observedListener) Accept() (net.Conn, error) {
	c, err := l.Listener.Accept()
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancelCause(context.Background())
	return &observedConn{Conn: c, ctx: ctx, cancel: cancel}, nil
}
func ObserveListener(listener net.Listener) net.Listener { return observedListener{listener} }
func ConnectionContext(ctx context.Context, c net.Conn) context.Context {
	if observed, ok := c.(*observedConn); ok {
		return context.WithValue(ctx, connectionContextKey{}, observed.ctx)
	}
	return ctx
}
func queryContext(r *http.Request) context.Context {
	if ctx, ok := r.Context().Value(connectionContextKey{}).(context.Context); ok {
		return ctx
	}
	return r.Context()
}
