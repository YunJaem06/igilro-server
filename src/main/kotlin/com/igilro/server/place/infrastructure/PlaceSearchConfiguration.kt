package com.igilro.server.place.infrastructure

import com.igilro.server.place.application.SearchPlacesUseCase
import com.igilro.server.place.domain.PlaceSearchProvider
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.http.HttpClient
import java.time.Duration

// Not a data class: a generated toString must never expose the API key.
@ConfigurationProperties("igilro.places")
class PlaceSearchProperties(
    val apiKey: String = "",
    val connectTimeout: Duration = Duration.ofSeconds(2),
    val responseTimeout: Duration = Duration.ofSeconds(3),
    val requestsPerSecond: Int = 4,
) {
    init {
        require(connectTimeout.toMillis() in 1..10_000) { "Place connect timeout must be 1ms..10s" }
        require(responseTimeout.toMillis() in 1..10_000) { "Place response timeout must be 1ms..10s" }
        require(requestsPerSecond in 1..1000) { "Place request limit must be 1..1000 per second" }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PlaceSearchProperties::class)
class PlaceSearchConfiguration {
    @Bean(destroyMethod = "close")
    fun placeSearchHttpClient(properties: PlaceSearchProperties): HttpClient =
        HttpClient.newBuilder().connectTimeout(properties.connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build()

    @Bean
    fun placeSearchProvider(properties: PlaceSearchProperties, @Qualifier("placeSearchHttpClient") client: HttpClient): PlaceSearchProvider = GeoapifyPlaceSearchProvider(
        properties,
        client,
        PlaceSearchRateLimiter(properties.requestsPerSecond),
    )

    @Bean
    fun searchPlacesUseCase(provider: PlaceSearchProvider) = SearchPlacesUseCase(provider)
}
