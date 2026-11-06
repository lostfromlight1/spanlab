// Command gateway is the HTTP front door on port 8080.
//
// Read this the way you would read a small Spring Boot app: main is
// the boot method, the mux is the set of @RequestMapping methods, and
// forward is a RestClient call with a 2 second timeout. Go has no
// Spring container here, so the client and the routes are local
// variables. The walk-through is learning/services/gateway.md.
package main

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/propagation"
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	shutdown, err := setupTracing(ctx, "gateway")
	if err != nil {
		slog.Error("tracing", "err", err)
		os.Exit(1)
	}
	defer func() { _ = shutdown(context.Background()) }()

	// env is System.getenv with a default. Compose sets the same Docker DNS names.
	orderURL := env("ORDER_URL", "http://order-service:8081")
	consumerURL := env("CONSUMER_URL", "http://consumer:8082")
	// The timeout is the lab's contract. otelhttp starts a client span and
	// writes traceparent on the outgoing request.
	client := &http.Client{
		Timeout:   2 * time.Second,
		Transport: otelhttp.NewTransport(http.DefaultTransport),
	}

	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})
	mux.HandleFunc("POST /api/v1/orders", forward(client, orderURL))
	mux.HandleFunc("GET /api/v1/orders/{id}", forward(client, orderURL))
	mux.HandleFunc("GET /api/v1/projections/{orderId}", forward(client, consumerURL))

	// The server span name is "POST /api/v1/orders", which is what Jaeger shows.
	handler := otelhttp.NewHandler(mux, "gateway", otelhttp.WithSpanNameFormatter(
		func(_ string, r *http.Request) string {
			return r.Method + " " + r.URL.Path
		},
	))
	server := &http.Server{Addr: ":8080", Handler: handler, ReadHeaderTimeout: 2 * time.Second}
	// go starts this function on another goroutine, like a daemon thread.
	// It blocks on ctx.Done(), which fires on Ctrl+C, then stops the server.
	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = server.Shutdown(shutdownCtx)
	}()
	if err = server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		slog.Error("http", "err", err)
		os.Exit(1)
	}
}

func forward(client *http.Client, base string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		req, err := http.NewRequestWithContext(r.Context(), r.Method, base+r.URL.RequestURI(), r.Body)
		if err != nil {
			writeProblem(w, http.StatusBadGateway, "Bad Gateway", "could not build downstream request")
			return
		}
		req.Header = r.Header.Clone()
		// Put the current trace on the downstream request. The order service
		// stores this traceparent on the outbox row.
		otel.GetTextMapPropagator().Inject(r.Context(), propagation.HeaderCarrier(req.Header))
		resp, err := client.Do(req)
		if err != nil {
			writeProblem(w, http.StatusGatewayTimeout, "Gateway Timeout", "downstream did not answer within 2 seconds")
			return
		}
		defer resp.Body.Close()
		if contentType := resp.Header.Get("Content-Type"); contentType != "" {
			w.Header().Set("Content-Type", contentType)
		}
		// Status and body are copied unchanged. A 400 from Java stays a 400.
		w.WriteHeader(resp.StatusCode)
		_, _ = io.Copy(w, resp.Body)
	}
}

func writeProblem(w http.ResponseWriter, status int, title, detail string) {
	w.Header().Set("Content-Type", "application/problem+json")
	w.WriteHeader(status)
	_, _ = w.Write([]byte(`{"title":"` + title + `","status":` + itoa(status) + `,"detail":"` + detail + `"}`))
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var digits [8]byte
	i := len(digits)
	for n > 0 {
		i--
		digits[i] = byte('0' + n%10)
		n /= 10
	}
	return string(digits[i:])
}
