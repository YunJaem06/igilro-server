package com.igilro.server.route.infrastructure.prediction

import com.igilro.server.route.domain.*
import com.igilro.server.route.domain.prediction.ModelScene
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.math.floor
import kotlin.test.*

class FileModelPredictionSourceTest {
    @TempDir lateinit var folder: Path
    private fun scene(heightSource: String="VWORLD_MODEL_ATTRIBUTE",complete: Boolean=false): Path {
        val osm="""<osm version="0.6"><meta osm_base="2026-10-25T00:00:00Z"/>
            <node id="1" lon="127.0374" lat="37.5483"/><node id="2" lon="127.0376" lat="37.5484"/>
            <way id="10"><nd ref="1"/><nd ref="2"/><tag k="highway" v="footway"/></way></osm>""".toByteArray()
        Files.write(folder.resolve("roads.osm"),osm)
        val size=36.0/(1 shl 15)
        val root=mapOf("schemaVersion" to 1,"kind" to "PUBLIC_3D_MODEL_PREDICTION_ONLY","modelVersion" to ModelScene.MODEL_VERSION,
            "sceneComplete" to complete,"graph" to mapOf("roadsFile" to "roads.osm","roadsSha256" to MessageDigest.getInstance("SHA-256").digest(osm).joinToString("") { "%02x".format(it) },"areaId" to "fixture","bounds" to listOf(127.037,37.548,127.038,37.549)),
            "projection" to mapOf("origin" to listOf(127.0374,37.5483),"coefficients" to listOf(listOf(88000.0,0.0),listOf(0.0,111000.0),listOf(0.0,0.0)),"checkedMaxErrorMeters" to .01,"targetCrs" to "EPSG:5179"),
            "terrain" to listOf(mapOf("level" to 15,"x" to floor((127.0374+180)/size).toInt(),"y" to floor((37.5483+90)/size).toInt(),"values" to List(4225) { 10.0 })),
            "buildings" to listOf(mapOf("id" to "model","heightSource" to heightSource,"baseMethod" to "DEM_AT_MODEL_INTERIOR_POINT","sourceUrl" to "https://cdn.vworld.kr/TDServer/services/map4/fixture.b3dm","sourceSha256" to "a".repeat(64),"heightMeters" to 20.0,"baseElevationMeters" to 10.0,
                "polygons" to listOf(listOf(listOf(listOf(127.0375,37.5482),listOf(127.0376,37.5482),listOf(127.0376,37.5483),listOf(127.0375,37.5482)))))),
            "limitations" to listOf("MODEL_HEIGHTS_ARE_NOT_ACTUAL_HEIGHTS","PARTIAL_PROVIDER_SCENE"),"attribution" to "synthetic fixture",
            "omittedModelIds" to emptyList<String>(),"sceneBufferMetersApprox" to 150.0)
        val path=folder.resolve("scene.json");Files.write(path,JsonMapper.builder().build().writeValueAsBytes(root));return path
    }
    @Test fun `disabled source leaves ordinary route and health services independent`() { assertNull(FileModelPredictionSource(null).current()) }
    @Test fun `file snapshot parses original walking graph without DB activation and detects tampering`() {
        val file=scene();val info=FileModelPredictionSource(file).current()!!
        assertEquals(2,info.graph.nodes.size);assertEquals(1,info.buildingCount);assertTrue(info.dataVersion.startsWith("model-scene-v2-"))
        Files.writeString(folder.resolve("roads.osm"),"changed")
        assertFailsWith<IllegalArgumentException> { FileModelPredictionSource(file) }
    }
    @Test fun `model source cannot claim ACTUAL heights or complete scene`() {
        assertFailsWith<IllegalArgumentException> { FileModelPredictionSource(scene(heightSource="ACTUAL")) }
        assertFailsWith<IllegalArgumentException> { FileModelPredictionSource(scene(complete=true)) }
    }
    @Test fun `packed float terrain uses little endian and rejects truncated payload`() {
        val file=scene();val mapper=JsonMapper.builder().build();val root=mapper.readTree(Files.readAllBytes(file)) as tools.jackson.databind.node.ObjectNode
        val tile=root["terrain"][0] as tools.jackson.databind.node.ObjectNode
        tile.remove("values")
        val buffer=java.nio.ByteBuffer.allocate(16900).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        repeat(4225) { buffer.putFloat(10.0f) }
        tile.put("float32leBase64",java.util.Base64.getEncoder().encodeToString(buffer.array()))
        root.put("schemaVersion",2);Files.write(file,mapper.writeValueAsBytes(root))
        assertEquals(1,FileModelPredictionSource(file).current()!!.terrainTileCount)
        tile.put("float32leBase64","AAAA");Files.write(file,mapper.writeValueAsBytes(root))
        assertFailsWith<IllegalArgumentException> { FileModelPredictionSource(file) }
    }
    @Test fun `existing v1 scenes remain readable by the v2 calculation engine`() {
        val file=scene();Files.writeString(file,Files.readString(file).replace(ModelScene.MODEL_VERSION,"public-3d-prism-dem-v1-step0.5m"))
        assertNotNull(FileModelPredictionSource(file).current())
    }
    @Test fun `connected core excludes islands without inventing source connections and versions the policy`() {
        val file=scene();val mapper=JsonMapper.builder().build();val root=mapper.readTree(Files.readAllBytes(file)) as tools.jackson.databind.node.ObjectNode
        val roads=folder.resolve("roads.osm")
        val xml=Files.readString(roads).replace("</osm>","""<node id="3" lon="127.0377" lat="37.5485"/><node id="4" lon="127.0378" lat="37.5485"/>
            <way id="11"><nd ref="3"/><nd ref="4"/><tag k="highway" v="footway"/></way></osm>""")
        Files.writeString(roads,xml)
        val graph=root["graph"] as tools.jackson.databind.node.ObjectNode
        graph.put("roadsSha256",MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(roads)).joinToString("") { "%02x".format(it) })
        Files.write(file,mapper.writeValueAsBytes(root))
        val all=FileModelPredictionSource(file).current()!!
        assertEquals(4,all.graph.nodes.size)
        graph.put("componentPolicy","LARGEST_CONNECTED_COMPONENT");Files.write(file,mapper.writeValueAsBytes(root))
        val core=FileModelPredictionSource(file).current()!!
        assertEquals(setOf(1L,2L),core.graph.nodes.keys)
        assertEquals(1,core.graph.connectedComponents.size);assertEquals(2,core.sourceComponentCount)
        assertEquals(2,core.excludedDisconnectedNodeCount);assertNotEquals(all.graph.dataVersion,core.graph.dataVersion)
    }
}
