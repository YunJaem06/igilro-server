package com.igilro.server.route.infrastructure.shade

import com.igilro.server.route.domain.Coordinate
import com.igilro.server.route.domain.shade.*
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.OffsetDateTime

class GeoJsonBuildingReader {
    private val mapper = JsonMapper.builder().build()

    fun read(file: Path): BuildingShadeImport {
        require(Files.isRegularFile(file) && Files.size(file) in 1..20_000_000) { "Building GeoJSON must be a nonempty file <= 20 MB" }
        val bytes = Files.readAllBytes(file)
        val root = mapper.readTree(bytes)
        require(root["type"]?.asString() == "FeatureCollection")
        val metadata = requireNotNull(root["metadata"]) { "Dataset provenance and coverage are required" }
        val features = requireNotNull(root["features"])
        require(features.isArray && features.size() <= 25_000)
        val buildings = elements(features).map { feature ->
            require(feature["type"]?.asString() == "Feature")
            val height = feature["properties"]?.get("heightMeters")?.takeUnless { it.isNull }?.let {
                require(it.isNumber) { "heightMeters must be a measured number or null, not a floor count" }
                it.asDouble()
            }
            val properties = feature["properties"]
            val heightSource = properties?.let { optionalText(it, "heightSource") }?.let(BuildingHeightSource::valueOf)
                ?: BuildingHeightSource.UNKNOWN
            BuildingFootprint(text(feature, "id"), polygons(requireNotNull(feature["geometry"])), height, heightSource,
                properties?.let { optionalText(it, "source") }, properties?.let { optionalText(it, "sourceId") },
                properties?.let { optionalText(it, "heightSourceReference") }, properties?.let { optionalText(it, "heightSourceId") })
        }
        val complete = requireNotNull(metadata["inventoryComplete"])
        require(complete.isBoolean)
        val maximumHeight = metadata["maximumHeightMeters"]?.takeUnless { it.isNull }?.let {
            require(it.isNumber)
            it.asDouble()
        }
        val checksum = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return BuildingShadeImport("buildings-v1-$checksum", text(metadata, "areaId"), text(metadata, "source"),
            OffsetDateTime.parse(text(metadata, "sourceTimestamp")).toInstant(), text(metadata, "attribution"), checksum,
            complete.asBoolean(), maximumHeight, polygons(requireNotNull(metadata["coverage"])), buildings)
    }

    private fun text(node: JsonNode, key: String): String {
        val value = requireNotNull(node[key]) { "$key is required" }
        require(value.isString) { "$key must be a string" }
        return value.asString()
    }

    private fun optionalText(node: JsonNode, key: String): String? {
        val value = node[key]?.takeUnless { it.isNull } ?: return null
        require(value.isString) { "$key must be a string" }
        return value.asString()
    }

    private fun elements(node: JsonNode): List<JsonNode> {
        require(node.isArray)
        return (0 until node.size()).map { node[it] }
    }

    private fun polygons(geometry: JsonNode): List<FootprintPolygon> {
        val coordinates = requireNotNull(geometry["coordinates"])
        require(coordinates.isArray)
        fun polygon(node: JsonNode): FootprintPolygon {
            require(node.isArray)
            return FootprintPolygon(elements(node).map { ring ->
                require(ring.isArray)
                elements(ring).map { point ->
                    require(point.isArray && point.size() == 2 && point[0].isNumber && point[1].isNumber) { "Coordinates must be WGS84 [longitude, latitude] pairs" }
                    Coordinate(point[0].asDouble(), point[1].asDouble())
                }
            })
        }
        return when (geometry["type"]?.asString()) {
            "Polygon" -> listOf(polygon(coordinates))
            "MultiPolygon" -> elements(coordinates).map(::polygon)
            else -> throw IllegalArgumentException("Only Polygon/MultiPolygon building geometry is supported")
        }
    }
}
