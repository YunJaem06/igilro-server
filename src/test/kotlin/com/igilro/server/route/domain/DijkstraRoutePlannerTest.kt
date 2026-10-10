package com.igilro.server.route.domain

import org.junit.jupiter.api.Test
import kotlin.test.*

class DijkstraRoutePlannerTest {
    private val planner = DijkstraRoutePlanner()
    private val a = Coordinate(127.03, 37.54)
    private val b = Coordinate(127.032, 37.54)
    private val c = Coordinate(127.031, 37.541)
    private val bounds = Bounds(127.02, 37.53, 127.05, 37.56)

    private fun graph(edges: List<WalkingEdge>, nodes: Map<Long, Coordinate> = mapOf(1L to a, 2L to b, 3L to c)) =
        WalkingGraph("fixture", "test", bounds, nodes, edges)

    @Test
    fun `fastest uses time cost and can prefer longer flat road over steps`() {
        val g = graph(listOf(WalkingEdge(1, 1, 2, 200.0, EdgeKind.STEPS), WalkingEdge(2, 1, 3, 130.0), WalkingEdge(3, 3, 2, 130.0)))
        val route = planner.find(g, a, b, 50.0)
        assertEquals(listOf(a, c, b), route.coordinates)
        assertEquals(260.0, route.distanceMeters)
        assertEquals(260.0 / 1.4, route.durationSeconds, 0.001)
    }

    @Test
    fun `two mid-edge snaps use only the section between them`() {
        val g = graph(listOf(WalkingEdge(1, 1, 2, 200.0)))
        val route = planner.find(g, Coordinate(127.0305, 37.54), Coordinate(127.0315, 37.54), 50.0)
        assertEquals(100.0, route.distanceMeters, 0.001)
        assertEquals(2, route.coordinates.size)
    }

    @Test
    fun `explicit foot one way prevents a reverse route on the same edge`() {
        val g = graph(listOf(WalkingEdge(1, 1, 2, 200.0, backwardAllowed = false)))
        assertEquals(RouteFailureReason.NOT_FOUND, assertFailsWith<RouteFailure> {
            planner.find(g, Coordinate(127.0315, 37.54), Coordinate(127.0305, 37.54), 50.0)
        }.reason)
        assertTrue(planner.find(g, a, b, 50.0).distanceMeters > 0)
    }

    @Test
    fun `endpoint of a one way edge can depart along another incident way`() {
        val g = graph(listOf(WalkingEdge(1, 1, 2, 200.0, backwardAllowed = false), WalkingEdge(2, 1, 3, 130.0)))
        assertEquals(listOf(a, c), planner.find(g, a, c, 50.0).coordinates)
    }

    @Test
    fun `different node IDs at the same coordinates do not create a junction`() {
        val d = Coordinate(127.033, 37.54)
        val g = graph(listOf(WalkingEdge(1, 1, 2, 200.0), WalkingEdge(2, 4, 5, 100.0)), mapOf(1L to a, 2L to b, 4L to b, 5L to d))
        assertEquals(RouteFailureReason.NOT_FOUND, assertFailsWith<RouteFailure> { planner.find(g, a, d, 50.0) }.reason)
    }

    @Test
    fun `coordinates beyond snapping limit return no route`() {
        val g = graph(listOf(WalkingEdge(1, 1, 2, 200.0)))
        assertEquals(RouteFailureReason.NOT_FOUND, assertFailsWith<RouteFailure> { planner.find(g, c, b, 50.0) }.reason)
    }

    @Test
    fun `nearby connected snap is considered when the closest segment is isolated`() {
        val d = Coordinate(127.0318, 37.5401)
        val e = Coordinate(127.0322, 37.5401)
        val g = graph(listOf(WalkingEdge(1, 1, 2, 200.0), WalkingEdge(2, 4, 5, 40.0)), mapOf(1L to a, 2L to b, 4L to d, 5L to e))
        val route = planner.find(g, a, Coordinate(127.032,37.5401), 50.0)
        assertEquals(b, route.coordinates.last())
        assertTrue(route.destination.distanceMeters in 10.0..12.0)
        assertEquals(200.0, route.distanceMeters, 0.001)
    }

    @Test
    fun `distinct requests snapping to the same point are rejected`() {
        val g = graph(listOf(WalkingEdge(1, 1, 2, 200.0)))
        assertEquals(RouteFailureReason.INVALID_QUERY, assertFailsWith<RouteFailure> {
            planner.find(g, Coordinate(127.031, 37.54001), Coordinate(127.031, 37.53999), 50.0)
        }.reason)
    }

    @Test
    fun `collapsed nearest positions do not manufacture a journey via a farther parallel road`() {
        val d = Coordinate(127.03,37.5402)
        val e = Coordinate(127.032,37.5402)
        val g = graph(listOf(WalkingEdge(1,1,2,200.0),WalkingEdge(2,4,5,200.0),WalkingEdge(3,1,4,22.0)),
            mapOf(1L to a,2L to b,4L to d,5L to e))
        assertEquals(RouteFailureReason.INVALID_QUERY, assertFailsWith<RouteFailure> {
            planner.find(g,Coordinate(127.031,37.54001),Coordinate(127.031,37.53999),50.0)
        }.reason)
    }

    @Test
    fun `negative costs and invalid coordinates are not admitted`() {
        assertFailsWith<IllegalArgumentException> { WalkingEdge(1, 1, 2, -1.0) }
        assertFailsWith<IllegalArgumentException> { Coordinate(Double.NaN, 37.54) }
        assertFailsWith<IllegalArgumentException> { Coordinate(127.03, 91.0) }
    }
}
