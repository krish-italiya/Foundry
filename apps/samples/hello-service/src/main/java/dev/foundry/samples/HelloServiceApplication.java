package dev.foundry.samples;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HelloServiceApplication {

    private HelloServiceApplication() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException(
                    "Expected exactly one argument: port");
        }

        int port = Integer.parseInt(args[0]);

        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid port");
        }

        HttpServer server = HttpServer.create(
                new InetSocketAddress(
                        InetAddress.getByName("127.0.0.1"),
                        port
                ),
                0
        );

        server.createContext("/health", exchange ->
                respond(
                        exchange,
                        "{\"service\":\"foundry-hello-service\","
                                + "\"status\":\"UP\"}"
                )
        );

        server.createContext("/", exchange ->
                respond(
                        exchange,
                        "{\"service\":\"foundry-hello-service\","
                                + "\"message\":\"Hello from Foundry!\"}"
                )
        );

        ExecutorService executor =
                Executors.newVirtualThreadPerTaskExecutor();

        server.setExecutor(executor);

        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> {
                    server.stop(0);
                    executor.close();
                }, "foundry-sample-shutdown")
        );

        System.out.println(
                "Sample service starting on 127.0.0.1:" + port);

        server.start();

        System.out.println("Sample service is accepting requests.");
    }

    private static void respond(
            HttpExchange exchange,
            String responseBody) throws IOException {

        int statusCode = 200;
        String body = responseBody;

        if (!"GET".equals(exchange.getRequestMethod())) {
            statusCode = 405;
            body = "{\"error\":\"Method not allowed\"}";
        }

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set(
                "Content-Type", "application/json; charset=utf-8");

        exchange.getResponseHeaders().set("Cache-Control", "no-store");

        exchange.sendResponseHeaders(statusCode, bytes.length);

        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}