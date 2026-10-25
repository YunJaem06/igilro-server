package com.igilro.server.place.api

import com.igilro.server.place.domain.PlaceSearchResult

object PlaceSearchMapper {
    fun toResponse(result: PlaceSearchResult) = PlaceSearchResponse(
        places = result.places.map {
            PlaceResponse(it.id, it.name, it.address, PlaceCoordinateResponse(it.coordinate.longitude, it.coordinate.latitude))
        },
        attributions = result.attributions.map { PlaceAttributionResponse(it.text, it.url) },
    )
}
