package com.autovoice.server.contracts;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Read-only departure planning. Route identifiers are local to a single preview result. */
public interface NavigationRoutePreview {
    NavigationRoutePreview NONE = new NavigationRoutePreview() {
        @Override public boolean available() { return false; }

        @Override public CompletableFuture<Result> plan(Request request) {
            return CompletableFuture.completedFuture(Result.failure(request.operationId(), Status.UNAVAILABLE));
        }
    };

    boolean available();

    CompletableFuture<Result> plan(Request request);

    enum CoordinateSystem { WGS84, GCJ02 }

    enum Preference {
        DEFAULT, AVOID_CONGESTION, PREFER_HIGHWAYS, AVOID_HIGHWAYS, LESS_TOLLS, FASTEST
    }

    enum Status { OK, ORIGIN_REQUIRED, INVALID_REQUEST, UNAVAILABLE, NO_ROUTE, BUSY }

    record Coordinate(double latitude, double longitude, CoordinateSystem coordinateSystem) {
        public Coordinate {
            if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                    || Math.abs(latitude) > 90 || Math.abs(longitude) > 180 || coordinateSystem == null) {
                throw new IllegalArgumentException("invalid coordinate");
            }
        }
    }

    record Request(String operationId, Coordinate origin, Coordinate destination,
                   List<Coordinate> waypoints, Preference preference) {
        public Request {
            if (operationId == null || operationId.isBlank() || operationId.length() > 128
                    || destination == null || waypoints == null || waypoints.size() > 4 || preference == null) {
                throw new IllegalArgumentException("invalid route preview request");
            }
            waypoints = List.copyOf(waypoints);
        }
    }

    record Estimate(String id, Long distanceMeters, Long durationSeconds, Double tollYuan) {}

    record Result(String operationId, Status status, String routeSetId, Long plannedAtMs, String source,
                  Coordinate origin, Coordinate destination, List<Coordinate> waypoints,
                  Preference preference, List<Estimate> routes) {
        public static Result failure(String operationId, Status status) {
            return new Result(operationId, status, null, null, null, null, null,
                    List.of(), null, List.of());
        }
    }
}
