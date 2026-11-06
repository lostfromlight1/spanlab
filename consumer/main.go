// Command consumer reads order-events and writes projection.processed_orders.
//
// main starts Postgres, Kafka, and an HTTP server on port 8082. The
// consume function runs on a goroutine, which is a thread you do not
// join. FetchMessage does not commit the offset. CommitMessages is the
// commit, and it runs only after the insert or the dead-letter publish
// returns. The walk-through is learning/services/consumer.md.
package main

import (
	"context"
	_ "embed"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/segmentio/kafka-go"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/trace"
)

//go:embed sql/001_processed_orders.sql
var schemaSQL string

// envelope is the JSON the publisher writes. The struct tags are the
// field names, the way Jackson uses @JsonProperty. A nested struct is
// the payload object.

type envelope struct {
	ID      string `json:"id"`
	Type    string `json:"type"`
	Payload struct {
		OrderID    string `json:"orderId"`
		CustomerID string `json:"customerId"`
		SKU        string `json:"sku"`
		Quantity   int    `json:"quantity"`
		Amount     string `json:"amount"`
	} `json:"payload"`
}

type projection struct {
	EventID     string    `json:"eventId"`
	OrderID     string    `json:"orderId"`
	CustomerID  string    `json:"customerId"`
	SKU         string    `json:"sku"`
	Quantity    int       `json:"quantity"`
	Amount      string    `json:"amount"`
	ProcessedAt time.Time `json:"processedAt"`
}

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	shutdown, err := setupTracing(ctx, "consumer")
	if err != nil {
		slog.Error("tracing", "err", err)
		os.Exit(1)
	}
	defer func() { _ = shutdown(context.Background()) }()

	pool, err := pgxpool.New(ctx, env("DATABASE_URL", "postgres://spanlab:spanlab@postgres:5432/spanlab"))
	if err != nil {
		slog.Error("postgres", "err", err)
		os.Exit(1)
	}
	defer pool.Close()
	if _, err = pool.Exec(ctx, schemaSQL); err != nil {
		slog.Error("schema", "err", err)
		os.Exit(1)
	}

	brokers := strings.Split(env("KAFKA_BROKERS", "kafka:9092"), ",")
	// FirstOffset matters only before this group has a committed offset.
	// After that, the group continues from the last commit.
	reader := kafka.NewReader(kafka.ReaderConfig{
		Brokers:     brokers,
		Topic:       "order-events",
		GroupID:     "spanlab-projection",
		StartOffset: kafka.FirstOffset,
	})
	defer reader.Close()

	// The writer republishes retries and dead letters. Topics are created
	// by kafka-init, so a missing topic is an error rather than a silent create.
	writer := &kafka.Writer{Addr: kafka.TCP(brokers...), AllowAutoTopicCreation: false}
	defer writer.Close()

	// go runs consume beside the HTTP server. main blocks in ListenAndServe.
	go consume(ctx, reader, writer, pool)

	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})
	mux.HandleFunc("GET /api/v1/projections/{orderId}", func(w http.ResponseWriter, r *http.Request) {
		getProjection(w, r, pool)
	})

	server := &http.Server{Addr: ":8082", Handler: mux, ReadHeaderTimeout: 2 * time.Second}
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

// consume fetches one record at a time. The offset stays put until
// CommitMessages, so a crash retries the same record.
func consume(ctx context.Context, reader *kafka.Reader, writer *kafka.Writer, pool *pgxpool.Pool) {
	tracer := otel.Tracer("consumer")
	propagator := otel.GetTextMapPropagator()
	for {
		msg, err := reader.FetchMessage(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			slog.Error("fetch", "err", err)
			continue
		}
		msgCtx := propagator.Extract(ctx, headerCarrier(msg.Headers))
		// order.created is a child of outbox.publish because the Kafka
		// headers carry that span's context.
		msgCtx, span := tracer.Start(msgCtx, "order.created", trace.WithSpanKind(trace.SpanKindConsumer))
		if err = apply(msgCtx, pool, msg.Value); err != nil {
			span.RecordError(err)
			span.End()
			deadLetter(ctx, writer, reader, msg, err)
			continue
		}
		span.End()
		slog.Info("applied", "offset", msg.Offset)
		if err = reader.CommitMessages(ctx, msg); err != nil {
			slog.Error("commit", "err", err)
		}
	}
}

