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

class KakaoPlaceSearchProvider(
    private val properties: PlaceSearchProperties,
    private val client: HttpClient,
    private val limiter: PlaceSearchRateLimiter,
    private val endpoint: URI = URI("https://dapi.kakao.com"),
) : PlaceSearchProvider {
    private val mapper = JsonMapper.builder().build()

    override fun search(query: String, limit: Int): PlaceSearchResult {
        val key = properties.apiKey.trim()
        if (key.isEmpty() || key.any(Char::isWhitespace) || key.any(Char::isISOControl)) fail(PlaceSearchFailureReason.NOT_CONFIGURED)
        limiter.acquire()
        val deadline = System.nanoTime() + properties.responseTimeout.toNanos()
        val keywords = fetch(query, limit, key, deadline, address = false)
        val places = if (keywords.isNotEmpty()) keywords else fetch(query, limit, key, deadline, address = true)
        return PlaceSearchResult(places, if (places.isEmpty()) emptyList() else listOf(
            PlaceAttribution("장소 정보: 카카오맵", "https://map.kakao.com/"),
        ))
    }

    private fun fetch(query: String, limit: Int, key: String, deadline: Long, address: Boolean): List<Place> {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) fail(PlaceSearchFailureReason.TIMEOUT)
        val params = linkedMapOf("query" to query, "size" to limit.toString(), "page" to "1")
        val path = if (address) "/v2/local/search/address.json" else "/v2/local/search/keyword.json"
        val uri = URI(endpoint.resolve(path).toString() + "?" + params.entries.joinToString("&") { (name, value) ->
            "$name=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
        })
        val request = HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofNanos(remaining))
            .header("Authorization", "KakaoAK $key")
            .header("Accept", "application/json").header("User-Agent", "igilro-server/place-search").GET().build()
        val pending = client.sendAsync(request, HttpResponse.BodyHandler { LimitedBodySubscriber() })
        val response = try {
            // Completion includes the entire body, so slow body delivery is bounded too.
            pending.get(remaining, TimeUnit.NANOSECONDS)
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
            KakaoPlaceSearchResponse.fromJson(mapper.readTree(response.body()), limit, address)
                .places.map { Place(it.id, it.name, it.address, PlaceCoordinate(it.longitude, it.latitude)) }.distinctBy { it.id }
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
