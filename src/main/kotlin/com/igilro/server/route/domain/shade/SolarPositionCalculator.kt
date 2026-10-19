package com.igilro.server.route.domain.shade

import com.igilro.server.route.domain.Coordinate
import java.time.Instant
import java.time.DateTimeException
import java.time.ZoneOffset
import kotlin.math.*

data class SolarPosition(val altitudeDegrees: Double, val azimuthDegrees: Double) {
    init {
        require(altitudeDegrees.isFinite() && altitudeDegrees in -90.0..90.0)
        require(azimuthDegrees.isFinite() && azimuthDegrees >= 0 && azimuthDegrees < 360)
    }

    // Azimuth is clockwise from true north. Shadows extend away from the sun.
    fun shadowOffset(heightMeters: Double): Pair<Double, Double> {
        require(altitudeDegrees >= 5.0 && heightMeters.isFinite() && heightMeters > 0)
        val length = heightMeters / tan(Math.toRadians(altitudeDegrees))
        val bearing = Math.toRadians(azimuthDegrees)
        return -length * sin(bearing) to -length * cos(bearing)
    }
}

class SolarPositionCalculator {
    // NOAA's published fractional-year approximation, without atmospheric refraction.
    // https://gml.noaa.gov/grad/solcalc/solareqns.PDF
    fun calculate(position: Coordinate, instant: Instant): SolarPosition? {
        val time = try { instant.atOffset(ZoneOffset.UTC) } catch (_: DateTimeException) { return null }
        if (time.year !in 2000..2100) return null
        val hour = time.hour + time.minute / 60.0 + time.second / 3600.0 + time.nano / 3.6e12
        val gamma = 2 * PI / time.toLocalDate().lengthOfYear() * (time.dayOfYear - 1 + (hour - 12) / 24)
        val equation = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
            0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
        val declination = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
            0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) -
            0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
        val minutes = ((hour * 60 + equation + 4 * position.longitude) % 1440 + 1440) % 1440
        val angle = Math.toRadians(minutes / 4 - 180)
        val latitude = Math.toRadians(position.latitude)
        val altitude = asin((sin(latitude) * sin(declination) + cos(latitude) * cos(declination) * cos(angle)).coerceIn(-1.0, 1.0))
        val azimuth = atan2(sin(angle), cos(angle) * sin(latitude) - tan(declination) * cos(latitude)) + PI
        return SolarPosition(Math.toDegrees(altitude), (Math.toDegrees(azimuth) + 360) % 360)
    }
}
