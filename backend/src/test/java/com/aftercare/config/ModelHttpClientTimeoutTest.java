package com.aftercare.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

class ModelHttpClientTimeoutTest {
    @Test
    void readTimeoutStopsAStalledProviderRequest() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(1_000);
                byte[] response = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(response);
                }
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            catch (IOException disconnected) {
                // The client is expected to close the timed-out request before this response is written.
            }
        });
        server.start();
        try {
            var customizer = new ModelHttpClientConfiguration().modelRestClientTimeoutCustomizer(
                    Duration.ofMillis(200), Duration.ofMillis(150));
            RestClient.Builder builder = RestClient.builder();
            customizer.customize(builder);
            RestClient client = builder.build();
            long startedAt = System.nanoTime();

            assertThatThrownBy(() -> client.get()
                    .uri("http://127.0.0.1:" + server.getAddress().getPort() + "/")
                    .retrieve().body(String.class))
                    .isInstanceOf(ResourceAccessException.class);
            long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            assertThat(elapsedMs).isLessThan(800);
        }
        finally {
            server.stop(0);
        }
    }
}
