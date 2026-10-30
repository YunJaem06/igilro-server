package com.igilro.server.route.domain

import kotlin.math.*

enum class GuidanceManeuver { DEPART, CONTINUE, KEEP_LEFT, KEEP_RIGHT, TURN_LEFT, TURN_RIGHT, U_TURN, CROSSING, STAIRS, ARRIVE }

data class GuidanceStep(
    val index: Int,
    val type: GuidanceManeuver,
    val coordinate: Coordinate,
    val geometryIndex: Int,
    val distanceFromStartMeters: Double,
    val distanceToNextStepMeters: Double,
    val durationToNextStepSeconds: Double,
    val instruction: String,
    val streetName: String?,
    val bearingBeforeDegrees: Double?,
    val bearingAfterDegrees: Double?,
    val crossing: CrossingMetadata?,
)

data class RouteGuidance(
    val status: String,
    val metadataStatus: String,
    val steps: List<GuidanceStep>,
    val modelVersion: String = RouteGuidanceGenerator.VERSION,
    val language: String = "ko",
)

/** Guidance annotates the chosen directed edge trace; it never changes the route. */
class RouteGuidanceGenerator {
    private data class Leg(val edge: WalkingEdge, val trace: RouteTraversal, val from: Coordinate, val to: Coordinate,
        val meters: Double, val seconds: Double, val metadata: EdgeGuidanceMetadata?)
    private data class Event(val geometryIndex: Int, val type: GuidanceManeuver, val crossing: CrossingMetadata? = null)

    fun generate(graph: WalkingGraph, route: WalkingRoute): RouteGuidance {
        val metadataStatus = if(graph.guidanceMetadataVersion == null) "TOPOLOGY_ONLY" else "OSM_TAGS"
        if(route.traversals.isEmpty()) return RouteGuidance("UNAVAILABLE", metadataStatus, emptyList())
        val edges = graph.edges.associateBy { it.id }
        val legs = route.traversals.map { trace ->
            val edge = edges.getValue(trace.edgeId)
            require(if(trace.toFraction > trace.fromFraction) edge.forwardAllowed else edge.backwardAllowed)
            val meters = edge.distanceMeters * abs(trace.toFraction-trace.fromFraction)
            Leg(edge,trace,point(graph,edge,trace.fromFraction),point(graph,edge,trace.toFraction),meters,
                WalkingTimeModel.duration(meters,edge.kind),graph.edgeGuidance[edge.id])
        }
        require(route.coordinates.size == legs.size+1)
        legs.forEachIndexed { i,leg ->
            require(leg.from.distanceTo(route.coordinates[i]) < .001 && leg.to.distanceTo(route.coordinates[i+1]) < .001)
            if(i>0) require(endNode(legs[i-1]) != null && endNode(legs[i-1]) == startNode(leg))
        }
        require(abs(legs.sumOf { it.meters }-route.distanceMeters) < .001)
        require(abs(legs.sumOf { it.seconds }-route.durationSeconds) < .001)
        val offsets = mutableListOf(0.0)
        val times = mutableListOf(0.0)
        val geometryOffsets = mutableListOf(0.0)
        route.coordinates.zipWithNext().forEach { (a,b) -> geometryOffsets.add(geometryOffsets.last()+a.distanceTo(b)) }
        legs.forEach { offsets.add(offsets.last()+it.meters); times.add(times.last()+it.seconds) }
        val events = mutableListOf(Event(0,GuidanceManeuver.DEPART,legs.first().metadata?.crossing))
        for(i in 1 until legs.size) {
            val before = legs[i-1]
            val after = legs[i]
            val node = requireNotNull(startNode(after))
            val incomingNeighbor = if(node == before.edge.to) before.edge.from else before.edge.to
            // Parallel duplicate edges to the same neighbor are one option. A geometric
            // intersection without a shared source node is never a walking junction.
            val options = graph.adjacency[node].orEmpty().filter { it.to != incomingNeighbor }.distinctBy { it.to }
            val crossing = when {
                after.metadata?.crossing != null && (before.metadata?.crossing == null ||
                    before.metadata.osmWayId != after.metadata.osmWayId) -> after.metadata.crossing
                before.metadata?.crossing != null || after.metadata?.crossing != null -> null
                else -> graph.nodeGuidance[node]?.crossing
            }
            val branch = options.size > 1
            val stairs = after.edge.kind == EdgeKind.STEPS && before.edge.kind != EdgeKind.STEPS
            if(!branch && crossing == null && !stairs) continue
            val turn = if(branch) maneuver(graph,route.coordinates,geometryOffsets,i,options,after) else GuidanceManeuver.CONTINUE
            val type = when {
                turn != GuidanceManeuver.CONTINUE -> turn
                crossing != null -> GuidanceManeuver.CROSSING
                stairs -> GuidanceManeuver.STAIRS
                else -> GuidanceManeuver.CONTINUE
            }
            events.add(Event(i,type,crossing))
        }
        events.add(Event(route.coordinates.lastIndex,GuidanceManeuver.ARRIVE))
        val steps = events.mapIndexed { index,event ->
            val i = event.geometryIndex
            val next = events.getOrNull(index+1)?.geometryIndex ?: i
            val street = legs.getOrNull(i)?.metadata?.streetName
            GuidanceStep(index,event.type,route.coordinates[i],i,offsets[i],offsets[next]-offsets[i],times[next]-times[i],
                instruction(event.type,street,event.crossing),street,
                if(i==0) null else bearing(look(route.coordinates,geometryOffsets,i,-1),route.coordinates[i]),
                if(i==route.coordinates.lastIndex) null else bearing(route.coordinates[i],look(route.coordinates,geometryOffsets,i,1)),event.crossing)
        }
        return RouteGuidance("AVAILABLE",metadataStatus,steps)
    }

