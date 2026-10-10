package com.igilro.server.health.application

import com.igilro.server.health.domain.HealthRepository
import com.igilro.server.health.domain.HealthStatus
import com.igilro.server.health.domain.ServiceHealth
import java.time.Clock

class CheckHealthUseCase(
    private val healthRepository: HealthRepository,
    private val clock: Clock,
) {
    fun execute(): ServiceHealth {
        val ready = healthRepository.isSpatialDatabaseReady()
        return ServiceHealth(
            status = if (ready) HealthStatus.UP else HealthStatus.DOWN,
            checkedAt = clock.instant(),
        )
    }
}
