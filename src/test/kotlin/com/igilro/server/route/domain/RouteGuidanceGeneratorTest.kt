package com.igilro.server.route.domain

import org.junit.jupiter.api.Test
import kotlin.test.*

class RouteGuidanceGeneratorTest {
    private fun p(x: Double,y: Double)=Coordinate(127.03+x/88000,37.54+y/111000)
    private val bounds=Bounds(127.02,37.53,127.05,37.56)
    private fun graph(nodes: Map<Long,Coordinate>,pairs: List<Pair<Long,Long>>,metadata: Map<Long,EdgeGuidanceMetadata> = emptyMap(),
        crossings: Map<Long,NodeGuidanceMetadata> = emptyMap(),mutate: (WalkingEdge)->WalkingEdge = { it }) =
        WalkingGraph("fixture","fixture",bounds,nodes,pairs.mapIndexed { i,(a,b) ->
            mutate(WalkingEdge(i+1L,a,b,nodes.getValue(a).distanceTo(nodes.getValue(b))))
        },crossings,metadata,if(metadata.isNotEmpty() || crossings.isNotEmpty()) GuidanceMetadataVersion.OSM_V1 else null)
    private fun route(g: WalkingGraph,a: Coordinate,b: Coordinate)=DijkstraRoutePlanner().find(g,a,b,.01)
    private fun guidance(g: WalkingGraph,a: Long,b: Long)=RouteGuidanceGenerator().generate(g,route(g,g.nodes.getValue(a),g.nodes.getValue(b)))

