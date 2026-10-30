package com.igilro.server.route.infrastructure.shade

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import com.igilro.server.route.domain.shade.BuildingHeightSource
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
        assertEquals(BuildingHeightSource.ACTUAL, dataset.buildings.single().heightSource)
        assertEquals("synthetic-test-fixture", dataset.buildings.single().source)
        assertEquals("fixture:building-1", dataset.buildings.single().sourceId)
        assertEquals(dataset, read(fixture()))
        assertEquals("buildings-v1-${dataset.checksum}", dataset.dataVersion)
    }

    @Test
    fun `missing height remains unknown and an incomplete inventory is retained`() {
        val dataset = read(fixture().replace("\"heightMeters\": 20", "\"heightMeters\": null")
            .replace("\"heightSource\": \"ACTUAL\"", "\"heightSource\": \"UNKNOWN\"")
            .replace("\"inventoryComplete\": true", "\"inventoryComplete\": false"))
        assertNull(dataset.buildings.single().heightMeters)
        assertFalse(dataset.inventoryComplete)
    }

    @Test
    fun `height provenance cannot be invented from an absent label or source identifier`() {
        val legacy = read(fixture().replace("\"heightSource\": \"ACTUAL\", ", ""))
        assertEquals(BuildingHeightSource.UNKNOWN, legacy.buildings.single().heightSource)
        listOf(
            fixture().replace("\"sourceId\": \"fixture:building-1\"", "\"sourceId\": null"),
            fixture().replace("\"heightSourceId\": \"fixture:height-1\"", "\"heightSourceId\": null"),
            fixture().replace("\"heightSource\": \"ACTUAL\"", "\"heightSource\": \"ASSUMED\""),
            fixture().replace("\"heightSource\": \"ACTUAL\"", "\"heightSource\": 1"),
        ).forEach { assertFails { read(it) } }
    }

    @Test
    fun `unverified maximum is accepted only for incomplete inventories`() {
        val unverified = fixture().replace("\"maximumHeightMeters\": 20", "\"maximumHeightMeters\": null")
        assertFailsWith<IllegalArgumentException> { read(unverified) }
        assertFailsWith<IllegalArgumentException> { read(fixture().replace("\"maximumHeightMeters\": 20,", "")) }
        val dataset = read(unverified.replace("\"inventoryComplete\": true", "\"inventoryComplete\": false"))
        assertNull(dataset.maximumHeightMeters)
        assertEquals(20.0, dataset.buildings.single().heightMeters)
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