    private fun maneuver(graph: WalkingGraph,path: List<Coordinate>,geometryOffsets: List<Double>,index: Int,options: List<GraphArc>,after: Leg): GuidanceManeuver {
        val incoming = bearing(look(path,geometryOffsets,index,-1),path[index])
        val outgoing = bearing(path[index],look(path,geometryOffsets,index,1))
        val turn = signedAngle(outgoing-incoming)
        if(abs(turn)>=160) return GuidanceManeuver.U_TURN
        if(abs(turn)>=45) return if(turn<0) GuidanceManeuver.TURN_LEFT else GuidanceManeuver.TURN_RIGHT
        if(abs(turn)>=20) return if(turn<0) GuidanceManeuver.KEEP_LEFT else GuidanceManeuver.KEEP_RIGHT
        val alternatives = options.filter { it.traversal?.edgeId != after.edge.id }.map {
            signedAngle(bearing(path[index],graph.nodes.getValue(it.to))-incoming)
        }.filter { abs(it)<60 }
        // At a shallow fork, indicate which branch was chosen even if it is nearly straight.
        return when {
            alternatives.isNotEmpty() && alternatives.all { it > turn+15 } -> GuidanceManeuver.KEEP_LEFT
            alternatives.isNotEmpty() && alternatives.all { it < turn-15 } -> GuidanceManeuver.KEEP_RIGHT
            else -> GuidanceManeuver.CONTINUE
        }
    }

    private fun instruction(type: GuidanceManeuver,street: String?,crossing: CrossingMetadata?): String {
        val road = street?.let { "${it} 방면으로 " } ?: ""
        val base = when(type) {
            GuidanceManeuver.DEPART -> "${road}경로를 따라 출발하세요."
            GuidanceManeuver.CONTINUE -> "갈림길에서 ${road}계속 직진하세요."
            GuidanceManeuver.KEEP_LEFT -> "갈림길에서 왼쪽 길을 따라 ${road}진행하세요."
            GuidanceManeuver.KEEP_RIGHT -> "갈림길에서 오른쪽 길을 따라 ${road}진행하세요."
            GuidanceManeuver.TURN_LEFT -> "갈림길에서 왼쪽으로 돌아 ${road}진행하세요."
            GuidanceManeuver.TURN_RIGHT -> "갈림길에서 오른쪽으로 돌아 ${road}진행하세요."
            GuidanceManeuver.U_TURN -> "갈림길에서 방향을 돌려 진행하세요."
            GuidanceManeuver.CROSSING -> ""
            GuidanceManeuver.STAIRS -> "계단 구간으로 진행하세요."
            GuidanceManeuver.ARRIVE -> "경로의 도착 지점에 도착했어요."
        }
        val cross = when(crossing?.trafficSignals) {
            true -> "신호가 설치된 횡단 지점을 건너세요."
            else -> if(crossing!=null) "횡단 지점을 건너세요." else ""
        }
        return listOf(base,cross).filter(String::isNotEmpty).joinToString(" ")
    }

    private fun startNode(leg: Leg): Long? = when {
        leg.trace.fromFraction < 1e-9 -> leg.edge.from
        leg.trace.fromFraction > 1-1e-9 -> leg.edge.to
        else -> null
    }
    private fun endNode(leg: Leg): Long? = when {
        leg.trace.toFraction < 1e-9 -> leg.edge.from
        leg.trace.toFraction > 1-1e-9 -> leg.edge.to
        else -> null
    }
    private fun point(graph: WalkingGraph,edge: WalkingEdge,f: Double): Coordinate {
        val a=graph.nodes.getValue(edge.from); val b=graph.nodes.getValue(edge.to)
        return when {
            f<1e-9 -> a
            f>1-1e-9 -> b
            else -> Coordinate(a.longitude+(b.longitude-a.longitude)*f,a.latitude+(b.latitude-a.latitude)*f)
        }
    }
    private fun look(path: List<Coordinate>,offsets: List<Double>,index: Int,direction: Int): Coordinate {
        val target=(offsets[index]+direction*10.0).coerceIn(0.0,offsets.last())
        val found=offsets.binarySearch(target)
        if(found>=0) return path[found]
        val right=-found-1;val left=right-1
        val f=(target-offsets[left])/(offsets[right]-offsets[left])
        val a=path[left];val b=path[right]
        return Coordinate(a.longitude+(b.longitude-a.longitude)*f,a.latitude+(b.latitude-a.latitude)*f)
    }
    private fun bearing(a: Coordinate,b: Coordinate): Double {
        val lat1=Math.toRadians(a.latitude);val lat2=Math.toRadians(b.latitude);val lon=Math.toRadians(b.longitude-a.longitude)
        return (Math.toDegrees(atan2(sin(lon)*cos(lat2),cos(lat1)*sin(lat2)-sin(lat1)*cos(lat2)*cos(lon)))+360)%360
    }
    private fun signedAngle(angle: Double)=(angle+540)%360-180
    companion object { const val VERSION="walking-guidance-v1-topology-look10m" }
}
