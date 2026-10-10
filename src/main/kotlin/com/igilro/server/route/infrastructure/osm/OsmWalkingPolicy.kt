package com.igilro.server.route.infrastructure.osm

import com.igilro.server.route.domain.EdgeKind

/** Conservative, static foot permissions; conditional restrictions are deferred. */
class OsmWalkingPolicy {
    data class Permissions(val kind: EdgeKind, val forward: Boolean, val backward: Boolean)
    private val allowed = setOf("yes", "designated", "permissive")
    private val ordinary = setOf("footway", "pedestrian", "path", "steps", "living_street", "residential", "service", "unclassified", "track")
    private val majorRoads = setOf("tertiary", "tertiary_link", "secondary", "secondary_link", "primary", "primary_link")

    fun way(tags: Map<String, String>): Permissions? {
        if (tags.keys.any { it.endsWith(":conditional") } || "opening_hours" in tags) return null
        if (tags["area"] == "yes" || tags["construction"] != null) return null
        if (tags["conveying"] != null && tags["conveying"] != "no") return null
        if (!accessAllowed(tags)) return null
        val highway = tags["highway"] ?: return null
        if (highway !in ordinary) {
            val explicitFoot = tags["foot"] in allowed
            val sidewalk = tags["sidewalk"] in setOf("yes", "both", "left", "right")
            if (!(highway in majorRoads && (explicitFoot || sidewalk)) && !(highway == "cycleway" && explicitFoot)) return null
        }
        fun directionAllowed(direction: String): Boolean {
            val foot = tags["foot:$direction"]
            if (foot != null) return foot in allowed
            val access = tags["access:$direction"]
            return tags["foot"] in allowed || access == null || access in allowed
        }
        var forward = directionAllowed("forward")
        var backward = directionAllowed("backward")
        when (tags["oneway:foot"]) {
            "yes", "1", "true" -> backward = false
            "-1" -> forward = false
            null, "no", "0", "false" -> Unit
            else -> return null
        }
        if (!forward && !backward) return null
        return Permissions(if (highway == "steps") EdgeKind.STEPS else EdgeKind.WALKWAY, forward, backward)
    }

    fun nodeAllowed(tags: Map<String, String>): Boolean {
        if (!accessAllowed(tags) || tags["locked"] == "yes" || "opening_hours" in tags || tags.keys.any { it.endsWith(":conditional") }) return false
        return tags["barrier"] !in setOf("wall", "fence", "hedge", "ditch", "retaining_wall", "city_wall") || tags["foot"] in allowed
    }

    private fun accessAllowed(tags: Map<String, String>): Boolean {
        val foot = tags["foot"]
        if (foot != null) return foot in allowed
        return tags["access"] == null || tags["access"] in allowed
    }

    companion object { const val VERSION = "osm-foot-conservative-v1" }
}
