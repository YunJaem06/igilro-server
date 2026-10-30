package com.igilro.server.route.infrastructure.shade

import com.igilro.server.route.domain.shade.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp

@Repository
class JdbcBuildingShadeImportRepository(jdbcTemplate: JdbcTemplate, transactionManager: PlatformTransactionManager) : BuildingShadeImportRepository {
    private val jdbc = jdbcTemplate
    private val transaction = TransactionTemplate(transactionManager)

    override fun save(dataset: BuildingShadeImport): BuildingShadeImportSummary = transaction.execute {
        jdbc.execute("SELECT pg_advisory_xact_lock(73694118)")
        val knownCount = dataset.buildings.count { it.heightMeters != null }
        val coverage = multiPolygonWkt(dataset.coverage)
        val reused = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM building_shade_dataset WHERE id=?)", Boolean::class.java, dataset.dataVersion) == true
        if (reused) {
            val matches = jdbc.queryForObject(
                """SELECT area_id=? AND source=? AND source_timestamp=? AND attribution=? AND checksum=?
                    AND inventory_complete=? AND maximum_height_meters IS NOT DISTINCT FROM ?::float8 AND building_count=? AND known_height_count=?
                    AND ST_Equals(coverage,ST_GeomFromText(?,4326)) FROM building_shade_dataset WHERE id=?""".trimIndent(),
                Boolean::class.java, dataset.areaId, dataset.source, Timestamp.from(dataset.sourceTimestamp), dataset.attribution,
                dataset.checksum, dataset.inventoryComplete, dataset.maximumHeightMeters, dataset.buildings.size, knownCount, coverage, dataset.dataVersion,
            )
            require(matches == true) { "Building dataset version has different metadata" }
            require(jdbc.queryForObject("SELECT count(*) FROM building_shade_footprint WHERE dataset_id=?", Int::class.java, dataset.dataVersion) == dataset.buildings.size)
            dataset.buildings.forEach { building ->
                require(jdbc.queryForObject(
                    """SELECT height_meters IS NOT DISTINCT FROM ?::float8 AND height_source=?
                        AND source IS NOT DISTINCT FROM ?::text AND source_id IS NOT DISTINCT FROM ?::text
                        AND height_source_reference IS NOT DISTINCT FROM ?::text AND height_source_id IS NOT DISTINCT FROM ?::text
                        AND ST_Equals(footprint,ST_Transform(ST_GeomFromText(?,4326),5179))
                        FROM building_shade_footprint WHERE dataset_id=? AND id=?""".trimIndent(),
                    Boolean::class.java, building.heightMeters, building.heightSource.name, building.source, building.sourceId,
                    building.heightSourceReference, building.heightSourceId, multiPolygonWkt(building.polygons), dataset.dataVersion, building.id,
                ) == true) { "Building dataset version has different footprints, heights or provenance" }
            }
        } else {
            jdbc.update(
                """INSERT INTO building_shade_dataset(id,area_id,source,source_timestamp,attribution,checksum,inventory_complete,
                    maximum_height_meters,building_count,known_height_count,coverage) VALUES (?,?,?,?,?,?,?,?,?,?,ST_GeomFromText(?,4326))""".trimIndent(),
                dataset.dataVersion, dataset.areaId, dataset.source, Timestamp.from(dataset.sourceTimestamp), dataset.attribution, dataset.checksum,
                dataset.inventoryComplete, dataset.maximumHeightMeters, dataset.buildings.size, knownCount, coverage,
            )
            jdbc.batchUpdate(
                """INSERT INTO building_shade_footprint(dataset_id,id,footprint,height_meters,height_source,source,source_id,height_source_reference,height_source_id)
                    VALUES (?,?,ST_Transform(ST_GeomFromText(?,4326),5179),?,?,?,?,?,?)""".trimIndent(), dataset.buildings, 250,
            ) { statement, building ->
                statement.setString(1, dataset.dataVersion)
                statement.setString(2, building.id)
                statement.setString(3, multiPolygonWkt(building.polygons))
                if (building.heightMeters == null) statement.setNull(4, java.sql.Types.DOUBLE) else statement.setDouble(4, building.heightMeters)
                statement.setString(5, building.heightSource.name)
                statement.setString(6, building.source)
                statement.setString(7, building.sourceId)
                statement.setString(8, building.heightSourceReference)
                statement.setString(9, building.heightSourceId)
            }
        }
        jdbc.update("""INSERT INTO building_shade_area(area_id,active_dataset_id) VALUES (?,?)
            ON CONFLICT(area_id) DO UPDATE SET active_dataset_id=EXCLUDED.active_dataset_id""".trimIndent(), dataset.areaId, dataset.dataVersion)
        BuildingShadeImportSummary(dataset.dataVersion, dataset.buildings.size, knownCount, reused)
    }
}