// deadLetter publishes the same bytes elsewhere, then commits the
// original offset. A permanent error (bad JSON, missing ids) goes
// straight to order-events-dlq. A database error is republished to
// order-events with x-attempts and dead-letters on the third try.
// If the republish fails, the offset is not committed.
func deadLetter(ctx context.Context, writer *kafka.Writer, reader *kafka.Reader, msg kafka.Message, cause error) {
	var permanent permanentError
	attempts := headerAttempts(msg.Headers) + 1
	topic := "order-events"
	if errors.As(cause, &permanent) || attempts >= 3 {
		topic = "order-events-dlq"
	}
	if err := publish(ctx, writer, topic, msg, attempts, cause); err != nil {
		slog.Error("republish", "topic", topic, "err", err)
		return
	}
	if topic == "order-events-dlq" {
		slog.Info("dead-lettered", "offset", msg.Offset, "attempts", attempts)
	} else {
		slog.Info("retried", "offset", msg.Offset, "attempts", attempts)
	}
	if err := reader.CommitMessages(ctx, msg); err != nil {
		slog.Error("commit", "err", err)
	}
}

func publish(ctx context.Context, writer *kafka.Writer, topic string, msg kafka.Message, attempts int, cause error) error {
	headers := make([]kafka.Header, 0, len(msg.Headers)+2)
	for _, header := range msg.Headers {
		if header.Key == "x-attempts" || header.Key == "x-error" {
			continue
		}
		headers = append(headers, header)
	}
	headers = append(headers,
		kafka.Header{Key: "x-attempts", Value: []byte(strconv.Itoa(attempts))},
		kafka.Header{Key: "x-error", Value: []byte(cause.Error())},
	)
	return writer.WriteMessages(ctx, kafka.Message{
		Topic:   topic,
		Key:     msg.Key,
		Value:   msg.Value,
		Headers: headers,
	})
}

func headerAttempts(headers []kafka.Header) int {
	for _, header := range headers {
		if header.Key == "x-attempts" {
			value, err := strconv.Atoi(string(header.Value))
			if err != nil {
				return 0
			}
			return value
		}
	}
	return 0
}

// permanentError marks a payload that will not succeed on retry.
// Wrapping the JSON error this way is the Go form of a checked failure
// the caller distinguishes with errors.As.
type permanentError struct{ error }

func (e permanentError) Unwrap() error { return e.error }

func apply(ctx context.Context, pool *pgxpool.Pool, value []byte) error {
	var event envelope
	if err := json.Unmarshal(value, &event); err != nil {
		return permanentError{err}
	}
	if event.ID == "" || event.Payload.OrderID == "" {
		return permanentError{errors.New("event id and order id are required")}
	}
	// The event id is the primary key. A second delivery of the same
	// record is a no-op, which is how at-least-once becomes one row.
	_, err := pool.Exec(ctx, `
		INSERT INTO projection.processed_orders
			(event_id, order_id, customer_id, sku, quantity, amount)
		VALUES ($1, $2, $3, $4, $5, $6)
		ON CONFLICT (event_id) DO NOTHING
	`, event.ID, event.Payload.OrderID, event.Payload.CustomerID, event.Payload.SKU, event.Payload.Quantity, event.Payload.Amount)
	return err
}

func getProjection(w http.ResponseWriter, r *http.Request, pool *pgxpool.Pool) {
	var row projection
	err := pool.QueryRow(r.Context(), `
		SELECT event_id::text, order_id::text, customer_id, sku, quantity, amount::text, processed_at
		FROM projection.processed_orders
		WHERE order_id = $1
		ORDER BY processed_at DESC
		LIMIT 1
	`, r.PathValue("orderId")).Scan(&row.EventID, &row.OrderID, &row.CustomerID, &row.SKU, &row.Quantity, &row.Amount, &row.ProcessedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		writeProblem(w, http.StatusNotFound, "Not Found", "projection not found")
		return
	}
	if err != nil {
		writeProblem(w, http.StatusInternalServerError, "Internal Server Error", "projection read failed")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(row)
}

func writeProblem(w http.ResponseWriter, status int, title, detail string) {
	w.Header().Set("Content-Type", "application/problem+json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{
		"title":  title,
		"status": status,
		"detail": detail,
	})
}

// headerCarrier lets the OpenTelemetry propagator read Kafka headers.
// Set is empty because this process does not write headers onto the
// record it just consumed. The var line below fails the build if a
// method is missing: that is "implements the interface" without a keyword.
type headerCarrier []kafka.Header

func (c headerCarrier) Get(key string) string {
	for _, header := range c {
		if header.Key == key {
			return string(header.Value)
		}
	}
	return ""
}

func (c headerCarrier) Set(key, value string) {}

func (c headerCarrier) Keys() []string {
	keys := make([]string, 0, len(c))
	for _, header := range c {
		keys = append(keys, header.Key)
	}
	return keys
}

var _ propagation.TextMapCarrier = headerCarrier(nil)
