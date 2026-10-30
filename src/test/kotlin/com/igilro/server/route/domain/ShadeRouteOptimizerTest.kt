package com.igilro.server.route.domain

import com.igilro.server.route.domain.shade.*
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.*

class ShadeRouteOptimizerTest {
    private val a = Coordinate(127.03, 37.54)
    private val b = Coordinate(127.032, 37.54)
    private val c = Coordinate(127.031, 37.5404)
    private val bounds = Bounds(127.02, 37.53, 127.05, 37.56)
    private val optimizer = ShadeRouteOptimizer()
    private fun graph(detour: Double = 250.0) = WalkingGraph("fixture", "test", bounds,
        mapOf(1L to a, 2L to b, 3L to c),
        listOf(WalkingEdge(1, 1, 2, 200.0), WalkingEdge(2, 1, 3, detour / 2), WalkingEdge(3, 3, 2, detour / 2)))
    private fun measurements(network: ShadeRouteOptimizer.Network, ratio: (ShadeRouteOptimizer.Section) -> Double) =
        listOf(ShadePathMeasurement(ShadeEvaluation(ShadeStatus.AVAILABLE, 0.0, 1.0, "buildings", "fixture", 60.0), 200.0)) +
            network.sections.map { section ->
                ShadePathMeasurement(ShadeEvaluation(ShadeStatus.AVAILABLE, ratio(section), 1.0, "buildings", "fixture", 60.0),
                    network.graph.edges.single { it.id == section.edgeId }.distanceMeters * (section.to - section.from))
            }

    @Test
    fun `shaded detour wins inside the time budget and endpoints remain fixed`() {
        val g = graph()
        val baseline = DijkstraRoutePlanner().find(g, a, b, 50.0)
        val network = optimizer.prepare(g, baseline)
        val result = optimizer.find(network, measurements(network) { if (it.edgeId == 1L) 0.0 else 1.0 })
        assertEquals(listOf(a, c, b), result.route.coordinates)
        assertEquals(250.0, result.route.distanceMeters)
        assertEquals(1.0, result.shade.ratio)
        assertEquals(baseline.origin, result.route.origin)
        assertEquals(baseline.destination, result.route.destination)
        assertEquals(listOf(2L,3L),result.route.traversals.map { it.edgeId })
        assertEquals("AVAILABLE",RouteGuidanceGenerator().generate(g,result.route).status)
    }

    @Test
    fun `exact thirty percent detour is allowed but a longer one is excluded`() {
        for (detour in listOf(260.0, 260.01)) {
            val g = graph(detour)
            val network = optimizer.prepare(g, DijkstraRoutePlanner().find(g, a, b, 50.0))
            val result = optimizer.find(network, measurements(network) { if (it.edgeId == 1L) 0.0 else 1.0 })
            assertEquals(if (detour == 260.0) detour else 200.0, result.route.distanceMeters, 1e-8)
        }
    }

    @Test
    fun `zero shade and equal exposure choose the faster journey`() {
        val g = graph()
        val network = optimizer.prepare(g, DijkstraRoutePlanner().find(g, a, b, 50.0))
        for (ratio in listOf(0.0, 1.0)) {
            assertEquals(listOf(a, b), optimizer.find(network, measurements(network) { ratio }).route.coordinates)
        }
        // A higher shade percentage alone can still leave more time in the sun.
        assertEquals(listOf(a, b), optimizer.find(network, measurements(network) { if (it.edgeId == 1L) 0.2 else 0.3 }).route.coordinates)
    }

    @Test
    fun `mid edge sections are measured separately rather than scaled from the entire edge`() {
        val g = graph()
        val baseline = DijkstraRoutePlanner().find(g, Coordinate(127.0305, 37.54), Coordinate(127.0315, 37.54), 50.0)
        val network = optimizer.prepare(g, baseline)
        val result = optimizer.find(network, measurements(network) { section ->
            if (section.from > 0 && section.to < 1) 1.0 else 0.0
        })
        assertEquals(baseline.coordinates, result.route.coordinates)
        assertEquals(100.0, result.route.distanceMeters, 1e-6)
        assertEquals(1.0, result.shade.ratio)
        assertTrue(network.paths.any { it == baseline.coordinates })
        assertEquals(baseline.traversals,result.route.traversals)
        assertEquals(result.route.distanceMeters,RouteGuidanceGenerator().generate(g,result.route).steps.sumOf { it.distanceToNextStepMeters },1e-6)
    }

    @Test
    fun `one way sections cannot be traversed backwards to collect shade`() {
        val g = WalkingGraph("fixture", "test", bounds, mapOf(1L to a, 2L to b, 3L to c), listOf(
            WalkingEdge(1, 1, 2, 200.0), WalkingEdge(2, 3, 1, 110.0, backwardAllowed = false),
            WalkingEdge(3, 3, 2, 110.0, backwardAllowed = false)))
        val network = optimizer.prepare(g, DijkstraRoutePlanner().find(g, a, b, 50.0))
        assertEquals(listOf(a, b), optimizer.find(network, measurements(network) { if (it.edgeId == 1L) 0.0 else 1.0 }).route.coordinates)
    }

