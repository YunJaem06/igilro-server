package com.igilro.server.route.application

import com.igilro.server.route.domain.Coordinate
import com.igilro.server.route.domain.shade.ShadePathMeasurement
import java.time.Instant

fun interface RouteShadeBatchEvaluator {
    /** One immutable building snapshot and one solar position; results retain input order. */
    fun evaluate(areaId: String, paths: List<List<Coordinate>>, reference: Coordinate, evaluatedAt: Instant): List<ShadePathMeasurement>
}
