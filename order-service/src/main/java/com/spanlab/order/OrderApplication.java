package com.spanlab.order;

import com.spanlab.catalog.v1.CatalogGrpc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.grpc.client.ImportGrpcClients;
import org.springframework.scheduling.annotation.EnableScheduling;

// Boots the order service on port 8081.
// EnableScheduling is what makes OutboxPublisher.tick run.
// ImportGrpcClients registers a blocking stub for the channel named
// "catalog". The host for that name is in application.yml.
@EnableScheduling
@SpringBootApplication
@ImportGrpcClients(target = "catalog", types = CatalogGrpc.CatalogBlockingStub.class)
public class OrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
