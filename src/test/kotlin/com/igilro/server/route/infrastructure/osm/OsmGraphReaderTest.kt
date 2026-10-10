package com.igilro.server.route.infrastructure.osm

import com.igilro.server.route.application.ImportWalkingGraphUseCase
import com.igilro.server.route.domain.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.*

class OsmGraphReaderTest {
    @TempDir lateinit var directory: Path
    private val bounds = Bounds(127.02, 37.53, 127.05, 37.56)
    private val fixture = javaClass.getResource("/osm/walking-network.osm")!!.readText()
    private fun read(text: String = fixture): GraphImport {
        val file = directory.resolve("extract.osm")
        file.writeText(text)
        return OsmGraphReader().read(file, "test", "Test", bounds, "synthetic-fixture")
    }

    @Test
    fun `only connected permitted bounded segments are imported with original node IDs`() {
        val graph = read()
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L, 6L), graph.nodes.keys)
        assertEquals(5, graph.edges.size)
        assertEquals(2, graph.edges.count { it.wayId == 101L })
        assertTrue(graph.edges.single { it.wayId == 107L }.backwardAllowed)
        assertEquals(EdgeKind.STEPS, graph.edges.single { it.wayId == 102L }.kind)
    }

    @Test
    fun `same input is reproducible and bounds contribute to the data version`() {
        assertEquals(read().dataVersion, read().dataVersion)
        val file = directory.resolve("extract.osm")
        file.writeText(fixture)
        val changed = OsmGraphReader().read(file, "test", "Test", Bounds(127.021, 37.53, 127.05, 37.56), "synthetic-fixture")
        assertNotEquals(read().dataVersion, changed.dataVersion)
    }

    @Test
    fun `missing referenced nodes and partial Overpass results are rejected`() {
        assertFailsWith<IllegalArgumentException> { read(fixture.replace("<nd ref=\"2\"/>", "<nd ref=\"999\"/>")) }
        assertFails { read(fixture.replace("</osm>", "<remark>timeout</remark></osm>")) }
    }

    @Test
    fun `DTD and external entities are rejected`() {
        assertFails { read("<!DOCTYPE osm [<!ENTITY external SYSTEM 'file:///never-read'>]><osm>&external;</osm>") }
    }

    @Test
    fun `invalid imports do not reach persistence`() {
        var called = false
        val importer = ImportWalkingGraphUseCase(GraphImportRepository { called = true; error("Should not persist") })
        assertFailsWith<IllegalArgumentException> { importer.execute(read().copy(edges = emptyList())) }
        assertFalse(called)
    }
}
