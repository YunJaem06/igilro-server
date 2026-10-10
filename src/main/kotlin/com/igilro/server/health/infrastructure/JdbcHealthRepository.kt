package com.igilro.server.health.infrastructure

import com.igilro.server.health.domain.HealthRepository
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class JdbcHealthRepository(private val jdbcTemplate: JdbcTemplate) : HealthRepository {
    override fun isSpatialDatabaseReady(): Boolean = try {
        jdbcTemplate.queryForObject(
            "SELECT ST_SRID(ST_SetSRID(ST_MakePoint(0, 0), 4326)) = 4326",
            Boolean::class.java,
        ) == true
    } catch (_: DataAccessException) {
        false
    }
}
