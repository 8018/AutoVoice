package com.autovoice.server.navigation;

record NavigationCandidate(
        String candidateId,
        String poiname,
        String address,
        double lat,
        double lon,
        String rawJson) {
}
