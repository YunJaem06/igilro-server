package com.igilro.server.health.api

import com.igilro.server.health.domain.ServiceHealth

object HealthResponseMapper {
    fun toResponse(health: ServiceHealth) = HealthResponse(
        status = health.status.name,
        checkedAt = health.checkedAt,
    )
}
