package com.igilro.server.place.application

import com.igilro.server.place.domain.PlaceSearchFailure
import com.igilro.server.place.domain.PlaceSearchFailureReason
import com.igilro.server.place.domain.PlaceSearchProvider
import com.igilro.server.place.domain.PlaceSearchResult

class SearchPlacesUseCase(private val provider: PlaceSearchProvider) {
    fun execute(query: String): PlaceSearchResult {
        if (query.length > MAX_RAW_QUERY_LENGTH) invalidQuery()
        val normalized = WHITESPACE.replace(query, " ").trim()
        if (normalized.codePointCount(0, normalized.length) !in 1..MAX_QUERY_LENGTH || normalized.any(Char::isISOControl)) invalidQuery()
        val result = provider.search(normalized, MAX_RESULTS)
        return result.copy(places = result.places.take(MAX_RESULTS), attributions = if (result.places.isEmpty()) emptyList() else result.attributions)
    }

    private fun invalidQuery(): Nothing = throw PlaceSearchFailure(PlaceSearchFailureReason.INVALID_QUERY)

    companion object {
        const val MAX_QUERY_LENGTH = 100
        const val MAX_RAW_QUERY_LENGTH = 1024
        const val MAX_RESULTS = 10
        private val WHITESPACE = Regex("(?U)\\s+")
    }
}
