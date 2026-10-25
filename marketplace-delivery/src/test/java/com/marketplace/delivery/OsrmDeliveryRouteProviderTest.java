package com.marketplace.delivery;

import com.marketplace.shared.api.ServiceUnavailableException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The OSRM road-routing adapter against a local JDK HTTP server: the base
 * URL is mandatory (the public demo server is never assumed), coordinates
 * are validated before any call, and every malformed answer — non-Ok code,
 * empty route list, non-finite or non-positive metrics, transport failure —
 * surfaces as ServiceUnavailable so quoting and dispatch degrade honestly.
 */
class OsrmDeliveryRouteProviderTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startServer(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.createContext("/", exchange -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private OsrmDeliveryRouteProvider provider(String baseUrl) {
        return new OsrmDeliveryRouteProvider(RestClient.builder(),
                new DeliveryProperties(true, "SAR", 100, 50, 20_000, 900,
                        baseUrl, 50, 25, 120, 25, 3L, 92L));
    }

    @Test
    void missingBaseUrlIsRefusedBeforeAnyCall() {
        assertThrows(ServiceUnavailableException.class, () -> provider("   ").route(37, -122, 38, -121));
    }

    @Test
    void invalidPickupCoordinatesAreRefused() {
        assertThrows(ServiceUnavailableException.class,
                () -> provider("http://127.0.0.1:1").route(91, -122, 38, -121));
    }

    @Test
    void invalidDropoffCoordinatesAreRefused() {
        assertThrows(ServiceUnavailableException.class,
                () -> provider("http://127.0.0.1:1").route(37, -122, Double.NaN, -121));
    }

    @Test
    void aHealthyAnswerMapsToRoundedRoadMetrics() throws IOException {
        String baseUrl = startServer(200,
                "{\"code\":\"Ok\",\"routes\":[{\"distance\":1250.4,\"duration\":78.9}]}");

        DeliveryRouteMetrics metrics = provider(baseUrl).route(37, -122, 38, -121);

        assertEquals(1250, metrics.distanceMeters());
        assertEquals(79, metrics.durationSeconds());
    }

    @Test
    void subSecondDurationsNeverRoundToZero() throws IOException {
        String baseUrl = startServer(200,
                "{\"code\":\"Ok\",\"routes\":[{\"distance\":120.0,\"duration\":0.2}]}");

        DeliveryRouteMetrics metrics = provider(baseUrl).route(37, -122, 38, -121);

        assertEquals(120, metrics.distanceMeters());
        assertEquals(1, metrics.durationSeconds());
    }

    @Test
    void nonOkCodeIsNoRoute() throws IOException {
        String baseUrl = startServer(200, "{\"code\":\"NoSegment\",\"routes\":[]}");

        assertThrows(ServiceUnavailableException.class, () -> provider(baseUrl).route(37, -122, 38, -121));
    }

    @Test
    void emptyRouteListIsNoRoute() throws IOException {
        String baseUrl = startServer(200, "{\"code\":\"Ok\",\"routes\":[]}");

        assertThrows(ServiceUnavailableException.class, () -> provider(baseUrl).route(37, -122, 38, -121));
    }

    @Test
    void nonFiniteMetricsAreRejected() throws IOException {
        String baseUrl = startServer(200,
                "{\"code\":\"Ok\",\"routes\":[{\"distance\":null,\"duration\":100.0}]}");

        assertThrows(ServiceUnavailableException.class, () -> provider(baseUrl).route(37, -122, 38, -121));
    }

    @Test
    void nonPositiveMetricsAreRejected() throws IOException {
        String baseUrl = startServer(200,
                "{\"code\":\"Ok\",\"routes\":[{\"distance\":-5.0,\"duration\":100.0}]}");

        assertThrows(ServiceUnavailableException.class, () -> provider(baseUrl).route(37, -122, 38, -121));
    }

    @Test
    void transportFailureIsServiceUnavailable() throws IOException {
        // A server that answers 500 exercises the transport-error branch.
        String baseUrl = startServer(500, "boom");

        assertThrows(ServiceUnavailableException.class, () -> provider(baseUrl).route(37, -122, 38, -121));
    }

    @Test
    void trailingSlashesInTheBaseUrlAreNormalized() throws IOException {
        String baseUrl = startServer(200,
                "{\"code\":\"Ok\",\"routes\":[{\"distance\":1000.0,\"duration\":60.0}]}");

        DeliveryRouteMetrics metrics = provider(baseUrl + "///").route(37, -122, 38, -121);

        assertEquals(1000, metrics.distanceMeters());
        assertEquals(60, metrics.durationSeconds());
    }
}
