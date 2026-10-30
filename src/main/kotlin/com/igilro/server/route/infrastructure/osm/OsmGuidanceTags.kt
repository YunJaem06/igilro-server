package com.igilro.server.route.infrastructure.osm

import com.igilro.server.route.domain.*

/** Normalize only explicit pedestrian crossing tags; vehicle signals do not qualify. */
internal object OsmGuidanceTags {
    fun node(tags: Map<String,String>): NodeGuidanceMetadata? =
        if(tags["highway"]=="crossing" && tags["crossing"]!="no") NodeGuidanceMetadata(crossing(tags)) else null

    fun edge(wayId: Long,tags: Map<String,String>) = EdgeGuidanceMetadata(wayId,
        (tags["name:ko"] ?: tags["name"])?.trim()?.takeIf { it.isNotEmpty() && it.length<=256 && it.none(Char::isISOControl) },
        if(tags["footway"]=="crossing" && tags["crossing"]!="no") crossing(tags) else null)

    private fun crossing(tags: Map<String,String>): CrossingMetadata {
        val explicit=when(tags["crossing:signals"]) { "yes" -> true; "no" -> false; else -> null }
        val legacy=tags["crossing"]=="traffic_signals"
        return CrossingMetadata(if(legacy && explicit==false) null else explicit ?: if(legacy) true else null)
    }
}
