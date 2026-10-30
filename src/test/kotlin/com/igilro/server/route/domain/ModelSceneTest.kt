package com.igilro.server.route.domain

import com.igilro.server.route.domain.prediction.*
import com.igilro.server.route.domain.shade.*
import org.junit.jupiter.api.Test
import kotlin.math.floor
import kotlin.test.*

class ModelSceneTest {
    private fun ring(x: Double,y: Double,w: Double,h: Double)=listOf(Point2(x,y),Point2(x+w,y),Point2(x+w,y+h),Point2(x,y+h),Point2(x,y))
    @Test fun `ray uses separate receiver and foundation heights`() {
        val prism=ScenePrism("model",20.0,0.0,listOf(listOf(ring(10.0,-2.0,10.0,4.0))))
        assertTrue(prism.blocks(Point2(0.0,0.0),0.0,Point2(1.0,0.0),1.0))
        assertFalse(prism.blocks(Point2(0.0,0.0),15.0,Point2(1.0,0.0),1.0))
        assertFalse(prism.blocks(Point2(0.0,0.0),20.0,Point2(1.0,0.0),1.0))
        assertFalse(prism.blocks(Point2(0.0,0.0),0.0,Point2(-1.0,0.0),1.0))
        assertTrue(ScenePrism("raised",20.0,10.0,prism.polygons).blocks(Point2(0.0,0.0),15.0,Point2(1.0,0.0),1.0))
    }
    @Test fun `courtyard is preserved without filling a convex hull`() {
        val prism=ScenePrism("courtyard",20.0,0.0,listOf(listOf(ring(-10.0,-10.0,20.0,20.0),ring(-5.0,-5.0,10.0,10.0))))
        assertFalse(prism.blocks(Point2(0.0,0.0),19.0,Point2(1.0,0.0),1.0))
        assertTrue(prism.blocks(Point2(0.0,0.0),0.0,Point2(1.0,0.0),1.0))
    }
    @Test fun `local projection has a stable inverse and oriented sunlight basis`() {
        val p=SceneProjection(Coordinate(127.0373317,37.548162),listOf(listOf(88334.01983138184,-434.717469940756),listOf(545.9901825952395,110944.48885554165),listOf(959130.8800316878,1949970.1294216882)))
        val coordinate=Coordinate(127.038,37.5485)
        val inverse=p.inverse(p.project(coordinate))
        assertEquals(coordinate.longitude,inverse.longitude,1e-10);assertEquals(coordinate.latitude,inverse.latitude,1e-10)
        val east=p.sunDirection(coordinate,90.0);assertTrue(east.x>.99 && east.y<0)
        val north=p.sunDirection(coordinate,0.0);assertTrue(north.y>.99 && north.x>0)
    }
    private val point=Coordinate(127.0374,37.5483)
    private val size=36.0/(1 shl 15)
    private val tx=floor((point.longitude+180)/size).toInt()
    private val ty=floor((point.latitude+90)/size).toInt()
    @Test fun `terrain grid matches SDK north row and triangular interpolation`() {
        val values=List(4225) { 10.0+it/65+(it%65)/10.0 }
        val t=TerrainTile(15,tx,ty,values);val west=-180+tx*size;val south=-90+ty*size
        assertEquals(10.0,t.heightAt(Coordinate(west,south+size)),1e-6)
        assertEquals(74.0,t.heightAt(Coordinate(west,south)),1e-6)
        assertFailsWith<IllegalArgumentException> { TerrainTile(15,tx,ty,List(4225) { 0.0 }) }
        assertFailsWith<IllegalArgumentException> { TerrainTile(15,tx,ty,List(4225) { Double.NaN }) }
    }
    @Test fun `same prism can shade a low path and miss an elevated path`() {
        val p=SceneProjection(point,listOf(listOf(88000.0,0.0),listOf(0.0,111000.0),listOf(0.0,0.0)))
        val prism=ScenePrism("model",20.0,10.0,listOf(listOf(ring(10.0,-5.0,10.0,10.0))))
        val paths=listOf(listOf(p.inverse(Point2(0.0,-2.0)),p.inverse(Point2(0.0,2.0))))
        fun ratio(height: Double)=ModelScene(p,listOf(TerrainTile(15,tx,ty,List(4225) { height })),listOf(prism))
            .measure(paths,point,SolarPosition(45.0,90.0),"model-scene","fixture").single().evaluation.ratio
        assertEquals(1.0,ratio(10.0));assertEquals(0.0,ratio(35.0))
    }
    @Test fun `missing terrain fails instead of inventing zero elevation`() {
        val p=SceneProjection(point,listOf(listOf(88000.0,0.0),listOf(0.0,111000.0),listOf(0.0,0.0)))
        val scene=ModelScene(p,listOf(TerrainTile(15,tx+2,ty,List(4225) { 10.0 })),listOf(ScenePrism("m",10.0,0.0,listOf(listOf(ring(1.0,1.0,2.0,2.0))))))
        assertEquals(RouteFailureReason.PREDICTION_DATA_UNAVAILABLE,assertFailsWith<RouteFailure> { scene.elevation(point) }.reason)
    }
    @Test fun `shade sections partition the route and reproduce its aggregate ratio`() {
        val p=SceneProjection(point,listOf(listOf(88000.0,0.0),listOf(0.0,111000.0),listOf(0.0,0.0)))
        val scene=ModelScene(p,listOf(TerrainTile(15,tx,ty,List(4225) { 10.0 })),
            listOf(ScenePrism("model",20.0,10.0,listOf(listOf(ring(10.0,-2.0,10.0,4.0))))))
        val path=listOf(p.inverse(Point2(0.0,-5.0)),p.inverse(Point2(0.0,0.0)),p.inverse(Point2(0.0,5.0)))
        val trace=scene.trace(path,point,SolarPosition(45.0,90.0),"fixture","fixture")
        assertEquals(listOf(ModeledShadeState.SUNLIGHT,ModeledShadeState.SHADE,ModeledShadeState.SUNLIGHT),trace.sections.map { it.state })
        assertEquals(path.first(),trace.sections.first().coordinates.first())
        assertEquals(path.last(),trace.sections.last().coordinates.last())
        assertTrue(trace.sections.any { path[1] in it.coordinates })
        assertEquals(0.0,trace.sections.first().fromDistanceMeters)
        trace.sections.zipWithNext().forEach { (a,b) ->
            assertEquals(a.toDistanceMeters,b.fromDistanceMeters)
            assertEquals(a.coordinates.last(),b.coordinates.first())
        }
        val length=path.zipWithNext().sumOf { (a,b)->a.distanceTo(b) }
        assertEquals(length,trace.sections.sumOf { it.distanceMeters },1e-6)
        assertEquals(trace.measurement.evaluation.ratio!!,
            trace.sections.filter { it.state==ModeledShadeState.SHADE }.sumOf { it.distanceMeters }/length,1e-9)
        assertEquals(trace.measurement.evaluation.ratio,scene.measure(listOf(path),point,SolarPosition(45.0,90.0),"fixture","fixture").single().evaluation.ratio)
        val reverse=scene.trace(path.reversed(),point,SolarPosition(45.0,90.0),"fixture","fixture")
        assertEquals(trace.measurement.evaluation.ratio!!,reverse.measurement.evaluation.ratio!!,1e-9)
    }
    @Test fun `quadratic projection inverse and derivative remain stable across park sized scene`() {
        val p=SceneProjection(point,listOf(listOf(88000.0,-434.0),listOf(546.0,111000.0),listOf(959000.0,1949000.0),
            listOf(100.0,200.0),listOf(-1200.0,300.0),listOf(5.0,8.0)))
        for(dx in listOf(-.01,0.0,.01)) for(dy in listOf(-.01,0.0,.01)) {
            val c=Coordinate(point.longitude+dx,point.latitude+dy);val inverse=p.inverse(p.project(c))
            assertEquals(c.longitude,inverse.longitude,1e-10);assertEquals(c.latitude,inverse.latitude,1e-10)
            assertTrue(p.sunDirection(c,90.0).x>.99)
        }
    }
    @Test fun `shadow index retains long casting buildings and prunes unrelated buildings`() {
        val p=SceneProjection(point,listOf(listOf(88000.0,0.0),listOf(0.0,111000.0),listOf(0.0,0.0)))
        val buildings=(1..40).map { ScenePrism("far$it",20.0,10.0,listOf(listOf(ring(it*100.0,500.0,10.0,10.0)))) }+
            ScenePrism("caster",50.0,10.0,listOf(listOf(ring(100.0,-2.0,10.0,4.0))))
        val scene=ModelScene(p,listOf(TerrainTile(15,tx,ty,List(4225) { 10.0 })),buildings)
        val path=listOf(p.inverse(Point2(0.0,-1.0)),p.inverse(Point2(0.0,1.0)))
        assertEquals(1.0,scene.trace(path,point,SolarPosition(10.0,90.0),"fixture","fixture").measurement.evaluation.ratio)
        assertEquals(0.0,scene.trace(path,point,SolarPosition(45.0,90.0),"fixture","fixture").measurement.evaluation.ratio)
    }
    @Test fun `connectivity uses original node identities even when disconnected coordinates coincide`() {
        val nodes=mapOf(1L to point,2L to Coordinate(127.0375,37.5483),3L to Coordinate(127.0375,37.5483),4L to Coordinate(127.0376,37.5483))
        val graph=WalkingGraph("fixture","fixture",Bounds(127.03,37.54,127.04,37.55),nodes,listOf(WalkingEdge(1,1,2,10.0),WalkingEdge(2,3,4,10.0)))
        assertEquals(listOf(setOf(1L,2L),setOf(3L,4L)),graph.connectedComponents)
    }
}
