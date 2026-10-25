package com.igilro.server.place.domain

data class PlaceCoordinate(val longitude: Double, val latitude: Double) {
    init {
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(latitude.isFinite() && latitude in -90.0..90.0)
    }
}

data class Place(val id: String, val name: String, val address: String, val coordinate: PlaceCoordinate) {
    init {
        require(id.isNotBlank() && ':' in id)
        require(name.isNotBlank())
    }
}

data class PlaceAttribution(val text: String, val url: String?)

data class PlaceSearchResult(val places: List<Place>, val attributions: List<PlaceAttribution> = emptyList())

fun interface PlaceSearchProvider {
    fun search(query: String, limit: Int): PlaceSearchResult
}
