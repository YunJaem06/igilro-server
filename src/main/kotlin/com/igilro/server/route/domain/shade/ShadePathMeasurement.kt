package com.igilro.server.route.domain.shade

/** Length uses the evaluator's projected geometry, independently of the walking time model. */
data class ShadePathMeasurement(val evaluation: ShadeEvaluation, val lengthMeters: Double? = null) {
    init {
        require(lengthMeters == null || lengthMeters.isFinite() && lengthMeters > 0)
        require(evaluation.status != ShadeStatus.AVAILABLE || lengthMeters != null)
    }
}
