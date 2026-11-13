package com.autovoice.server.gateway;

import com.autovoice.server.contracts.NavigationRoutePreview;
import com.autovoice.server.contracts.NavigationRoutePreview.*;
import com.autovoice.server.contracts.SessionContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class GatewayRoutePreviewEndpoint {
    static final String CAPABILITY = "navigation_route_preview";
    private static final String RESULT = "navigation_route_preview_result";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final NavigationRoutePreview preview;
    private final GatewayDownlink downlink;

    GatewayRoutePreviewEndpoint(NavigationRoutePreview preview, GatewayDownlink downlink) {
        this.preview = preview;
        this.downlink = downlink;
    }

    boolean supported() {
        return preview.available();
    }

    void request(WebSocketSession session, SessionContext context, AtomicReference<String> pending,
                 Map<String, Object> payload) {
        if (context == null || !context.sessionId().equals(payload.get("sessionId"))) return;
        if (!(payload.get("operationId") instanceof String operationId)
                || !operationId.matches("[A-Za-z0-9_-]{1,128}")) return;

        Request request;
        try {
            Coordinate origin = payload.get("origin") == null ? null : coordinate(payload.get("origin"));
            request = new Request(operationId, origin, coordinate(payload.get("destination")),
                    waypoints(payload.getOrDefault("waypoints", List.of())),
                    Preference.valueOf(String.valueOf(payload.get("preference"))));
        } catch (IllegalArgumentException ignored) {
            send(session, Result.failure(operationId, Status.INVALID_REQUEST));
            return;
        }
        if (request.origin() == null) {
            send(session, Result.failure(operationId, Status.ORIGIN_REQUIRED));
            return;
        }
        if (!supported()) {
            send(session, Result.failure(operationId, Status.UNAVAILABLE));
            return;
        }
        if (!pending.compareAndSet(null, operationId)) {
            if (!operationId.equals(pending.get())) send(session, Result.failure(operationId, Status.BUSY));
            return;
        }

        CompletableFuture<Result> planned;
        try {
            planned = preview.plan(request);
            if (planned == null) throw new IllegalStateException("route provider did not start planning");
        } catch (RuntimeException ignored) {
            pending.compareAndSet(operationId, null);
            send(session, Result.failure(operationId, Status.UNAVAILABLE));
            return;
        }
        planned.orTimeout(7, TimeUnit.SECONDS).whenComplete((result, error) -> {
            pending.compareAndSet(operationId, null);
            if (session.isOpen()) {
                send(session, error == null && result != null && operationId.equals(result.operationId())
                        ? result : Result.failure(operationId, Status.UNAVAILABLE));
            }
        });
    }

    private void send(WebSocketSession session, Result result) {
        Map<String, Object> payload = JSON.convertValue(result, new TypeReference<>() {});
        downlink.send(session, RESULT, payload);
    }

    private static Coordinate coordinate(Object value) {
        if (!(value instanceof Map<?, ?> point) || !(point.get("latitude") instanceof Number latitude)
                || !(point.get("longitude") instanceof Number longitude)) {
            throw new IllegalArgumentException("invalid coordinate");
        }
        return new Coordinate(latitude.doubleValue(), longitude.doubleValue(),
                CoordinateSystem.valueOf(String.valueOf(point.get("coordinateSystem"))));
    }

    private static List<Coordinate> waypoints(Object value) {
        if (!(value instanceof List<?> points) || points.size() > 4) {
            throw new IllegalArgumentException("invalid waypoints");
        }
        List<Coordinate> waypoints = new ArrayList<>();
        for (Object point : points) waypoints.add(coordinate(point));
        return waypoints;
    }
}
