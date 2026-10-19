package com.igilro.server.route.domain.shade

import com.igilro.server.route.domain.Bounds
import com.igilro.server.route.domain.Coordinate
import java.time.Instant

data class FootprintPolygon(val rings: List<List<Coordinate>>) {
    init {
        require(rings.isNotEmpty())
        require(rings.all { it.size >= 4 && it.first() == it.last() })
        require(rings.flatten().all { it.longitude in 124.0..132.0 && it.latitude in 33.0..39.0 }) {
            "Building shade is limited to the Korean pilot projection"
        }
    }
}

data class BuildingFootprint(val id: String, val polygons: List<FootprintPolygon>, val heightMeters: Double?) {
    init {
        require(id.isNotBlank() && id.length <= 200 && polygons.isNotEmpty())
        require(heightMeters == null || heightMeters.isFinite() && heightMeters > 0 && heightMeters <= 500)
    }
}

data class BuildingShadeImport(
    val dataVersion: String,
    val areaId: String,
    val source: String,
    val sourceTimestamp: Instant,
    val attribution: String,
    val checksum: String,
    val inventoryComplete: Boolean,
    val maximumHeightMeters: Double,
    val coverage: List<FootprintPolygon>,
    val buildings: List<BuildingFootprint>,
) {
    init {
        require(dataVersion.matches(Regex("buildings-v1-[a-f0-9]{64}")))
        require(areaId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(source.isNotBlank() && source.length <= 4096 && attribution.isNotBlank() && attribution.length <= 4096)
        require(checksum.matches(Regex("[a-f0-9]{64}")))
        require(maximumHeightMeters.isFinite() && maximumHeightMeters > 0 && maximumHeightMeters <= 500)
        require(coverage.isNotEmpty() && buildings.size <= 25_000)
        require(buildings.map { it.id }.toSet().size == buildings.size)
        require(buildings.all { it.heightMeters == null || it.heightMeters <= maximumHeightMeters })
        val points = coverage.flatMap { it.rings.flatten() }
        Bounds(points.minOf { it.longitude }, points.minOf { it.latitude }, points.maxOf { it.longitude }, points.maxOf { it.latitude })
        require((coverage + buildings.flatMap { it.polygons }).sumOf { polygon -> polygon.rings.sumOf { it.size } } <= 250_000)
    }
}

data class BuildingShadeImportSummary(val dataVersion: String, val buildingCount: Int, val knownHeightCount: Int, val reused: Boolean)

fun interface BuildingShadeImportRepository {
    fun save(dataset: BuildingShadeImport): BuildingShadeImportSummary
}
