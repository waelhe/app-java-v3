package com.marketplace.delivery;

import com.marketplace.shared.api.ServiceUnavailableException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Adapter for a self-hosted or organization-managed OSRM HTTP endpoint.
 * The base URL is required at runtime; the public demo server is never assumed.
 */
@Component
final class OsrmDeliveryRouteProvider implements DeliveryRouteProvider {

    private final RestClient.Builder restClientBuilder;
    private final DeliveryProperties properties;

    OsrmDeliveryRouteProvider(RestClient.Builder restClientBuilder, DeliveryProperties properties) {
        this.restClientBuilder = restClientBuilder;
        this.properties = properties;
    }

    @Override
    public DeliveryRouteMetrics route(double fromLatitude, double fromLongitude,
                                      double toLatitude, double toLongitude) {
        String baseUrl = properties.routingBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new ServiceUnavailableException("Road-routing service is not configured");
        }
        validateCoordinate(fromLatitude, fromLongitude);
        validateCoordinate(toLatitude, toLongitude);
        String coordinates = String.format(Locale.ROOT, "%.6f,%.6f;%.6f,%.6f",
                fromLongitude, fromLatitude, toLongitude, toLatitude);
        String normalizedBase = baseUrl.trim().replaceAll("/+$", "");
        URI uri = UriComponentsBuilder.fromUriString(normalizedBase)
                .pathSegment("route", "v1", "driving", coordinates)
                .queryParam("overview", "false")
                .queryParam("steps", "false")
                .build()
                .encode()
                .toUri();
        try {
            var requestFactory = new org.springframework.http.client.JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
            requestFactory.setReadTimeout(Duration.ofSeconds(3));
            OsrmRouteResponse response = restClientBuilder.clone()
                    .requestFactory(requestFactory)
                    .build()
                    .get()
                    .uri(uri)
                    .retrieve()
                    .body(OsrmRouteResponse.class);
            if (response == null || !"Ok".equals(response.code())
                    || response.routes() == null || response.routes().isEmpty()) {
                throw new ServiceUnavailableException("Road-routing service returned no route");
            }
            OsrmRouteResponse.Route route = response.routes().getFirst();
            if (!Double.isFinite(route.distance()) || !Double.isFinite(route.duration())
                    || route.distance() <= 0 || route.duration() <= 0
                    || route.distance() > Long.MAX_VALUE || route.duration() > Long.MAX_VALUE) {
                throw new ServiceUnavailableException("Road-routing service returned invalid metrics");
            }
            return new DeliveryRouteMetrics(Math.round(route.distance()), Math.max(1, Math.round(route.duration())));
        } catch (ServiceUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ServiceUnavailableException("Road-routing service is unavailable");
        }
    }

    private static void validateCoordinate(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new ServiceUnavailableException("Invalid delivery coordinates");
        }
    }

    record OsrmRouteResponse(String code, List<Route> routes) {
        record Route(double distance, double duration) { }
    }
}
