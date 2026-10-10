package com.igilro.server.health.domain

/** Reads whether the spatial database required by the service is usable. */
fun interface HealthRepository {
    fun isSpatialDatabaseReady(): Boolean
}
