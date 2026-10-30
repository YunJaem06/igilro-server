package com.igilro.server.route.domain.prediction

import com.igilro.server.route.domain.*
import com.igilro.server.route.domain.shade.*
import kotlin.math.*

data class Point2(val x: Double, val y: Double)

/** Locally fitted EPSG:5179 projection, independently checked by the preparer. */
class SceneProjection(val origin: Coordinate, val coefficients: List<List<Double>>) {
    init { require(coefficients.size in setOf(3,6) && coefficients.all { it.size == 2 && it.all(Double::isFinite) }) }
    private val determinant = coefficients[0][0]*coefficients[1][1]-coefficients[1][0]*coefficients[0][1]
    init { require(abs(determinant) > 1e6) }
    fun project(c: Coordinate): Point2 {
        val a=c.longitude-origin.longitude; val b=c.latitude-origin.latitude
        val terms=listOf(a,b,1.0,a*a,a*b,b*b)
        return Point2(coefficients.indices.sumOf { terms[it]*coefficients[it][0] },
            coefficients.indices.sumOf { terms[it]*coefficients[it][1] })
    }
    fun inverse(p: Point2): Coordinate {
        val x=p.x-coefficients[2][0]; val y=p.y-coefficients[2][1]
        var result=Coordinate(origin.longitude+(x*coefficients[1][1]-y*coefficients[1][0])/determinant,
            origin.latitude+(y*coefficients[0][0]-x*coefficients[0][1])/determinant)
        repeat(5) {
            val q=project(result);val j=jacobian(result);val det=j[0]*j[3]-j[1]*j[2]
            require(abs(det)>1e6)
            result=Coordinate(result.longitude+((p.x-q.x)*j[3]-(p.y-q.y)*j[1])/det,
                result.latitude+((p.y-q.y)*j[0]-(p.x-q.x)*j[2])/det)
        }
        return result
    }
    private fun jacobian(at: Coordinate): List<Double> {
        val a=at.longitude-origin.longitude;val b=at.latitude-origin.latitude
        fun c(i: Int,k: Int)=coefficients.getOrNull(i)?.get(k) ?: 0.0
        return listOf(c(0,0)+2*a*c(3,0)+b*c(4,0),c(1,0)+a*c(4,0)+2*b*c(5,0),
            c(0,1)+2*a*c(3,1)+b*c(4,1),c(1,1)+a*c(4,1)+2*b*c(5,1))
    }
    fun sunDirection(at: Coordinate, azimuthDegrees: Double): Point2 {
        val radians=Math.toRadians(azimuthDegrees)
        val latitude=Math.toRadians(at.latitude)
        val north=cos(radians)/(111132.92-559.82*cos(2*latitude)+1.175*cos(4*latitude)-.0023*cos(6*latitude))
        val east=sin(radians)/(111412.84*cos(latitude)-93.5*cos(3*latitude)+.118*cos(5*latitude))
        val j=jacobian(at)
        val x=east*j[0]+north*j[1]
        val y=east*j[2]+north*j[3]
        val length=hypot(x,y)
        return Point2(x/length,y/length)
    }
}

data class TerrainTile(val level: Int, val x: Int, val y: Int, val values: List<Double>) {
    init {
        require(level==15 && x in 0 until (10 shl level) && y in 0 until (5 shl level))
        require(values.size==4225 && values.all { it.isFinite() && it in -500.0..9000.0 } && values.any { it!=0.0 })
    }
    fun heightAt(c: Coordinate): Double {
        val size=36.0/(1 shl level)
        val u=((c.longitude-(-180+x*size))/size*64).coerceIn(0.0,64.0)
        val v=((c.latitude-(-90+y*size))/size*64).coerceIn(0.0,64.0)
        val ix=min(63,u.toInt());val iy=min(63,v.toInt());val dx=u-ix;val dy=v-iy
        val sw=values[(64-iy)*65+ix];val se=values[(64-iy)*65+ix+1]
        val nw=values[(63-iy)*65+ix];val ne=values[(63-iy)*65+ix+1]
        return if(dy<dx) sw+dx*(se-sw)+dy*(ne-se) else sw+dx*(ne-nw)+dy*(nw-sw)
    }
}

