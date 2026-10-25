package com.igilro.server.place.domain

enum class PlaceSearchFailureReason {
    INVALID_QUERY, NOT_CONFIGURED, PROVIDER_AUTH_FAILED, RATE_LIMITED, TIMEOUT, UNAVAILABLE, BAD_RESPONSE,
}

// Deliberately excludes raw provider messages, credentials, query URIs and exception causes.
class PlaceSearchFailure(val reason: PlaceSearchFailureReason, val retryAfterSeconds: Long? = null) : RuntimeException(reason.name)
