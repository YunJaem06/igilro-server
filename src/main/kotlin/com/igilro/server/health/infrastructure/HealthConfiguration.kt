package com.igilro.server.health.infrastructure

import com.igilro.server.health.application.CheckHealthUseCase
import com.igilro.server.health.domain.HealthRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class HealthConfiguration {
    @Bean
    fun checkHealthUseCase(repository: HealthRepository, clock: Clock) = CheckHealthUseCase(repository, clock)
}
