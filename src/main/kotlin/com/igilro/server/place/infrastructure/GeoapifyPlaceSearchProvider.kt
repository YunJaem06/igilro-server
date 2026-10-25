package com.igilro.server.place.infrastructure

import com.igilro.server.place.domain.*
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class GeoapifyPlaceSearchProvider(
    private val properties: PlaceSearchProperties,
    private val client: HttpClient,
    private val limiter: PlaceSearchRateLimiter,
    private val endpoint: URI = URI("https://api.geoapify.com/v1/geocode/search"),
) : PlaceSearchProvider {
    private val mapper = JsonMapper.builder().build()

    override fun search(query: String, limit: Int): PlaceSearchResult {
        val key = properties.apiKey.trim()
        if (key.isEmpty() || key.any(Char::isWhitespace) || key.any(Char::isISOControl)) fail(PlaceSearchFailureReason.NOT_CONFIGURED)
        limiter.acquire()
        val params = linkedMapOf("text" to query, "format" to "json", "filter" to "countrycode:kr",
            "bias" to "countrycode:none", "lang" to "ko", "limit" to limit.toString(), "apiKey" to key)
        val uri = URI(endpoint.toString() + "?" + params.entries.joinToString("&") { (name, value) ->
            "$name=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
        })
        val request = HttpRequest.newBuilder(uri).timeout(properties.responseTimeout)
            .header("Accept", "application/json").header("User-Agent", "igilro-server/place-search").GET().build()
        val pending = client.sendAsync(request, HttpResponse.BodyHandler { LimitedBodySubscriber() })
        val response = try {
            // Completion includes the entire body, so slow body delivery is bounded too.
            pending.get(properties.responseTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            pending.cancel(true)
            fail(PlaceSearchFailureReason.TIMEOUT)
        } catch (_: InterruptedException) {
            pending.cancel(true)
            Thread.currentThread().interrupt()
            fail(PlaceSearchFailureReason.UNAVAILABLE)
        } catch (error: ExecutionException) {
            val causes = generateSequence(error.cause) { it.cause }.take(10).toList()
            when {
                causes.any { it is HttpTimeoutException } -> fail(PlaceSearchFailureReason.TIMEOUT)
                causes.any { it is ResponseTooLarge } -> fail(PlaceSearchFailureReason.BAD_RESPONSE)
                else -> fail(PlaceSearchFailureReason.UNAVAILABLE)
            }
        }
        when (response.statusCode()) {
            200 -> Unit
            401, 403 -> fail(PlaceSearchFailureReason.PROVIDER_AUTH_FAILED)
            429 -> throw PlaceSearchFailure(PlaceSearchFailureReason.RATE_LIMITED,
                response.headers().firstValue("Retry-After").orElse("").toLongOrNull()?.takeIf { it in 1..86400 })
            in 500..599 -> fail(PlaceSearchFailureReason.UNAVAILABLE)
            else -> fail(PlaceSearchFailureReason.BAD_RESPONSE)
        }
        if (response.headers().firstValue("Content-Type").orElse("").substringBefore(';').trim().lowercase() != "application/json") {
            fail(PlaceSearchFailureReason.BAD_RESPONSE)
        }
        return try {
            val data = GeoapifyResponse.fromJson(mapper.readTree(response.body()), limit)
            val places = data.results.map { item ->
                val name = sequenceOf(item.name, item.addressLine1, item.formatted).filterNotNull().firstOrNull { it.isNotBlank() }?.trim()
                    ?: fail(PlaceSearchFailureReason.BAD_RESPONSE)
                Place("geoapify:${item.placeId}", name, item.formatted?.trim().orEmpty(), PlaceCoordinate(item.longitude, item.latitude))
            }.distinctBy { it.id }
            val attributions = if (places.isEmpty()) emptyList() else (listOf(
                PlaceAttribution("Powered by Geoapify", "https://www.geoapify.com/"),
                PlaceAttribution("© OpenStreetMap contributors", "https://www.openstreetmap.org/copyright"),
            ) + data.results.mapNotNull { it.attribution?.let { source -> PlaceAttribution(source.text, source.url) } }).distinct()
            PlaceSearchResult(places, attributions)
        } catch (failure: PlaceSearchFailure) {
            throw failure
        } catch (_: RuntimeException) {
            // Jackson diagnostics can contain the response text. Do not propagate them as causes.
            fail(PlaceSearchFailureReason.BAD_RESPONSE)
        }
    }

    private fun fail(reason: PlaceSearchFailureReason): Nothing = throw PlaceSearchFailure(reason)

    private class ResponseTooLarge : IOException("Place response exceeds size limit")

    private class LimitedBodySubscriber : HttpResponse.BodySubscriber<ByteArray> {
        private val delegate = HttpResponse.BodySubscribers.ofByteArray()
        private var subscription: Flow.Subscription? = null
        private var received = 0L
        override fun getBody(): CompletionStage<ByteArray> = delegate.body
        override fun onSubscribe(value: Flow.Subscription) { subscription = value; delegate.onSubscribe(value) }
        override fun onNext(items: List<ByteBuffer>) {
            received += items.sumOf { it.remaining().toLong() }
            if (received > MAX_RESPONSE_BYTES) {
                subscription?.cancel()
                delegate.onError(ResponseTooLarge())
            } else delegate.onNext(items)
        }
        override fun onError(error: Throwable) = delegate.onError(error)
        override fun onComplete() = delegate.onComplete()
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 256 * 1024
    }
}
