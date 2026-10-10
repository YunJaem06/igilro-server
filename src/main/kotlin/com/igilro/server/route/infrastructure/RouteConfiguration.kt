package com.igilro.server.route.infrastructure

import com.igilro.server.route.application.ImportWalkingGraphUseCase
import com.igilro.server.route.application.SearchWalkingRouteUseCase
import com.igilro.server.route.domain.*
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class RouteConfiguration {
    @Bean
    fun searchWalkingRouteUseCase(repository: WalkingGraphRepository, clock: Clock) =
        SearchWalkingRouteUseCase(repository, DijkstraRoutePlanner(), clock)

    @Bean
    fun importWalkingGraphUseCase(repository: GraphImportRepository) = ImportWalkingGraphUseCase(repository)
}
