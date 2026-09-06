package server

import (
	"net/http"
	"strings"
	"time"
)

type metricResponseWriter struct {
	http.ResponseWriter
	status int
}

func (w *metricResponseWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }
func (w *metricResponseWriter) WriteHeader(status int) {
	if w.status == 0 && status >= 200 {
		w.status = status
	}
	w.ResponseWriter.WriteHeader(status)
}
func (w *metricResponseWriter) Write(body []byte) (int, error) {
	if w.status == 0 {
		w.status = http.StatusOK
	}
	return w.ResponseWriter.Write(body)
}

func (s *Server) instrumentHTTP(mux *http.ServeMux) http.Handler {
	if s.Metrics == nil {
		return mux
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		started := time.Now()
		// Go 1.22 has no Request.Pattern. Ask ServeMux for the registered pattern
		// so graph IDs, resource names and queries never become metric labels.
		_, route := mux.Handler(r)
		if _, path, ok := strings.Cut(route, " "); ok {
			route = path
		}
		writer := &metricResponseWriter{ResponseWriter: w}
		defer func() {
			panicValue := recover()
			status := writer.status
			if status == 0 {
				status = http.StatusOK
				if panicValue != nil {
					status = http.StatusInternalServerError
				}
			}
			s.Metrics.RecordHTTP(r.Method, route, status, time.Since(started))
			if panicValue != nil {
				panic(panicValue)
			}
		}()
		mux.ServeHTTP(writer, r)
	})
}
