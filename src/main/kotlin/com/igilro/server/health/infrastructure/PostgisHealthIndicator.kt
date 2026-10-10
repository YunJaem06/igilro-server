package com.igilro.server.health.infrastructure

import com.igilro.server.health.domain.HealthRepository
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component

@Component("postgisHealthIndicator")
class PostgisHealthIndicator(private val repository: HealthRepository) : HealthIndicator {
    override fun health(): Health =
        if (repository.isSpatialDatabaseReady()) Health.up().build() else Health.down().build()
}
