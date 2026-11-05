package com.acme.payments;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** A runnable JVM endpoint matching the AcmeShop Swift client's charge request. */
public final class PaymentService {
    public static void registerRoutes(HttpServer server) {
        server.createContext("/v1/charge", exchange -> {
            byte[] body = "charged".getBytes(StandardCharsets.UTF_8);
            int status = "POST".equals(exchange.getRequestMethod()) ? 200 : 405;
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
    }

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        registerRoutes(server);
        server.start();
    }
}
