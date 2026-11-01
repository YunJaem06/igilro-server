package com.igilro.server.route.infrastructure.data

import com.igilro.server.route.domain.Bounds
import com.igilro.server.route.domain.Coordinate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.core.StreamReadFeature
import java.time.OffsetDateTime

data class RouteDataObject(val key: String, val versionId: String, val sha256: String, val sizeBytes: Long)
data class RouteDataGraph(val areaId: String, val areaName: String, val bounds: Bounds, val source: String, val expectedDataVersion: String)
data class RouteDataManifest(val files: Map<String, RouteDataObject>, val graph: RouteDataGraph) {
    companion object {
        val FILE_LIMITS = mapOf("scene.json" to 32_000_000L, "roads.osm" to 20_000_000L, "walking.osm" to 20_000_000L)

        fun parse(bytes: ByteArray, manifestKey: String): RouteDataManifest {
            require(bytes.size in 1..65_536)
            require(manifestKey.matches(Regex("datasets/[a-f0-9]{64}/manifest\\.json")))
            val root = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build().readTree(bytes)
            require(root.isObject && root["schemaVersion"]?.isInt == true && root["schemaVersion"].asInt() == 1)
            val nodes = requireNotNull(root["files"])
            require(nodes.isObject && nodes.propertyNames().toSet() == FILE_LIMITS.keys)
            val prefix = manifestKey.substringBeforeLast('/')
            val files = FILE_LIMITS.mapValues { (name, limit) ->
                val node = requireNotNull(nodes[name])
                val size = requireNotNull(node["sizeBytes"])
                require(size.isIntegralNumber && size.asLong() in 1..limit)
                val key = text(node,"key"); require(key == "$prefix/$name")
                val version = text(node,"versionId"); require(version != "null" && version.length <= 1024)
                val hash = text(node,"sha256"); require(hash.matches(Regex("[a-f0-9]{64}")))
                RouteDataObject(key,version,hash,size.asLong())
            }
            val graph = requireNotNull(root["graph"])
            val id = text(graph,"areaId"); require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
            val name = text(graph,"areaName"); require(name.length <= 200)
            val source = text(graph,"source"); require(source.startsWith("https://") && source.length <= 4096 && '?' !in source && '@' !in source)
            val version = text(graph,"expectedDataVersion"); require(version.matches(Regex("osm-v1-[a-f0-9]{64}")))
            val bounds = requireNotNull(graph["bounds"])
            require(bounds.isArray && bounds.size() == 4)
            val numbers = (0..3).map { require(bounds[it].isNumber); bounds[it].asDouble().also { n -> require(n.isFinite()) } }
            val area = Bounds(numbers[0],numbers[1],numbers[2],numbers[3])
            require(area.west >= 124 && area.east <= 132 && area.south >= 33 && area.north <= 39 &&
                area.east-area.west <= .1 && area.north-area.south <= .1)
            val smoke = requireNotNull(root["smokeQuery"])
            for (field in listOf("origin","destination")) {
                val p = requireNotNull(smoke[field])
                require(p["longitude"]?.isNumber == true && p["latitude"]?.isNumber == true)
                require(area.contains(Coordinate(p["longitude"].asDouble(),p["latitude"].asDouble())))
            }
            require(OffsetDateTime.parse(text(smoke,"departureAt")).year in 2000..2100)
            require(text(smoke,"travelMode") == "walking")
            return RouteDataManifest(files,RouteDataGraph(id,name,area,source,version))
        }

        private fun text(node: JsonNode,key: String): String {
            val value = requireNotNull(node[key]); require(value.isString)
            return value.asString().also { require(it.isNotBlank() && it.none(Char::isISOControl)) }
        }
    }
}
