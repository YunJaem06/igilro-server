package com.igilro.server.route.infrastructure.shade

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class GeoJsonBuildingReaderTest {
    @TempDir lateinit var directory: Path
    private fun fixture() = javaClass.getResource("/shade/buildings.geojson")!!.readText()
    private fun read(content: String) = GeoJsonBuildingReader().read(Files.writeString(directory.resolve("buildings.geojson"), content))

    @Test
    fun `source coverage heights and checksum are preserved reproducibly`() {
        val dataset = read(fixture())
        assertEquals("shade-test", dataset.areaId)
        assertEquals("Synthetic test geometry; not real buildings", dataset.attribution)
        assertEquals(20.0, dataset.buildings.single().heightMeters)
        assertEquals(dataset, read(fixture()))
        assertEquals("buildings-v1-${dataset.checksum}", dataset.dataVersion)
    }

    @Test
    fun `missing height remains unknown and an incomplete inventory is retained`() {
        val dataset = read(fixture().replace("\"heightMeters\": 20", "\"heightMeters\": null").replace("\"inventoryComplete\": true", "\"inventoryComplete\": false"))
        assertNull(dataset.buildings.single().heightMeters)
        assertFalse(dataset.inventoryComplete)
    }

    @Test
    fun `invalid heights absent provenance and reversed coordinate order are rejected`() {
        listOf(
            fixture().replace("\"heightMeters\": 20", "\"heightMeters\": 0"),
            fixture().replace("\"heightMeters\": 20", "\"heightMeters\": 21"),
            fixture().replace("\"heightMeters\": 20", "\"heightMeters\": \"20\""),
            fixture().replace("\"metadata\"", "\"missing\""),
            fixture().replace("[127.0308,37.53995]", "[37.53995,127.0308]"),
            fixture().replace("2026-10-10T00:00:00Z", "2026-10-10T00:00:00"),
        ).forEach { assertFails { read(it) } }
    }
}
