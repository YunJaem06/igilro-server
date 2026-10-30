package com.igilro.server.route.domain.shade

enum class ShadeStatus { AVAILABLE, DATA_UNAVAILABLE, INCOMPLETE_COVERAGE, NIGHT, LOW_SUN, UNSUPPORTED_TIME }

data class ShadeEvaluation(
    val status: ShadeStatus,
    val ratio: Double? = null,
    val coverage: Double? = null,
    val dataVersion: String? = null,
    val attribution: String? = null,
    val solarAltitudeDegrees: Double? = null,
) {
    init {
        require(ratio == null || ratio.isFinite() && ratio in 0.0..1.0)
        require(coverage == null || coverage.isFinite() && coverage in 0.0..1.0)
        require((status == ShadeStatus.AVAILABLE) == (ratio != null))
        require(status != ShadeStatus.AVAILABLE || coverage == 1.0 && !dataVersion.isNullOrBlank())
    }

    companion object {
        const val MODEL_VERSION = "building-prism-v2-noaa-5179-minsun5-height-source"
    }
}
