package com.igilro.server.route.domain

enum class RouteFailureReason {
    INVALID_QUERY, OUTSIDE_SUPPORTED_AREA, NOT_FOUND, DATA_UNAVAILABLE,
    SHADE_DATA_UNAVAILABLE, SHADE_TIME_UNSUPPORTED, SEARCH_LIMIT_EXCEEDED,
    PREDICTION_DATA_UNAVAILABLE,
}

class RouteFailure(val reason: RouteFailureReason, cause: Throwable? = null) : RuntimeException(reason.name, cause)
