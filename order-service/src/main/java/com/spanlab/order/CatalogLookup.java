package com.spanlab.order;

import com.spanlab.catalog.v1.CatalogGrpc;
import com.spanlab.catalog.v1.CheckSkuRequest;

import org.springframework.stereotype.Component;

// Blocking gRPC client for CheckSku. The stub is created by
// @ImportGrpcClients on OrderApplication and aimed at
// static://catalog-service:9090.
@Component
class CatalogLookup {

    private final CatalogGrpc.CatalogBlockingStub catalog;

    CatalogLookup(CatalogGrpc.CatalogBlockingStub catalog) {
        this.catalog = catalog;
    }

    boolean known(String sku) {
        return catalog.checkSku(CheckSkuRequest.newBuilder().setSku(sku).build()).getKnown();
    }
}
