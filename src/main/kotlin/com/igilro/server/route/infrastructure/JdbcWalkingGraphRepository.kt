package com.igilro.server.route.infrastructure

import com.igilro.server.route.domain.*
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.concurrent.ConcurrentHashMap

@Repository
class JdbcWalkingGraphRepository(private val jdbc: JdbcTemplate) : WalkingGraphRepository {
    private data class ActiveDataset(val version: String, val area: String, val bounds: Bounds, val nodes: Int, val edges: Int)
    private val cache = ConcurrentHashMap<ActiveDataset, WalkingGraph>()

    override fun findActiveGraphs(): List<WalkingGraph> = try {
        // Metadata is checked for every request; an imported dataset's contents are immutable.
        val active = jdbc.query(
            """SELECT a.id, a.west, a.south, a.east, a.north, d.id AS version, d.area_id AS dataset_area, d.node_count, d.edge_count
                FROM route_service_area a JOIN route_dataset d ON d.id=a.active_dataset_id ORDER BY a.id""".trimIndent(),
        ) { row, _ ->
            require(row.getString("id") == row.getString("dataset_area")) { "Active dataset belongs to another area" }
            ActiveDataset(row.getString("version"), row.getString("id"),
                Bounds(row.getDouble("west"), row.getDouble("south"), row.getDouble("east"), row.getDouble("north")),
                row.getInt("node_count"), row.getInt("edge_count"))
        }
        cache.keys.retainAll(active.toSet())
        active.map { dataset -> cache.computeIfAbsent(dataset) { load(dataset) } }
    } catch (ex: DataAccessException) {
        throw RouteFailure(RouteFailureReason.DATA_UNAVAILABLE, ex)
    } catch (ex: IllegalArgumentException) {
        // Stored geometry and graph invariants can be invalid even when SQL CHECKs pass.
        throw RouteFailure(RouteFailureReason.DATA_UNAVAILABLE, ex)
    }

    private fun load(dataset: ActiveDataset): WalkingGraph {
        if (dataset.nodes !in 2..100_000 || dataset.edges !in 1..250_000) throw RouteFailure(RouteFailureReason.DATA_UNAVAILABLE)
        val nodes = jdbc.query("SELECT osm_id, ST_X(location) AS lon, ST_Y(location) AS lat FROM route_node WHERE dataset_id=?", { row, _ ->
            row.getLong("osm_id") to Coordinate(row.getDouble("lon"), row.getDouble("lat"))
        }, dataset.version).toMap()
        val edges = jdbc.query("SELECT id, from_node, to_node, distance_meters, kind, forward_allowed, backward_allowed FROM route_edge WHERE dataset_id=? ORDER BY id", { row, _ ->
            WalkingEdge(row.getLong("id"), row.getLong("from_node"), row.getLong("to_node"), row.getDouble("distance_meters"),
                EdgeKind.valueOf(row.getString("kind")), row.getBoolean("forward_allowed"), row.getBoolean("backward_allowed"))
        }, dataset.version)
        if (nodes.size != dataset.nodes || edges.size != dataset.edges) throw RouteFailure(RouteFailureReason.DATA_UNAVAILABLE)
        return WalkingGraph(dataset.version, dataset.area, dataset.bounds, nodes, edges)
    }
}
