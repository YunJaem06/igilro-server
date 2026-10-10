package com.igilro.server.health.domain

import java.time.Instant

enum class HealthStatus { UP, DOWN }

data class ServiceHealth(
    val status: HealthStatus,
    val checkedAt: Instant,
)
