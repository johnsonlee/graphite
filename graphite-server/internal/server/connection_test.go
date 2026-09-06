package server

import (
	"bufio"
	"context"
	"io"
	"net"
	"net/http"
	"testing"
	"time"
)

func TestTCPHalfCloseKeepsQueryAliveAndResetCancels(t *testing.T) {
	for _, reset := range []bool{false, true} {
		t.Run(map[bool]string{false: "half-close", true: "reset"}[reset], func(t *testing.T) {
			entered := make(chan struct{})
			result := make(chan error, 1)
			release := make(chan struct{})
			g, _ := NewGuard(1, 2*time.Second)
			defer g.Close()
			h := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				_, err := g.Execute(queryContext(r), nil, func(ctx context.Context) (any, error) {
					close(entered)
					select {
					case <-ctx.Done():
						return nil, ctx.Err()
					case <-release:
						return "completed", nil
					}
				})
				result <- err
				if err != nil {
					writeQueryError(w, err)
				} else {
					writeText(w, 200, "completed")
				}
			})
			listener, err := net.Listen("tcp", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			s := &http.Server{Handler: h, ConnContext: ConnectionContext}
			defer s.Close()
			go s.Serve(ObserveListener(listener))
			c, err := net.Dial("tcp", listener.Addr().String())
			if err != nil {
				t.Fatal(err)
			}
			defer c.Close()
			_, err = io.WriteString(c, "GET /query HTTP/1.1\r\nHost: localhost\r\n\r\n")
			if err != nil {
				t.Fatal(err)
			}
			select {
			case <-entered:
			case <-time.After(time.Second):
				t.Fatal("handler not entered")
			}
			tcp := c.(*net.TCPConn)
			if reset {
				_ = tcp.SetLinger(0)
				_ = tcp.Close()
				select {
				case err := <-result:
					assertQueryError(t, err, 503, "cypher_query_cancelled")
				case <-time.After(time.Second):
					t.Fatal("reset did not cancel execution")
				}
			} else {
				if err := tcp.CloseWrite(); err != nil {
					t.Fatal(err)
				}
				// Observe the HTTP read side consume FIN before allowing completion.
				select {
				case err := <-result:
					t.Fatalf("half-close completed early: %v", err)
				case <-time.After(20 * time.Millisecond):
				}
				close(release)
				if err := <-result; err != nil {
					t.Fatalf("half-close cancelled: %v", err)
				}
				_ = c.SetReadDeadline(time.Now().Add(time.Second))
				response, err := http.ReadResponse(bufio.NewReader(c), nil)
				if err != nil {
					t.Fatal(err)
				}
				defer response.Body.Close()
				body, err := io.ReadAll(response.Body)
				if err != nil {
					t.Fatal(err)
				}
				if response.StatusCode != 200 || string(body) != "completed" {
					t.Fatalf("half-close response: %d %q", response.StatusCode, body)
				}
			}
		})
	}
}