    @Test fun `a curved path without a choice produces only departure and arrival`() {
        val g=graph(mapOf(1L to p(-30.0,0.0),2L to p(0.0,0.0),3L to p(0.0,30.0)),listOf(1L to 2L,2L to 3L))
        assertEquals(listOf(GuidanceManeuver.DEPART,GuidanceManeuver.ARRIVE),guidance(g,1,3).steps.map { it.type })
    }
    @Test fun `real branch directions reverse correctly and preserve named outgoing way`() {
        val nodes=mapOf(1L to p(-30.0,0.0),2L to p(0.0,0.0),3L to p(0.0,-30.0),4L to p(30.0,0.0))
        val g=graph(nodes,listOf(1L to 2L,2L to 3L,2L to 4L),mapOf(2L to EdgeGuidanceMetadata(20,"서울숲길")))
        val step=guidance(g,1,3).steps[1]
        assertEquals(GuidanceManeuver.TURN_RIGHT,step.type);assertEquals("서울숲길",step.streetName)
        assertEquals(nodes[2],step.coordinate);assertEquals(1,step.geometryIndex)
        assertEquals(GuidanceManeuver.TURN_LEFT,guidance(g,3,1).steps[1].type)
    }
    @Test fun `shallow forks distinguish left from right while a straight junction stays straight`() {
        val fork=graph(mapOf(1L to p(0.0,-30.0),2L to p(0.0,0.0),3L to p(-10.0,30.0),4L to p(10.0,30.0)),
            listOf(1L to 2L,2L to 3L,2L to 4L))
        assertEquals(GuidanceManeuver.KEEP_LEFT,guidance(fork,1,3).steps[1].type)
        assertEquals(GuidanceManeuver.KEEP_RIGHT,guidance(fork,1,4).steps[1].type)
        val straight=graph(mapOf(1L to p(0.0,-30.0),2L to p(0.0,0.0),3L to p(0.0,30.0),4L to p(30.0,0.0)),
            listOf(1L to 2L,2L to 3L,2L to 4L))
        assertEquals(GuidanceManeuver.CONTINUE,guidance(straight,1,3).steps[1].type)
    }
    @Test fun `incoming only road is not a legal alternative and parallel duplicates are one option`() {
        val nodes=mapOf(1L to p(-30.0,0.0),2L to p(0.0,0.0),3L to p(0.0,30.0),4L to p(30.0,0.0))
        val g=graph(nodes,listOf(1L to 2L,2L to 3L,4L to 2L,2L to 3L),mutate={ if(it.id==3L) it.copy(backwardAllowed=false) else it })
        assertEquals(2,guidance(g,1,3).steps.size)
    }
    @Test fun `tiny geometry offsets at a real fork do not turn a right turn into a U turn`() {
        val g=graph(mapOf(1L to p(-30.0,0.0),2L to p(0.0,0.0),3L to p(-.2,0.0),4L to p(0.0,-30.0),5L to p(30.0,0.0)),
            listOf(1L to 2L,2L to 3L,3L to 4L,2L to 5L))
        val steps=guidance(g,1,4).steps
        assertEquals(3,steps.size);assertEquals(GuidanceManeuver.TURN_RIGHT,steps[1].type)
    }
    @Test fun `crossing existence does not invent signals and explicit signals remain source metadata`() {
        val nodes=mapOf(1L to p(-30.0,0.0),2L to p(0.0,0.0),3L to p(30.0,0.0))
        for(signals in listOf(null,false,true)) {
            val g=graph(nodes,listOf(1L to 2L,2L to 3L),crossings=mapOf(2L to NodeGuidanceMetadata(CrossingMetadata(signals))))
            val step=guidance(g,1,3).steps[1]
            assertEquals(GuidanceManeuver.CROSSING,step.type);assertEquals(signals,step.crossing!!.trafficSignals)
            assertEquals(signals==true,"신호가 설치된" in step.instruction)
            assertFalse("초록불" in step.instruction)
        }
    }
    @Test fun `one crossing way with many geometry nodes produces a single entry instruction`() {
        val nodes=(1L..5L).associateWith { p(it*10.0,0.0) }
        val tags=mapOf(2L to EdgeGuidanceMetadata(100,crossing=CrossingMetadata(true)),3L to EdgeGuidanceMetadata(100,crossing=CrossingMetadata(true)))
        val g=graph(nodes,listOf(1L to 2L,2L to 3L,3L to 4L,4L to 5L),tags,
            mapOf(3L to NodeGuidanceMetadata(CrossingMetadata(true)),4L to NodeGuidanceMetadata(CrossingMetadata(true))))
        val crossings=guidance(g,1,5).steps.filter { it.crossing!=null }
        assertEquals(1,crossings.size);assertEquals(nodes[2],crossings.single().coordinate)
        val inside=RouteGuidanceGenerator().generate(g,route(g,p(25.0,0.0),p(35.0,0.0)))
        assertEquals(GuidanceManeuver.DEPART,inside.steps.first().type)
        assertNotNull(inside.steps.first().crossing);assertEquals(2,inside.steps.size)
    }
    @Test fun `a turn at an explicitly mapped crossing retains both pieces of guidance`() {
        val g=graph(mapOf(1L to p(-30.0,0.0),2L to p(0.0,0.0),3L to p(0.0,-30.0),4L to p(30.0,0.0)),
            listOf(1L to 2L,2L to 3L,2L to 4L),crossings=mapOf(2L to NodeGuidanceMetadata(CrossingMetadata())))
        val step=guidance(g,1,3).steps[1]
        assertEquals(GuidanceManeuver.TURN_RIGHT,step.type);assertNotNull(step.crossing)
        assertTrue("횡단" in step.instruction)
    }
    @Test fun `partial reverse edges use snapped endpoints and raw route cost without adding pin access distance`() {
        val g=graph(mapOf(1L to p(0.0,0.0),2L to p(100.0,0.0)),listOf(1L to 2L))
        val r=route(g,p(80.0,0.0),p(20.0,0.0));val before=r.copy()
        val guide=RouteGuidanceGenerator().generate(g,r)
        assertEquals(before,r);assertTrue(r.traversals.single().fromFraction>r.traversals.single().toFraction)
        assertEquals(r.origin.coordinate,guide.steps.first().coordinate);assertEquals(r.destination.coordinate,guide.steps.last().coordinate)
        assertEquals(0.0,guide.steps.first().distanceFromStartMeters)
        assertEquals(r.distanceMeters,guide.steps.last().distanceFromStartMeters,1e-6)
        assertEquals(r.distanceMeters,guide.steps.sumOf { it.distanceToNextStepMeters },1e-6)
        assertEquals(r.durationSeconds,guide.steps.sumOf { it.durationToNextStepSeconds },1e-6)
        assertEquals(0.0,guide.steps.last().distanceToNextStepMeters)
        assertEquals("TOPOLOGY_ONLY",guide.metadataStatus)
    }
    @Test fun `coincident coordinates with distinct source nodes do not manufacture a junction`() {
        val g=graph(mapOf(1L to p(-30.0,0.0),2L to p(0.0,0.0),3L to p(0.0,30.0),4L to p(0.0,0.0),5L to p(30.0,0.0)),
            listOf(1L to 2L,2L to 3L,4L to 5L))
        assertEquals(2,guidance(g,1,3).steps.size)
    }
    @Test fun `stairs are identified without claiming ascent or descent`() {
        val g=graph(mapOf(1L to p(0.0,0.0),2L to p(30.0,0.0),3L to p(60.0,0.0)),listOf(1L to 2L,2L to 3L),
            mutate={if(it.id==2L) it.copy(kind=EdgeKind.STEPS) else it})
        assertEquals(GuidanceManeuver.STAIRS,guidance(g,1,3).steps[1].type)
    }
    @Test fun `routes without an edge trace report unavailable guidance without invented turns`() {
        val g=graph(mapOf(1L to p(0.0,0.0),2L to p(30.0,0.0)),listOf(1L to 2L))
        val guide=RouteGuidanceGenerator().generate(g,route(g,g.nodes.getValue(1),g.nodes.getValue(2)).copy(traversals=emptyList()))
        assertEquals("UNAVAILABLE",guide.status);assertTrue(guide.steps.isEmpty())
    }
}
