package com.igilro.server.route.application

import com.igilro.server.route.domain.Coordinate
import com.igilro.server.route.domain.shade.ShadeEvaluation
import java.time.Instant

fun interface RouteShadeEvaluator {
    fun evaluate(areaId: String, coordinates: List<Coordinate>, evaluatedAt: Instant): ShadeEvaluation
}
