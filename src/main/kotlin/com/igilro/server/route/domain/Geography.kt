package com.igilro.server.route.domain

import kotlin.math.*

data class Coordinate(val longitude: Double, val latitude: Double) {
    init {
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(latitude.isFinite() && latitude in -90.0..90.0)
    }

    fun distanceTo(other: Coordinate): Double {
        val latitudeDelta = Math.toRadians(other.latitude - latitude)
        val longitudeDelta = Math.toRadians(other.longitude - longitude)
        val value = sin(latitudeDelta / 2).pow(2) + cos(Math.toRadians(latitude)) *
            cos(Math.toRadians(other.latitude)) * sin(longitudeDelta / 2).pow(2)
        return 6_371_008.8 * 2 * asin(sqrt(value.coerceIn(0.0, 1.0)))
    }
}

data class Bounds(val west: Double, val south: Double, val east: Double, val north: Double) {
    init {
        Coordinate(west, south)
        Coordinate(east, north)
        require(west < east && south < north)
        require(east - west <= 0.1 && north - south <= 0.1) { "Pilot area must be small and bounded" }
    }

    fun contains(point: Coordinate) = point.longitude in west..east && point.latitude in south..north
}
