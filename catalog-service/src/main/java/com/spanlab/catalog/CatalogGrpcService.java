package com.spanlab.catalog;

import java.util.Set;

import com.spanlab.catalog.v1.CatalogGrpc;
import com.spanlab.catalog.v1.CheckSkuRequest;
import com.spanlab.catalog.v1.CheckSkuResponse;

import io.grpc.stub.StreamObserver;
import org.springframework.grpc.server.service.GrpcService;

// CheckSku over gRPC. Extending the generated ImplBase and exposing it
// as a bean is enough for Spring to register the service on Netty.
// Unknown SKUs return known=false. The order service turns that into HTTP 400.
@GrpcService
public class CatalogGrpcService extends CatalogGrpc.CatalogImplBase {

    private static final Set<String> KNOWN = Set.of("SKU-1", "SKU-2");

    @Override
    public void checkSku(CheckSkuRequest request, StreamObserver<CheckSkuResponse> responseObserver) {
        // onNext is the single reply. onCompleted closes this call.
        // The method is asynchronous in the generated API even though
        // the work here is a set lookup.
        boolean known = KNOWN.contains(request.getSku());
        responseObserver.onNext(CheckSkuResponse.newBuilder().setKnown(known).build());
        responseObserver.onCompleted();
    }
}
