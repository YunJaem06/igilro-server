package com.igilro.server.route.infrastructure

import com.igilro.server.route.domain.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class JdbcGuidanceMetadataRepository(private val jdbc: JdbcTemplate) {
    data class Snapshot(val version: String?,val nodes: Map<Long,NodeGuidanceMetadata>,val edges: Map<Long,EdgeGuidanceMetadata>)

    /** Caller owns the graph import transaction and advisory lock. Enrichment is one-time. */
    fun save(graph: GraphImport) {
        if(graph.guidanceMetadataVersion==null) return
        val existing=load(graph.dataVersion)
        if(existing.version!=null) {
            require(existing.version==graph.guidanceMetadataVersion && existing.nodes==graph.nodeGuidance && existing.edges==graph.edgeGuidance) {
                "Guidance metadata differs from its immutable source snapshot"
            }
            return
        }
        jdbc.batchUpdate("INSERT INTO route_node_guidance(dataset_id,osm_id,traffic_signals) VALUES (?,?,?)",graph.nodeGuidance.entries.toList(),500) { statement,item ->
            statement.setString(1,graph.dataVersion);statement.setLong(2,item.key);statement.setObject(3,item.value.crossing.trafficSignals,java.sql.Types.BOOLEAN)
        }
        jdbc.batchUpdate("INSERT INTO route_edge_guidance(dataset_id,edge_id,street_name,is_crossing,traffic_signals) VALUES (?,?,?,?,?)",graph.edgeGuidance.entries.toList(),500) { statement,item ->
            statement.setString(1,graph.dataVersion);statement.setLong(2,item.key);statement.setString(3,item.value.streetName)
            statement.setBoolean(4,item.value.crossing!=null);statement.setObject(5,item.value.crossing?.trafficSignals,java.sql.Types.BOOLEAN)
        }
        jdbc.update("INSERT INTO route_guidance_dataset(dataset_id,metadata_version) VALUES (?,?)",graph.dataVersion,graph.guidanceMetadataVersion)
    }

    fun load(dataset: String): Snapshot {
        val version=jdbc.query("SELECT metadata_version FROM route_guidance_dataset WHERE dataset_id=?",{ row,_ -> row.getString(1) },dataset).singleOrNull()
            ?: return Snapshot(null,emptyMap(),emptyMap())
        val nodes=jdbc.query("SELECT osm_id,traffic_signals FROM route_node_guidance WHERE dataset_id=?",{ row,_ ->
            row.getLong("osm_id") to NodeGuidanceMetadata(CrossingMetadata(row.getObject("traffic_signals") as Boolean?))
        },dataset).toMap()
        val edges=jdbc.query("""SELECT g.edge_id,e.osm_way_id,g.street_name,g.is_crossing,g.traffic_signals
            FROM route_edge_guidance g JOIN route_edge e ON e.dataset_id=g.dataset_id AND e.id=g.edge_id WHERE g.dataset_id=?""",{ row,_ ->
            row.getLong("edge_id") to EdgeGuidanceMetadata(row.getLong("osm_way_id"),row.getString("street_name"),
                if(row.getBoolean("is_crossing")) CrossingMetadata(row.getObject("traffic_signals") as Boolean?) else null)
        },dataset).toMap()
        return Snapshot(version,nodes,edges)
    }
}
