package com.igilro.server.route.infrastructure

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("igilro.route-search")
class RouteSearchProperties(
    val requestsPerSecond: Int = 4,
    val burstCapacity: Int = 4,
    val maxConcurrentSearches: Int = 2,
)
