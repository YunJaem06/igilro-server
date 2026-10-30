package com.igilro.server.route.infrastructure.osm

import com.igilro.server.route.domain.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

class OsmGraphReader(private val policy: OsmWalkingPolicy = OsmWalkingPolicy()) {
    private data class Node(val coordinate: Coordinate, val tags: Map<String, String>)
    private data class Way(val id: Long, val nodes: List<Long>, val tags: Map<String, String>)

    fun read(file: Path, areaId: String, areaName: String, bounds: Bounds, source: String): GraphImport {
        require(Files.isRegularFile(file) && Files.size(file) in 1..20_000_000) { "OSM XML must be a nonempty file <= 20 MB" }
        val bytes = Files.readAllBytes(file)
        val factory = XMLInputFactory.newFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        }
        val nodes = mutableMapOf<Long, Node>()
        val ways = mutableListOf<Way>()
        var sourceTimestamp: Instant? = null
        bytes.inputStream().use { input ->
            val xml = factory.createXMLStreamReader(input)
            try {
                var id = 0L
                var point: Coordinate? = null
                var references = mutableListOf<Long>()
                var tags = mutableMapOf<String, String>()
                var element: String? = null
                var rootSeen = false
                while (xml.hasNext()) {
                    when (xml.next()) {
                        XMLStreamConstants.DTD -> error("DTD is not allowed in OSM input")
                        XMLStreamConstants.START_ELEMENT -> when (xml.localName) {
                            "osm" -> rootSeen = true
                            "remark" -> error("Overpass returned a partial or failed query")
                            "meta" -> sourceTimestamp = Instant.parse(xml.getAttributeValue(null, "osm_base"))
                            "node", "way" -> {
                                element = xml.localName
                                id = xml.getAttributeValue(null, "id").toLong()
                                require(id > 0)
                                tags = mutableMapOf()
                                references = mutableListOf()
                                point = if (element == "node") Coordinate(xml.getAttributeValue(null, "lon").toDouble(), xml.getAttributeValue(null, "lat").toDouble()) else null
                            }
                            "tag" -> if (element != null) tags[xml.getAttributeValue(null, "k")] = xml.getAttributeValue(null, "v")
                            "nd" -> if (element == "way") references.add(xml.getAttributeValue(null, "ref").toLong())
                        }
                        XMLStreamConstants.END_ELEMENT -> when (xml.localName) {
                            "node" -> {
                                require(id !in nodes) { "Duplicate OSM node" }
                                nodes[id] = Node(requireNotNull(point), tags.toMap())
                                element = null
                            }
                            "way" -> {
                                ways.add(Way(id, references.toList(), tags.toMap()))
                                element = null
                            }
                        }
                    }
                    require(nodes.size <= 150_000 && ways.size <= 50_000) { "OSM extract is too large" }
                }
                require(rootSeen) { "OSM root is required" }
            } finally {
                xml.close()
            }
        }
        require(ways.map { it.id }.toSet().size == ways.size) { "Duplicate OSM way" }
        val edges = mutableListOf<ImportedEdge>()
        val edgeGuidance = mutableMapOf<Long,EdgeGuidanceMetadata>()
        val used = linkedMapOf<Long, Coordinate>()
        ways.sortedBy { it.id }.forEach { way ->
            val permissions = policy.way(way.tags) ?: return@forEach
            way.nodes.zipWithNext().forEach { (fromId, toId) ->
                val from = requireNotNull(nodes[fromId]) { "Missing referenced node $fromId" }
                val to = requireNotNull(nodes[toId]) { "Missing referenced node $toId" }
                if (fromId != toId && from.coordinate != to.coordinate && bounds.contains(from.coordinate) && bounds.contains(to.coordinate) &&
                    policy.nodeAllowed(from.tags) && policy.nodeAllowed(to.tags)
                ) {
                    used[fromId] = from.coordinate
                    used[toId] = to.coordinate
                    edges.add(ImportedEdge(edges.size.toLong() + 1, way.id, fromId, toId, permissions.kind, permissions.forward, permissions.backward))
                    edgeGuidance[edges.last().id] = OsmGuidanceTags.edge(way.id,way.tags)
                }
            }
        }
        val checksum = sha256(bytes)
        val version = "osm-v1-" + sha256("$checksum|$areaId|$bounds|${OsmWalkingPolicy.VERSION}".toByteArray())
        return GraphImport(version, areaId, areaName, bounds, source, requireNotNull(sourceTimestamp) { "OSM snapshot timestamp is required" }, checksum, used, edges,
            used.keys.mapNotNull { id -> OsmGuidanceTags.node(nodes.getValue(id).tags)?.let { id to it } }.toMap(),edgeGuidance,GuidanceMetadataVersion.OSM_V1)
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
