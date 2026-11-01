package com.igilro.server.route.infrastructure

import com.igilro.server.route.application.ImportWalkingGraphUseCase
import com.igilro.server.route.application.SearchWalkingRouteUseCase
import com.igilro.server.route.application.RouteShadeEvaluator
import com.igilro.server.route.application.RouteShadeBatchEvaluator
import com.igilro.server.route.application.ImportBuildingShadeUseCase
import com.igilro.server.route.domain.shade.BuildingShadeImportRepository
import com.igilro.server.route.domain.*
import com.igilro.server.route.application.ModelPredictionSource
import com.igilro.server.route.application.SearchModelPredictionUseCase
import com.igilro.server.route.application.RouteSearchAdmission
import com.igilro.server.route.infrastructure.prediction.FileModelPredictionSource
import java.nio.file.Path
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import java.time.Clock

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RouteSearchProperties::class)
class RouteConfiguration {
    @Bean
    fun routeSearchAdmission(properties: RouteSearchProperties) = RouteSearchAdmission(
        properties.requestsPerSecond, properties.burstCapacity, properties.maxConcurrentSearches,
    )

    @Bean
    fun modelPredictionSource(@Value("\${igilro.shade-prediction.file:}") file: String): ModelPredictionSource =
        FileModelPredictionSource(file.takeIf { it.isNotBlank() }?.let(Path::of))

    @Bean
    fun searchModelPredictionUseCase(source: ModelPredictionSource, clock: Clock, admission: RouteSearchAdmission) =
        SearchModelPredictionUseCase(source, clock, admission)

    @Bean
    fun searchWalkingRouteUseCase(repository: WalkingGraphRepository, clock: Clock, shadeEvaluator: RouteShadeEvaluator,
        batchEvaluator: RouteShadeBatchEvaluator, admission: RouteSearchAdmission,
        @Value("\${igilro.shade-routing.ground-verified-datasets:}") groundVerifiedDatasets: String) =
        SearchWalkingRouteUseCase(repository, DijkstraRoutePlanner(), clock, shadeEvaluator, admission, batchEvaluator,
            groundVerifiedDatasets.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet())

    @Bean
    fun importWalkingGraphUseCase(repository: GraphImportRepository) = ImportWalkingGraphUseCase(repository)

    @Bean
    fun importBuildingShadeUseCase(repository: BuildingShadeImportRepository) = ImportBuildingShadeUseCase(repository)
}
