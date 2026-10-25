package com.igilro.server.place.api

import com.igilro.server.place.application.SearchPlacesUseCase
import com.igilro.server.place.domain.*
import com.igilro.server.shared.api.RequestIdFilter
import com.igilro.server.shared.api.error.GlobalExceptionHandler
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

class PlaceControllerTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), ZoneOffset.UTC)
    private fun mvc(provider: PlaceSearchProvider) = MockMvcBuilders.standaloneSetup(PlaceController(SearchPlacesUseCase(provider)))
        .setControllerAdvice(PlaceExceptionHandler(clock), GlobalExceptionHandler(clock))
        .addFilters<StandaloneMockMvcBuilder>(RequestIdFilter()).build()

    @Test
    fun `query alone returns the Flutter contract with numeric longitude latitude and source attribution`() {
        val result = PlaceSearchResult(listOf(Place("test:opaque-id", "합성 장소", "합성 주소", PlaceCoordinate(127.0374, 37.5445))),
            listOf(PlaceAttribution("fixture source", "https://example.org/copyright")))
        val response = mvc { query, _ -> assertEquals("서울숲", query); result }
            .perform(get("/api/v1/places/search").param("query", "서울숲").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().exists(RequestIdFilter.HEADER))
            .andExpect(jsonPath("$.places[0].id").value("test:opaque-id"))
            .andExpect(jsonPath("$.places[0].name").value("합성 장소"))
            .andExpect(jsonPath("$.places[0].address").value("합성 주소"))
            .andExpect(jsonPath("$.places[0].coordinate.longitude").value(127.0374))
            .andExpect(jsonPath("$.places[0].coordinate.latitude").value(37.5445))
            .andExpect(jsonPath("$.attributions[0].text").value("fixture source"))
            .andExpect(jsonPath("$.results").doesNotExist()).andReturn().response
        val coordinate = JsonMapper.builder().build().readTree(response.contentAsByteArray)["places"][0]["coordinate"]
        assertTrue(coordinate["longitude"].isNumber && coordinate["latitude"].isNumber)
    }

    @Test
    fun `no matches is exactly a 200 places empty array`() {
        val response = mvc { _, _ -> PlaceSearchResult(emptyList()) }
            .perform(get("/api/v1/places/search").param("query", "없는 후보"))
            .andExpect(status().isOk).andReturn().response
        assertEquals("""{"places":[]}""", response.contentAsString)
    }

    @Test
    fun `missing blank and overlong queries return 400 before the provider is called`() {
        var calls = 0
        val mvc = mvc { _, _ -> calls++; PlaceSearchResult(emptyList()) }
        val missing = mvc.perform(get("/api/v1/places/search")).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST")).andReturn().response
        assertNotNull(UUID.fromString(missing.getHeader(RequestIdFilter.HEADER)))
        listOf("", "  \u00a0\t", "가".repeat(101)).forEach { value ->
            mvc.perform(get("/api/v1/places/search").param("query", value))
                .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("query"))
        }
        assertEquals(0, calls)
    }

    @Test
    fun `missing address remains a required empty string`() {
        mvc { _, _ -> PlaceSearchResult(listOf(Place("test:1", "합성 장소", "", PlaceCoordinate(127.1, 37.5)))) }
            .perform(get("/api/v1/places/search").param("query", "서울숲"))
            .andExpect(status().isOk).andExpect(jsonPath("$.places[0].address").value(""))
    }

    @Test
    fun `all service and provider failures preserve stable codes top level fields and request ID`() {
        val outcomes = mapOf(
            PlaceSearchFailureReason.NOT_CONFIGURED to (503 to "PLACE_SEARCH_NOT_CONFIGURED"),
            PlaceSearchFailureReason.PROVIDER_AUTH_FAILED to (503 to "PLACE_SEARCH_PROVIDER_AUTH_FAILED"),
            PlaceSearchFailureReason.TIMEOUT to (504 to "PLACE_SEARCH_TIMEOUT"),
            PlaceSearchFailureReason.UNAVAILABLE to (502 to "PLACE_SEARCH_UNAVAILABLE"),
            PlaceSearchFailureReason.BAD_RESPONSE to (502 to "PLACE_SEARCH_BAD_RESPONSE"),
            PlaceSearchFailureReason.RATE_LIMITED to (429 to "TOO_MANY_REQUESTS"),
        )
        outcomes.forEach { (reason, outcome) ->
            val requestId = UUID.randomUUID().toString()
            mvc { _, _ -> throw PlaceSearchFailure(reason) }
                .perform(get("/api/v1/places/search").param("query", "서울숲").header(RequestIdFilter.HEADER, requestId))
                .andExpect(status().`is`(outcome.first))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(outcome.second))
                .andExpect(jsonPath("$.message").isString)
                .andExpect(jsonPath("$.requestId").value(requestId))
                .andExpect(jsonPath("$.timestamp").value("2026-10-25T00:00:00Z"))
                .andExpect(jsonPath("$.errors").isEmpty)
                .andExpect(jsonPath("$.places").doesNotExist())
                .andExpect(header().string(RequestIdFilter.HEADER, requestId))
        }
    }

    @Test
    fun `rate limiting returns retry after and the common error envelope`() {
        mvc { _, _ -> throw PlaceSearchFailure(PlaceSearchFailureReason.RATE_LIMITED, 1) }
            .perform(get("/api/v1/places/search").param("query", "서울숲"))
            .andExpect(status().isTooManyRequests).andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
            .andExpect(header().string("Retry-After", "1"))
    }

    @Test
    fun `non JSON accept receives a common 406 without calling the supplier`() {
        var calls = 0
        mvc { _, _ -> calls++; PlaceSearchResult(emptyList()) }
            .perform(get("/api/v1/places/search").param("query", "서울숲").accept(MediaType.TEXT_PLAIN))
            .andExpect(status().isNotAcceptable).andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"))
        assertEquals(0, calls)
    }

    @Test
    fun `domain rejects invalid coordinates before serialization`() {
        listOf(Double.NaN to 37.5, Double.POSITIVE_INFINITY to 37.5, 181.0 to 37.5,
            127.0 to -91.0, 127.0 to Double.NEGATIVE_INFINITY).forEach { (lon, lat) ->
            assertFailsWith<IllegalArgumentException> { PlaceCoordinate(lon, lat) }
        }
        PlaceCoordinate(-180.0, -90.0)
        PlaceCoordinate(180.0, 90.0)
    }
}
