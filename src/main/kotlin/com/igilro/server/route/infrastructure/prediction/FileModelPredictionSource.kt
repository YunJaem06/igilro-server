package com.igilro.server.route.infrastructure.prediction

import com.igilro.server.route.application.ModelPredictionSource
import com.igilro.server.route.domain.*
import com.igilro.server.route.domain.prediction.*
import com.igilro.server.route.domain.shade.*
import com.igilro.server.route.infrastructure.osm.OsmGraphReader
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** One immutable file snapshot, separate from the active direct-height DB. */
class FileModelPredictionSource(file: Path?) : ModelPredictionSource {
    private val loaded: Pair<PredictionSceneInfo,ModelScene>? = file?.let { read(it) }
    override fun current()=loaded?.first
    override fun measure(info: PredictionSceneInfo,paths: List<List<Coordinate>>,center: Coordinate,sun: SolarPosition): List<ShadePathMeasurement> {
        val snapshot=loaded ?: throw RouteFailure(RouteFailureReason.PREDICTION_DATA_UNAVAILABLE)
        if(info.dataVersion!=snapshot.first.dataVersion) throw RouteFailure(RouteFailureReason.PREDICTION_DATA_UNAVAILABLE)
        return snapshot.second.measure(paths,center,sun,info.dataVersion,info.attribution)
    }
    override fun trace(info: PredictionSceneInfo,path: List<Coordinate>,center: Coordinate,sun: SolarPosition): ModeledPathPrediction {
        val snapshot=loaded ?: throw RouteFailure(RouteFailureReason.PREDICTION_DATA_UNAVAILABLE)
        if(info.dataVersion!=snapshot.first.dataVersion) throw RouteFailure(RouteFailureReason.PREDICTION_DATA_UNAVAILABLE)
        return snapshot.second.trace(path,center,sun,info.dataVersion,info.attribution)
    }
    private fun read(file: Path): Pair<PredictionSceneInfo,ModelScene> {
        require(Files.isRegularFile(file) && Files.size(file) in 1..32_000_000) { "Prediction scene must be a file <= 32 MB" }
        val bytes=Files.readAllBytes(file)
        val root=JsonMapper.builder().build().readTree(bytes)
        require(root["schemaVersion"]?.asInt() in setOf(1,2) && text(root,"kind")=="PUBLIC_3D_MODEL_PREDICTION_ONLY")
        require(text(root,"modelVersion") in setOf(ModelScene.MODEL_VERSION,"public-3d-prism-dem-v1-step0.5m") && root["sceneComplete"]?.isBoolean==true && !root["sceneComplete"].asBoolean())
        val source=requireNotNull(root["graph"]);require(text(source,"roadsFile")=="roads.osm")
        val roads=file.toAbsolutePath().parent.resolve("roads.osm")
        require(Files.isRegularFile(roads) && Files.size(roads) in 1..20_000_000)
        require(sha(Files.readAllBytes(roads))==text(source,"roadsSha256")) { "Prediction roads checksum mismatch" }
        val bounds=numbers(requireNotNull(source["bounds"]));require(bounds.size==4)
        val graphBounds=Bounds(bounds[0],bounds[1],bounds[2],bounds[3])
        require(bounds[0]>=124 && bounds[2]<=132 && bounds[1]>=33 && bounds[3]<=39 && bounds[2]-bounds[0]<=.02 && bounds[3]-bounds[1]<=.02)
        val areaName=source["areaName"]?.asString() ?: "Public-model pilot"
        val imported=OsmGraphReader().read(roads,text(source,"areaId"),areaName,graphBounds,"OpenStreetMap")
        require(imported.nodes.size<=20_000 && imported.edges.size<=30_000)
        val sourceGraph=WalkingGraph(imported.dataVersion,imported.areaId,imported.bounds,imported.nodes,imported.edges.map {
            WalkingEdge(it.id,it.from,it.to,imported.nodes.getValue(it.from).distanceTo(imported.nodes.getValue(it.to)),it.kind,it.forwardAllowed,it.backwardAllowed)
        },imported.nodeGuidance,imported.edgeGuidance,imported.guidanceMetadataVersion)
        val componentPolicy=source["componentPolicy"]?.asString() ?: "ALL_SOURCE_COMPONENTS"
        require(componentPolicy in setOf("ALL_SOURCE_COMPONENTS","LARGEST_CONNECTED_COMPONENT"))
        val graph=if(componentPolicy=="LARGEST_CONNECTED_COMPONENT") {
            val included=sourceGraph.connectedComponents.first()
            val includedEdges=sourceGraph.edges.filter { it.from in included && it.to in included }
            val includedIds=includedEdges.map { it.id }.toSet()
            WalkingGraph("osm-prediction-v2-"+sha((imported.dataVersion+"|"+componentPolicy).toByteArray(Charsets.UTF_8)),
                imported.areaId,imported.bounds,sourceGraph.nodes.filterKeys { it in included },
                includedEdges,sourceGraph.nodeGuidance.filterKeys { it in included },sourceGraph.edgeGuidance.filterKeys { it in includedIds },sourceGraph.guidanceMetadataVersion)
        } else sourceGraph
        val p=requireNotNull(root["projection"]);val o=numbers(requireNotNull(p["origin"]));require(o.size==2)
        val error=number(p,"checkedMaxErrorMeters");require(error in 0.0..0.05 && text(p,"targetCrs")=="EPSG:5179")
        val projection=SceneProjection(Coordinate(o[0],o[1]),elements(requireNotNull(p["coefficients"])).map(::numbers))
        val terrainNodes=elements(requireNotNull(root["terrain"]));require(terrainNodes.size in 1..512)
        val terrain=terrainNodes.map {
            val values=if(it["values"]!=null) numbers(it["values"]) else {
                val packed=text(it,"float32leBase64");require(packed.length<=22_536)
                val decoded=Base64.getDecoder().decode(packed);require(decoded.size==16_900)
                val buffer=ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN)
                List(4225) { buffer.float.toDouble() }
            }
            TerrainTile(it["level"].asInt(),it["x"].asInt(),it["y"].asInt(),values)
        }
        val buildingNodes=elements(requireNotNull(root["buildings"]));require(buildingNodes.size in 1..8000)
        var vertices=0
        val buildings=buildingNodes.map { b ->
            require(text(b,"heightSource")=="VWORLD_MODEL_ATTRIBUTE" && text(b,"baseMethod")=="DEM_AT_MODEL_INTERIOR_POINT")
            require(text(b,"sourceUrl").startsWith("https://cdn.vworld.kr/TDServer/services/map4/") && text(b,"sourceSha256").matches(Regex("[0-9a-f]{64}")))
            val polygons=elements(requireNotNull(b["polygons"])).map { polygon -> elements(polygon).map { ring ->
                elements(ring).map { point ->
                    val c=numbers(point);require(c.size==2);vertices++;require(vertices<=1_000_000)
                    projection.project(Coordinate(c[0],c[1]))
                }
            } }
            ScenePrism(text(b,"id"),number(b,"heightMeters"),number(b,"baseElevationMeters"),polygons)
        }
        require(buildings.map { it.id }.toSet().size==buildings.size)
        val limitations=elements(requireNotNull(root["limitations"])).map { require(it.isString);it.asString() }
        require("MODEL_HEIGHTS_ARE_NOT_ACTUAL_HEIGHTS" in limitations && "PARTIAL_PROVIDER_SCENE" in limitations)
        val omitted=elements(requireNotNull(root["omittedModelIds"])).size
        val buffer=number(root,"sceneBufferMetersApprox");require(buffer in 0.0..500.0)
        val accessPoints=root["accessPoints"]?.let(::elements).orEmpty().map {
            val c=numbers(requireNotNull(it["coordinate"]));require(c.size==2)
            PredictionAccessPoint(text(it,"id"),text(it,"name"),Coordinate(c[0],c[1]))
        }.filter { point -> graph.bounds.contains(point.coordinate) && runCatching {
            EdgeSnapper().candidates(point.coordinate,graph,50.0)
        }.isSuccess }
        return PredictionSceneInfo("model-scene-v2-"+sha(bytes),graph,text(root,"attribution"),limitations,buildings.size,terrain.size,error,omitted,buffer,areaName,accessPoints,
            componentPolicy,sourceGraph.connectedComponents.size,sourceGraph.nodes.size-graph.nodes.size) to ModelScene(projection,terrain,buildings)
    }
    private fun elements(node: JsonNode): List<JsonNode> { require(node.isArray);return (0 until node.size()).map { node[it] } }
    private fun numbers(node: JsonNode)=elements(node).map { require(it.isNumber);it.asDouble().also { v -> require(v.isFinite()) } }
    private fun text(node: JsonNode,key: String): String { val value=requireNotNull(node[key]);require(value.isString && value.asString().isNotBlank());return value.asString() }
    private fun number(node: JsonNode,key: String): Double { val value=requireNotNull(node[key]);require(value.isNumber);return value.asDouble().also { require(it.isFinite()) } }
    private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
