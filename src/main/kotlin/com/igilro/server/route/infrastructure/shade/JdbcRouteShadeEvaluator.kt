package com.igilro.server.route.infrastructure.shade

import com.igilro.server.route.application.RouteShadeEvaluator
import com.igilro.server.route.domain.Coordinate
import com.igilro.server.route.domain.shade.*
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class JdbcRouteShadeEvaluator(private val jdbc: JdbcTemplate) : RouteShadeEvaluator {
    private val solar = SolarPositionCalculator()
    private val log = LoggerFactory.getLogger(javaClass)

    override fun evaluate(areaId: String, coordinates: List<Coordinate>, evaluatedAt: Instant): ShadeEvaluation {
        require(coordinates.size >= 2)
        val center = Coordinate((coordinates.minOf { it.longitude } + coordinates.maxOf { it.longitude }) / 2,
            (coordinates.minOf { it.latitude } + coordinates.maxOf { it.latitude }) / 2)
        val sun = solar.calculate(center, evaluatedAt) ?: return ShadeEvaluation(ShadeStatus.UNSUPPORTED_TIME)
        if (sun.altitudeDegrees <= 0) return ShadeEvaluation(ShadeStatus.NIGHT, solarAltitudeDegrees = sun.altitudeDegrees)
        if (sun.altitudeDegrees < 5) return ShadeEvaluation(ShadeStatus.LOW_SUN, solarAltitudeDegrees = sun.altitudeDegrees)
        val (east, north) = sun.shadowOffset(1.0)
        val wkt = "LINESTRING(" + coordinates.joinToString(",") { "${it.longitude} ${it.latitude}" } + ")"
        return try {
            jdbc.query(SQL, { row, _ ->
                val version = row.getString("id")
                val attribution = row.getString("attribution")
                if (!row.getBoolean("data_valid")) {
                    ShadeEvaluation(ShadeStatus.DATA_UNAVAILABLE, solarAltitudeDegrees = sun.altitudeDegrees)
                } else {
                    val total = row.getDouble("total_length")
                    val covered = row.getDouble("covered_length")
                    val shaded = row.getDouble("shaded_length")
                    if (!total.isFinite() || total <= 0 || !covered.isFinite() || !shaded.isFinite()) {
                        ShadeEvaluation(ShadeStatus.DATA_UNAVAILABLE, solarAltitudeDegrees = sun.altitudeDegrees)
                    } else {
                        val complete = total - covered <= maxOf(1e-6, total * 1e-9)
                        ShadeEvaluation(if (complete) ShadeStatus.AVAILABLE else ShadeStatus.INCOMPLETE_COVERAGE,
                            if (complete) (shaded / total).coerceIn(0.0, 1.0) else null,
                            if (complete) 1.0 else (covered / total).coerceIn(0.0, 1.0), version, attribution, sun.altitudeDegrees)
                    }
                }
            }, wkt, center.longitude, center.latitude, east, north, areaId).singleOrNull()
                ?: ShadeEvaluation(ShadeStatus.DATA_UNAVAILABLE, solarAltitudeDegrees = sun.altitudeDegrees)
        } catch (ex: DataAccessException) {
            // Shade is optional; a missing/broken environmental dataset does not remove a valid fastest route.
            log.warn("건물 그늘 평가 자료를 읽거나 계산하지 못했습니다", ex)
            ShadeEvaluation(ShadeStatus.DATA_UNAVAILABLE, solarAltitudeDegrees = sun.altitudeDegrees)
        }
    }

    companion object {
        private val SQL = """
            WITH input AS (
                SELECT ST_Transform(ST_GeomFromText(?,4326),5179) AS route,
                       ST_SetSRID(ST_MakePoint(?,?),4326)::geography AS origin, ?::float8 AS east, ?::float8 AS north
            ), basis AS (
                SELECT *, ST_Transform(origin::geometry,5179) AS p,
                    ST_Transform(ST_Project(origin,1,pi()/2)::geometry,5179) AS e,
                    ST_Transform(ST_Project(origin,1,0)::geometry,5179) AS n FROM input
            ), params AS (
                SELECT route, east*(ST_X(e)-ST_X(p))+north*(ST_X(n)-ST_X(p)) AS dx,
                    east*(ST_Y(e)-ST_Y(p))+north*(ST_Y(n)-ST_Y(p)) AS dy FROM basis
            ), dataset AS MATERIALIZED (
                SELECT d.*, p.*, sqrt(dx*dx+dy*dy)*maximum_height_meters*1.001 AS reach,
                    a.area_id=d.area_id AND counts.total=building_count AND counts.known=known_height_count
                    AND coalesce(counts.max_height,0)<=maximum_height_meters AS data_valid
                FROM building_shade_area a JOIN building_shade_dataset d ON d.id=a.active_dataset_id
                CROSS JOIN params p CROSS JOIN LATERAL (
                    SELECT count(*) AS total, count(height_meters) AS known, max(height_meters) AS max_height
                    FROM building_shade_footprint WHERE dataset_id=d.id
                ) counts WHERE a.area_id=?
            ), nearby AS MATERIALIZED (
                SELECT b.*, dx, dy, maximum_height_meters FROM dataset d
                JOIN building_shade_footprint b ON b.dataset_id=d.id AND ST_DWithin(b.footprint,d.route,d.reach)
            ), shadows AS (
                SELECT coalesce(ST_UnaryUnion(ST_Collect(igilro_building_shadow(footprint,dx*height_meters,dy*height_meters))
                    FILTER (WHERE height_meters IS NOT NULL)),ST_GeomFromText('POLYGON EMPTY',5179)) AS known,
                    coalesce(ST_UnaryUnion(ST_Collect(igilro_building_shadow(footprint,dx*maximum_height_meters,dy*maximum_height_meters))
                    FILTER (WHERE height_meters IS NULL)),ST_GeomFromText('POLYGON EMPTY',5179)) AS unknown FROM nearby
            ), mask AS (
                SELECT d.*, s.known,
                    CASE WHEN inventory_complete THEN ST_Difference(ST_Buffer(ST_Transform(coverage,5179),-reach),s.unknown)
                         ELSE ST_GeomFromText('POLYGON EMPTY',5179) END AS covered FROM dataset d CROSS JOIN shadows s
            )
            SELECT id,attribution,data_valid,
                sum(ST_Length(segment.geom)) AS total_length,
                sum(ST_Length(ST_Intersection(segment.geom,covered))) AS covered_length,
                sum(ST_Length(ST_Intersection(segment.geom,known))) AS shaded_length
            FROM mask CROSS JOIN LATERAL ST_DumpSegments(route) segment
            GROUP BY id,attribution,data_valid
        """.trimIndent()
    }
}
