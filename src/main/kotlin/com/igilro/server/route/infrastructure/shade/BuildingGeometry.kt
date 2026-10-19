package com.igilro.server.route.infrastructure.shade

import com.igilro.server.route.domain.shade.FootprintPolygon

internal fun multiPolygonWkt(polygons: List<FootprintPolygon>) = "MULTIPOLYGON(" + polygons.joinToString(",") { polygon ->
    "(" + polygon.rings.joinToString(",") { ring ->
        "(" + ring.joinToString(",") { "${it.longitude} ${it.latitude}" } + ")"
    } + ")"
} + ")"
