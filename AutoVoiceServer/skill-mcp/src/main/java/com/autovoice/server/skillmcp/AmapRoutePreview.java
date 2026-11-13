package com.autovoice.server.skillmcp;

import com.autovoice.server.contracts.NavigationRoutePreview.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

final class AmapRoutePreview {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpUrl WEB = HttpUrl.get("https://restapi.amap.com/");
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).callTimeout(Duration.ofSeconds(3)).build();
    private final Clock clock;
    private final WebQuery query;

    @FunctionalInterface
    interface WebQuery {
        JsonNode get(String path, Map<String, String> params) throws IOException;
    }

    AmapRoutePreview(Clock clock, WebQuery query) {
        this.clock = clock;
        this.query = query;
    }

    static AmapRoutePreview from(SkillConfig config) {
        if (!config.enabled() || !"amap-maps".equals(config.id()) || !"llm".equals(config.normalizedScope())) {
            return null;
        }
        HttpUrl mcp = HttpUrl.parse(config.mcpUrl() == null ? "" : config.mcpUrl());
        if (mcp == null || !mcp.isHttps() || !"mcp.amap.com".equals(mcp.host()) || mcp.port() != 443
                || !mcp.username().isEmpty() || !mcp.password().isEmpty()) return null;
        List<String> keys = mcp.queryParameterValues("key");
        if (keys.size() != 1 || keys.getFirst() == null || keys.getFirst().isBlank()) return null;
        String key = keys.getFirst();
        return new AmapRoutePreview(Clock.systemUTC(), (path, params) -> {
            var url = WEB.newBuilder().encodedPath(path).addQueryParameter("key", key);
            params.forEach(url::addQueryParameter);
            var request = new okhttp3.Request.Builder().url(url.build()).build();
            try (var response = HTTP.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) throw new IOException("AMap request failed");
                return JSON.readTree(response.body().byteStream());
            }
        });
    }

    Result preview(Request request) {
        if (request.origin() == null) return Result.failure(request.operationId(), Status.ORIGIN_REQUIRED);
        try {
            List<Coordinate> points = new ArrayList<>();
            points.add(request.origin());
            points.addAll(request.waypoints());
            points.add(request.destination());
            convertGps(points);

            Coordinate origin = points.getFirst();
            Coordinate destination = points.getLast();
            List<Coordinate> waypoints = List.copyOf(points.subList(1, points.size() - 1));
            Map<String, String> params = new LinkedHashMap<>();
            params.put("origin", format(origin));
            params.put("destination", format(destination));
            params.put("show_fields", "cost");
            params.put("strategy", strategy(request.preference()));
            if (!waypoints.isEmpty()) {
                params.put("waypoints", waypoints.stream().map(AmapRoutePreview::format).collect(Collectors.joining(";")));
            }

            JsonNode response = query.get("/v5/direction/driving", params);
            if (response == null || !"1".equals(response.path("status").asText())) {
                return Result.failure(request.operationId(), Status.UNAVAILABLE);
            }
            JsonNode paths = response.path("route").path("paths");
            if (!paths.isArray() || paths.isEmpty()) return Result.failure(request.operationId(), Status.NO_ROUTE);

            String routeSetId = UUID.randomUUID().toString();
            List<Estimate> estimates = new ArrayList<>();
            for (JsonNode path : paths) {
                if (!path.isObject()) continue;
                JsonNode cost = path.path("cost");
                Long distance = integer(path.path("distance"));
                Long duration = integer(cost.path("duration"));
                Double toll = decimal(cost.path("tolls"));
                if (distance == null && duration == null && toll == null) continue;
                estimates.add(new Estimate(routeSetId + ":" + (estimates.size() + 1), distance, duration, toll));
                if (estimates.size() == 5) break;
            }
            if (estimates.isEmpty()) return Result.failure(request.operationId(), Status.NO_ROUTE);
            return new Result(request.operationId(), Status.OK, routeSetId, clock.millis(), "AMAP_WEB",
                    origin, destination, waypoints, request.preference(), List.copyOf(estimates));
        } catch (IOException | RuntimeException ignored) {
            // Downstream exceptions can contain credential-bearing URLs or user coordinates.
            return Result.failure(request.operationId(), Status.UNAVAILABLE);
        }
    }

    private void convertGps(List<Coordinate> points) throws IOException {
        List<Integer> indices = new ArrayList<>();
        List<String> gps = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            Coordinate point = points.get(i);
            if (point.coordinateSystem() == CoordinateSystem.WGS84) {
                indices.add(i);
                gps.add(format(point));
            }
        }
        if (gps.isEmpty()) return;
        JsonNode response = query.get("/v3/assistant/coordinate/convert",
                Map.of("locations", String.join("|", gps), "coordsys", "gps"));
        if (response == null || !"1".equals(response.path("status").asText())) throw new IOException("conversion failed");
        String[] values = response.path("locations").asText("").split(";", -1);
        if (values.length != indices.size()) throw new IOException("conversion count mismatch");
        for (int i = 0; i < values.length; i++) {
            String[] lonLat = values[i].split(",", -1);
            if (lonLat.length != 2) throw new IOException("invalid converted point");
            points.set(indices.get(i), new Coordinate(Double.parseDouble(lonLat[1]),
                    Double.parseDouble(lonLat[0]), CoordinateSystem.GCJ02));
        }
    }

    private static String format(Coordinate coordinate) {
        return coordinateNumber(coordinate.longitude()) + "," + coordinateNumber(coordinate.latitude());
    }

    private static String coordinateNumber(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static String strategy(Preference preference) {
        return switch (preference) {
            case DEFAULT -> "32";
            case AVOID_CONGESTION -> "33";
            case PREFER_HIGHWAYS -> "34";
            case AVOID_HIGHWAYS -> "35";
            case LESS_TOLLS -> "36";
            case FASTEST -> "38";
        };
    }

    private static Long integer(JsonNode value) {
        try {
            long number = Long.parseLong(value.asText());
            return number >= 0 ? number : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Double decimal(JsonNode value) {
        try {
            double number = Double.parseDouble(value.asText());
            return Double.isFinite(number) && number >= 0 ? number : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
