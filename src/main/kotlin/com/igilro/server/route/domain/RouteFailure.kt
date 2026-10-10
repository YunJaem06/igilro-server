package com.igilro.server.route.domain

enum class RouteFailureReason { INVALID_QUERY, OUTSIDE_SUPPORTED_AREA, NOT_FOUND, DATA_UNAVAILABLE }

class RouteFailure(val reason: RouteFailureReason, cause: Throwable? = null) : RuntimeException(reason.name, cause)
