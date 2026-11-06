// Command loadgen posts COUNT orders to the gateway and exits.
//
// This is the smallest Go program in the repo. main is a Java main,
// os.Getenv is System.getenv, and http.Client is java.net.http.HttpClient.
// It is not part of docker compose up. Run it with:
//
//	docker compose --profile load run --rm loadgen
//
// The walk-through is learning/services/loadgen.md.
package main

import (
	"bytes"
	"fmt"
	"net/http"
	"os"
	"strconv"
	"time"
)

func main() {
	gateway := env("GATEWAY_URL", "http://gateway:8080")
	count := 10
	if raw := os.Getenv("COUNT"); raw != "" {
		parsed, err := strconv.Atoi(raw)
		if err != nil || parsed < 1 {
			fmt.Fprintf(os.Stderr, "COUNT must be a positive integer\n")
			os.Exit(1)
		}
		count = parsed
	}

	// Five seconds is longer than the gateway's 2 second downstream timeout,
	// so a slow order service comes back as HTTP 504 from the gateway.
	client := &http.Client{Timeout: 5 * time.Second}
	// SKU-1 is known to the catalog, so each post is a normal order.
	body := []byte(`{"customerId":"loadgen","sku":"SKU-1","quantity":1,"amount":"1.00"}`)
	created := 0
	for i := 0; i < count; i++ {
		// NewReader starts at the beginning of body on every iteration.
		// The previous Post consumed the previous reader.
		response, err := client.Post(gateway+"/api/v1/orders", "application/json", bytes.NewReader(body))
		if err != nil {
			fmt.Fprintf(os.Stderr, "post %d: %v\n", i, err)
			os.Exit(1)
		}
		response.Body.Close()
		if response.StatusCode != http.StatusCreated {
			fmt.Fprintf(os.Stderr, "post %d: status %d\n", i, response.StatusCode)
			os.Exit(1)
		}
		created++
	}
	fmt.Printf("created %d orders\n", created)
}

func env(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}
