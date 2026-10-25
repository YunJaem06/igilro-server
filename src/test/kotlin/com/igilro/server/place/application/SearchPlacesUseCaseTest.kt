package com.igilro.server.place.application

import com.igilro.server.place.domain.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class SearchPlacesUseCaseTest {
    @Test
    fun `normalizes Korean unicode whitespace without changing special characters`() {
        var received = ""
        val useCase = SearchPlacesUseCase { query, limit ->
            received = query
            assertEquals(10, limit)
            PlaceSearchResult(emptyList())
        }
        useCase.execute(" \t서울숲\n\u00a0\u0085  카페 & A+B / #1?% ")
        assertEquals("서울숲 카페 & A+B / #1?%", received)
    }

    @Test
    fun `blank overlong raw and control character inputs never call the provider`() {
        var calls = 0
        val useCase = SearchPlacesUseCase { _, _ -> calls++; PlaceSearchResult(emptyList()) }
        listOf("", " \r\n\t\u00a0\u3000", "가".repeat(101), " ".repeat(1025), "서울\u0000숲").forEach { query ->
            assertEquals(PlaceSearchFailureReason.INVALID_QUERY, assertFailsWith<PlaceSearchFailure> { useCase.execute(query) }.reason)
        }
        assertEquals(0, calls)
    }

    @Test
    fun `query length counts Unicode code points after whitespace normalization`() {
        val useCase = SearchPlacesUseCase { _, _ -> PlaceSearchResult(emptyList()) }
        useCase.execute("  " + "가".repeat(100) + "  ")
        useCase.execute("🌳".repeat(100))
        assertFailsWith<PlaceSearchFailure> { useCase.execute("🌳".repeat(101)) }
    }

    @Test
    fun `caps results and clears attribution only for empty results`() {
        val place = Place("test:1", "합성 후보", "", PlaceCoordinate(127.1, 37.5))
        val attributions = listOf(PlaceAttribution("fixture", null))
        val many = SearchPlacesUseCase { _, _ -> PlaceSearchResult(List(12) { place }, attributions) }.execute("서울숲")
        assertEquals(10, many.places.size)
        assertEquals(attributions, many.attributions)
        val empty = SearchPlacesUseCase { _, _ -> PlaceSearchResult(emptyList(), attributions) }.execute("서울숲")
        assertEquals(PlaceSearchResult(emptyList()), empty)
    }

    @Test
    fun `provider failure is never replaced with empty results`() {
        val failure = PlaceSearchFailure(PlaceSearchFailureReason.TIMEOUT)
        assertSame(failure, assertFailsWith<PlaceSearchFailure> { SearchPlacesUseCase { _, _ -> throw failure }.execute("서울숲") })
    }
}
