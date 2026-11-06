package com.spanlab.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Boots the catalog. HTTP stays on 8083 for health and Prometheus.
// gRPC stays on 9090, set in application.yml. There is no database.
@SpringBootApplication
public class CatalogApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogApplication.class, args);
    }
}