class ScenePrism(val id: String, val height: Double, val base: Double, val polygons: List<List<List<Point2>>>) {
    init {
        require(id.isNotBlank() && height.isFinite() && height>0 && height<=500 && base.isFinite())
        require(polygons.isNotEmpty() && polygons.all { p -> p.isNotEmpty() && p.all { r -> r.size>=4 && r.first()==r.last() && r.all { it.x.isFinite() && it.y.isFinite() } } })
    }
    private val vertices=polygons.flatten().flatten()
    private val minX=vertices.minOf { it.x };private val maxX=vertices.maxOf { it.x }
    private val minY=vertices.minOf { it.y };private val maxY=vertices.maxOf { it.y }
    internal fun shadowBounds(direction: Point2,slope: Double,minimumElevation: Double): List<Double> {
        val reach=max(0.0,(base+height-minimumElevation)/slope)
        val dx=-direction.x*reach;val dy=-direction.y*reach
        return listOf(minX+min(0.0,dx),minY+min(0.0,dy),maxX+max(0.0,dx),maxY+max(0.0,dy))
    }
    private fun insideRing(p: Point2, ring: List<Point2>): Boolean {
        var inside=false
        for(i in 0 until ring.lastIndex) {
            val a=ring[i];val b=ring[i+1]
            if((a.y>p.y)!=(b.y>p.y) && p.x<(b.x-a.x)*(p.y-a.y)/(b.y-a.y)+a.x) inside=!inside
        }
        return inside
    }
    private fun contains(p: Point2)=polygons.any { insideRing(p,it.first()) && it.drop(1).none { h -> insideRing(p,h) } }
    fun blocks(p: Point2, receiverHeight: Double, direction: Point2, slope: Double): Boolean {
        if(receiverHeight>=base+height) return false
        val enter=max(0.0,(base-receiverHeight)/slope);val leave=(base+height-receiverHeight)/slope
        val a=Point2(p.x+direction.x*enter,p.y+direction.y*enter)
        val b=Point2(p.x+direction.x*leave,p.y+direction.y*leave)
        if(max(a.x,b.x)<minX || min(a.x,b.x)>maxX || max(a.y,b.y)<minY || min(a.y,b.y)>maxY) return false
        if(contains(a) || contains(b)) return true
        fun cross(x: Point2,y: Point2)=x.x*y.y-x.y*y.x
        val ray=Point2(b.x-a.x,b.y-a.y)
        return polygons.any { poly -> poly.any { ring -> (0 until ring.lastIndex).any { i ->
            val c=ring[i];val d=ring[i+1];val segment=Point2(d.x-c.x,d.y-c.y)
            val den=cross(ray,segment)
            if(abs(den)<1e-12) false else {
                val delta=Point2(c.x-a.x,c.y-a.y)
                cross(delta,segment)/den in 0.0..1.0 && cross(delta,ray)/den in 0.0..1.0
            }
        } } }
    }
}

enum class ModeledShadeState { SHADE, SUNLIGHT }
data class ModeledShadeSection(val state: ModeledShadeState,val coordinates: List<Coordinate>,
    val fromDistanceMeters: Double,val toDistanceMeters: Double) {
    val distanceMeters: Double get()=toDistanceMeters-fromDistanceMeters
}
data class ModeledPathPrediction(val measurement: ShadePathMeasurement,val sections: List<ModeledShadeSection>)

/** Per-sun bounding volume tree. Queries remain exact ray/prism tests after pruning. */
private class ShadowIndex(entries: List<Pair<ScenePrism,List<Double>>>,depth: Int=0) {
    private val bounds=listOf(entries.minOf { it.second[0] },entries.minOf { it.second[1] },
        entries.maxOf { it.second[2] },entries.maxOf { it.second[3] })
    private val leaf=if(entries.size<=8) entries else emptyList()
    private val children=if(leaf.isNotEmpty()) emptyList() else {
        val axis=depth%2;val sorted=entries.sortedBy { it.second[axis]+it.second[axis+2] };val half=sorted.size/2
        listOf(ShadowIndex(sorted.take(half),depth+1),ShadowIndex(sorted.drop(half),depth+1))
    }
    fun blocks(p: Point2,z: Double,direction: Point2,slope: Double): Boolean {
        if(p.x<bounds[0] || p.y<bounds[1] || p.x>bounds[2] || p.y>bounds[3]) return false
        return leaf.any { (prism,b) -> p.x>=b[0] && p.y>=b[1] && p.x<=b[2] && p.y<=b[3] && prism.blocks(p,z,direction,slope) } ||
            children.any { it.blocks(p,z,direction,slope) }
    }
}

