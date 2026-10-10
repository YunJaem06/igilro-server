package com.igilro.server.route.infrastructure

import com.igilro.server.route.domain.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Repository
class JdbcGraphImportRepository(
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) : GraphImportRepository {
    private val transaction = TransactionTemplate(transactionManager)

    override fun save(graph: GraphImport): GraphImportSummary = transaction.execute {
        // Serialize imports, while route requests keep reading the previous committed dataset.
        jdbc.execute("SELECT pg_advisory_xact_lock(73694117)")
        val reused = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM route_dataset WHERE id = ?)", Boolean::class.java, graph.dataVersion) == true
        if (!reused) {
            jdbc.update(
                "INSERT INTO route_dataset(id, area_id, source, source_timestamp, checksum, node_count, edge_count) VALUES (?,?,?,?,?,?,?)",
                graph.dataVersion, graph.areaId, graph.source, java.sql.Timestamp.from(graph.sourceTimestamp), graph.checksum,
                graph.nodes.size, graph.edges.size,
            )
            val nodes = graph.nodes.entries.toList()
            jdbc.batchUpdate(
                "INSERT INTO route_node(dataset_id, osm_id, location) VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?),4326))",
                nodes, 500,
            ) { statement, node ->
                statement.setString(1, graph.dataVersion)
                statement.setLong(2, node.key)
                statement.setDouble(3, node.value.longitude)
                statement.setDouble(4, node.value.latitude)
            }
            jdbc.batchUpdate(
                """INSERT INTO route_edge(dataset_id,id,osm_way_id,from_node,to_node,kind,forward_allowed,backward_allowed,geometry,distance_meters)
                    VALUES (?,?,?,?,?,?,?,?,ST_GeomFromText(?,4326),ST_Length(ST_GeomFromText(?,4326)::geography))""".trimIndent(),
                graph.edges, 500,
            ) { statement, edge ->
                val from = graph.nodes.getValue(edge.from)
                val to = graph.nodes.getValue(edge.to)
                val wkt = "LINESTRING(${from.longitude} ${from.latitude},${to.longitude} ${to.latitude})"
                statement.setString(1, graph.dataVersion)
                statement.setLong(2, edge.id)
                statement.setLong(3, edge.wayId)
                statement.setLong(4, edge.from)
                statement.setLong(5, edge.to)
                statement.setString(6, edge.kind.name)
                statement.setBoolean(7, edge.forwardAllowed)
                statement.setBoolean(8, edge.backwardAllowed)
                statement.setString(9, wkt)
                statement.setString(10, wkt)
            }
        }
        jdbc.update(
            """INSERT INTO route_service_area(id,name,west,south,east,north,boundary,active_dataset_id)
                VALUES (?,?,?,?,?,?,ST_GeomFromText(?,4326),?)
                ON CONFLICT(id) DO UPDATE SET name=EXCLUDED.name, west=EXCLUDED.west, south=EXCLUDED.south,
                east=EXCLUDED.east, north=EXCLUDED.north, boundary=EXCLUDED.boundary, active_dataset_id=EXCLUDED.active_dataset_id""".trimIndent(),
            graph.areaId, graph.areaName, graph.bounds.west, graph.bounds.south, graph.bounds.east, graph.bounds.north,
            polygonWkt(graph.bounds), graph.dataVersion,
        )
        GraphImportSummary(graph.dataVersion, graph.nodes.size, graph.edges.size, reused)
    }

    private fun polygonWkt(bounds: Bounds) = with(bounds) {
        "POLYGON(($west $south,$east $south,$east $north,$west $north,$west $south))"
    }
}
