package com.igilro.server.route.domain

import com.igilro.server.route.domain.shade.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.*

class SolarPositionCalculatorTest {
    private val calculator = SolarPositionCalculator()
    private val seoul = Coordinate(127.04, 37.54)

    @Test
    fun `summer noon elevation agrees with latitude and solstice declination`() {
        val sun = calculator.calculate(Coordinate(-105.0, 40.0), Instant.parse("2026-06-21T19:00:00Z"))!!
        assertEquals(73.45, sun.altitudeDegrees, 0.3)
        assertEquals(180.0, sun.azimuthDegrees, 3.0)
    }

    @Test
    fun `morning and afternoon sun cast shadows in opposite east west directions`() {
        val morning = calculator.calculate(seoul, Instant.parse("2026-06-21T00:00:00Z"))!!
        val afternoon = calculator.calculate(seoul, Instant.parse("2026-06-21T07:00:00Z"))!!
        assertTrue(morning.altitudeDegrees > 5 && afternoon.altitudeDegrees > 5)
        assertTrue(morning.shadowOffset(20.0).first < 0)
        assertTrue(afternoon.shadowOffset(20.0).first > 0)
        assertTrue(calculator.calculate(seoul, Instant.parse("2026-06-21T15:00:00Z"))!!.altitudeDegrees < 0)
    }

    @Test
    fun `offset representations of the same instant give identical solar positions`() {
        val utc = Instant.parse("2026-10-10T05:00:00Z")
        val local = OffsetDateTime.parse("2026-10-10T14:00:00+09:00").toInstant()
        assertEquals(calculator.calculate(seoul, utc), calculator.calculate(seoul, local))
        assertNotNull(calculator.calculate(seoul, Instant.parse("2024-02-29T03:00:00Z")))
        assertNull(calculator.calculate(seoul, Instant.parse("2200-01-01T00:00:00Z")))
        assertNull(calculator.calculate(seoul, Instant.MAX))
    }

    @Test
    fun `shadow lengths use height over tangent of solar elevation`() {
        val south = SolarPosition(45.0, 180.0).shadowOffset(10.0)
        assertEquals(0.0, south.first, 1e-9)
        assertEquals(10.0, south.second, 1e-9)
        assertFailsWith<IllegalArgumentException> { SolarPosition(1.0, 180.0).shadowOffset(10.0) }
    }

    @Test
    fun `unobserved values cannot masquerade as zero shade`() {
        assertFailsWith<IllegalArgumentException> { ShadeEvaluation(ShadeStatus.DATA_UNAVAILABLE, ratio = 0.0) }
        assertFailsWith<IllegalArgumentException> { ShadeEvaluation(ShadeStatus.AVAILABLE, 0.4, 0.5, "dataset") }
        assertFailsWith<IllegalArgumentException> { ShadeEvaluation(ShadeStatus.INCOMPLETE_COVERAGE, coverage = Double.NaN) }
    }
}