class ModelScene(val projection: SceneProjection, terrain: List<TerrainTile>, val buildings: List<ScenePrism>) {
    private val tiles=terrain.associateBy { it.x to it.y }
    private val minimumElevation=terrain.minOf { it.values.min() }
    init { require(terrain.isNotEmpty() && tiles.size==terrain.size && buildings.isNotEmpty()) }
    fun elevation(c: Coordinate): Double {
        val size=36.0/(1 shl 15)
        val tile=tiles[floor((c.longitude+180)/size).toInt() to floor((c.latitude+90)/size).toInt()]
            ?: throw RouteFailure(RouteFailureReason.PREDICTION_DATA_UNAVAILABLE)
        return tile.heightAt(c)
    }
    fun measure(paths: List<List<Coordinate>>, center: Coordinate, sun: SolarPosition, version: String, attribution: String): List<ShadePathMeasurement> {
        return evaluate(paths,center,sun,version,attribution,false).map { it.measurement }
    }
    fun trace(path: List<Coordinate>,center: Coordinate,sun: SolarPosition,version: String,attribution: String)=
        evaluate(listOf(path),center,sun,version,attribution,true).single()
    private fun evaluate(paths: List<List<Coordinate>>,center: Coordinate,sun: SolarPosition,version: String,attribution: String,
        includeSections: Boolean): List<ModeledPathPrediction> {
        val deadline=System.nanoTime()+8_000_000_000L;var totalSamples=0
        val direction=projection.sunDirection(center,sun.azimuthDegrees)
        val slope=tan(Math.toRadians(sun.altitudeDegrees))
        require(sun.altitudeDegrees in 5.0..90.0)
        val index=ShadowIndex(buildings.map { it to it.shadowBounds(direction,slope,minimumElevation) })
        return paths.map { path ->
            var total=0.0;var shaded=0.0
            val sections=mutableListOf<ModeledShadeSection>()
            var state: ModeledShadeState?=null;var from=0.0;var points=mutableListOf<Coordinate>()
            path.zipWithNext().forEach { (start,end) ->
                val a=projection.project(start);val b=projection.project(end);val length=hypot(b.x-a.x,b.y-a.y)
                if(length>1e-8) {
                    val count=ceil(length/SAMPLE_STEP_METERS).toInt()
                    totalSamples+=count
                    if(totalSamples>250_000 || System.nanoTime()>deadline) throw RouteFailure(RouteFailureReason.SEARCH_LIMIT_EXCEEDED)
                    val meters=start.distanceTo(end)/count
                    fun coordinate(f: Double)=when(f) {
                        0.0 -> start
                        1.0 -> end
                        else -> Coordinate(start.longitude+(end.longitude-start.longitude)*f,start.latitude+(end.latitude-start.latitude)*f)
                    }
                    for(i in 0 until count) {
                        if(i%128==0 && System.nanoTime()>deadline) throw RouteFailure(RouteFailureReason.SEARCH_LIMIT_EXCEEDED)
                        val fraction=(i+.5)/count;val p=Point2(a.x+(b.x-a.x)*fraction,a.y+(b.y-a.y)*fraction)
                        val z=elevation(coordinate(fraction))
                        val next=if(index.blocks(p,z,direction,slope)) ModeledShadeState.SHADE else ModeledShadeState.SUNLIGHT
                        if(includeSections) {
                            val left=coordinate(i.toDouble()/count);val right=coordinate((i+1.0)/count)
                            val changed=state!=next
                            if(changed) {
                                if(state!=null) sections.add(ModeledShadeSection(state,points.toList(),from,total))
                                state=next;from=total;points=mutableListOf(left)
                            }
                            // Keep state boundaries and every original route corner;
                            // collinear sampling points need not inflate the map payload.
                            if(!changed && i>0) points[points.lastIndex]=right else points.add(right)
                        }
                        if(next==ModeledShadeState.SHADE) shaded+=meters
                        total+=meters
                    }
                }
            }
            if(total<=0) throw RouteFailure(RouteFailureReason.PREDICTION_DATA_UNAVAILABLE)
            if(state!=null) sections.add(ModeledShadeSection(state,points.toList(),from,total))
            // Internal optimizer adapter only. The prediction API never exposes AVAILABLE.
            ModeledPathPrediction(ShadePathMeasurement(ShadeEvaluation(ShadeStatus.AVAILABLE,(shaded/total).coerceIn(0.0,1.0),1.0,version,attribution,sun.altitudeDegrees),total),sections)
        }
    }
    companion object { const val SAMPLE_STEP_METERS=0.5;const val MODEL_VERSION="public-3d-prism-dem-v2-step0.5m" }
}

data class PredictionAccessPoint(val id: String,val name: String,val coordinate: Coordinate)
data class PredictionSceneInfo(val dataVersion: String,val graph: WalkingGraph,val attribution: String,
    val limitations: List<String>,val buildingCount: Int,val terrainTileCount: Int,val projectionErrorMeters: Double,
    val omittedBuildingCount: Int=0,val sceneBufferMetersApprox: Double=0.0,val areaName: String="Public-model pilot",
    val accessPoints: List<PredictionAccessPoint> = emptyList(),val componentPolicy: String="ALL_SOURCE_COMPONENTS",
    val sourceComponentCount: Int=graph.connectedComponents.size,val excludedDisconnectedNodeCount: Int=0)
