package com.igilro.server.route.domain

/** Source tags only. Null signal information never means an unsignalized crossing. */
data class CrossingMetadata(val trafficSignals: Boolean? = null)
data class NodeGuidanceMetadata(val crossing: CrossingMetadata)
data class EdgeGuidanceMetadata(val osmWayId: Long, val streetName: String? = null, val crossing: CrossingMetadata? = null) {
    init {
        require(osmWayId > 0)
        require(streetName == null || streetName.isNotBlank() && streetName.length <= 256 && streetName.none(Char::isISOControl))
    }
}

object GuidanceMetadataVersion { const val OSM_V1 = "osm-guidance-tags-v1" }

internal fun WalkingGraph.crossingAt(
    junction: Long?,
    before: EdgeGuidanceMetadata?,
    after: EdgeGuidanceMetadata?,
): CrossingMetadata? = when {
    after?.crossing != null && (before?.crossing == null || before.osmWayId != after.osmWayId) -> after.crossing
    before?.crossing != null || after?.crossing != null -> null
    else -> junction?.let { nodeGuidance[it]?.crossing }
}