    @Test
    fun `a faster but sunnier prefix must survive dominance under the time constraint`() {
        val d = Coordinate(127.0315, 37.5402)
        val g = WalkingGraph("fixture", "test", bounds, mapOf(1L to a, 2L to b, 3L to c, 4L to d, 5L to Coordinate(127.0305,37.5405)), listOf(
            WalkingEdge(1,1,3,56.0,backwardAllowed=false), // 40s, 30s sun
            WalkingEdge(2,1,5,42.0,backwardAllowed=false), WalkingEdge(3,5,3,42.0,backwardAllowed=false), // 60s, no sun
            WalkingEdge(4,3,2,84.0,backwardAllowed=false), // 60s sun
            WalkingEdge(5,3,4,59.5,backwardAllowed=false), WalkingEdge(6,4,2,59.5,backwardAllowed=false))) // 85s, no sun
        val baseline = DijkstraRoutePlanner().find(g,a,b,50.0)
        assertEquals(100.0,baseline.durationSeconds,1e-8)
        val network=optimizer.prepare(g,baseline)
        val result=optimizer.find(network,measurements(network) { when(it.edgeId) { 1L -> 0.25; 4L -> 0.0; else -> 1.0 } })
        assertEquals(listOf(a,c,d,b),result.route.coordinates)
        assertEquals(125.0,result.route.durationSeconds,1e-8)
    }

    @Test
    fun `missing coverage mixed versions and incomplete result sets fail closed`() {
        val g = graph()
        val network = optimizer.prepare(g, DijkstraRoutePlanner().find(g,a,b,50.0))
        val valid = measurements(network) { 0.5 }
        for (invalid in listOf(valid.dropLast(1), valid.mapIndexed { i,v -> if(i==1) v.copy(evaluation=v.evaluation.copy(dataVersion="other")) else v },
            valid.mapIndexed { i,v -> if(i==1) ShadePathMeasurement(ShadeEvaluation(ShadeStatus.INCOMPLETE_COVERAGE,coverage=0.9)) else v })) {
            assertEquals(RouteFailureReason.SHADE_DATA_UNAVAILABLE, assertFailsWith<RouteFailure> { optimizer.find(network,invalid) }.reason)
        }
    }

    @Test
    fun `search limits never return an unproven best route`() {
        val g = graph()
        val network = optimizer.prepare(g, DijkstraRoutePlanner().find(g,a,b,50.0))
        assertEquals(RouteFailureReason.SEARCH_LIMIT_EXCEEDED,assertFailsWith<RouteFailure> {
            ShadeRouteOptimizer(maximumLabels=1).find(network,measurements(network) { 0.5 })
        }.reason)
    }

    @Test
    fun `response shade ratio is weighted by measured length independently of walking time`() {
        val g=WalkingGraph("fixture","test",bounds,mapOf(1L to a,2L to b,3L to c),listOf(
            WalkingEdge(1,1,3,100.0,EdgeKind.STEPS),WalkingEdge(2,3,2,100.0)))
        val network=optimizer.prepare(g,DijkstraRoutePlanner().find(g,a,b,50.0))
        val values=measurements(network) { if(it.edgeId==1L) 1.0 else 0.0 }.mapIndexed { index,value ->
            if(index==0) value else value.copy(lengthMeters=if(network.sections[index-1].edgeId==1L) 10.0 else 90.0)
        }
        val result=optimizer.find(network,values)
        assertEquals(0.1,result.shade.ratio!!,1e-10)
        assertEquals(100/0.9+100/1.4,result.route.durationSeconds,1e-10)
    }

    @Test
    fun `optimizer agrees with an independent exhaustive path oracle on small directed graphs`() {
        repeat(40) { seed ->
            val random=Random(seed)
            val nodes=(1L..6L).associateWith { Coordinate(127.03+(it-1)*0.0004,37.54) }
            var id=0L
            val edges=(1L..5L).flatMap { from -> (from+1..6L).map { to ->
                WalkingEdge(++id,from,to,random.nextDouble(10.0,100.0),backwardAllowed=false)
            } }
            val ratios=edges.associate { it.id to random.nextDouble() }
            val g=WalkingGraph("fixture","test",bounds,nodes,edges)
            val baseline=DijkstraRoutePlanner().find(g,nodes.getValue(1),nodes.getValue(6),50.0)
            val network=optimizer.prepare(g,baseline)
            val result=optimizer.find(network,measurements(network) { ratios.getValue(it.edgeId) })
            val possible=mutableListOf<Pair<Double,Double>>()
            fun enumerate(node: Long, seconds: Double, exposed: Double) {
                if(seconds>baseline.durationSeconds*1.3+1e-8) return
                if(node==6L) { possible.add(exposed to seconds); return }
                edges.filter { it.from==node }.forEach { edge ->
                    val time=WalkingTimeModel.duration(edge.distanceMeters,edge.kind)
                    enumerate(edge.to,seconds+time,exposed+time*(1-ratios.getValue(edge.id)))
                }
            }
            enumerate(1,0.0,0.0)
            val expected=possible.minWith(compareBy<Pair<Double,Double>> { it.first }.thenBy { it.second })
            val exposed=result.route.coordinates.zipWithNext().sumOf { (from,to) ->
                val edge=edges.single { nodes.getValue(it.from)==from && nodes.getValue(it.to)==to }
                WalkingTimeModel.duration(edge.distanceMeters,edge.kind)*(1-ratios.getValue(edge.id))
            }
            assertEquals(expected.first,exposed,1e-8,"seed=$seed")
            assertEquals(expected.second,result.route.durationSeconds,1e-8,"seed=$seed")
        }
    }
}
