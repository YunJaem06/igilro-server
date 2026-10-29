package com.igilro.server.place.infrastructure

import com.igilro.server.place.application.SearchPlacesUseCase
import com.igilro.server.place.domain.*
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class KakaoPlaceSearchProviderTest {
    private lateinit var server: HttpServer
    private lateinit var client: HttpClient
    private val workers = Executors.newCachedThreadPool { task -> Thread(task).apply { isDaemon = true } }
    private val recorded = AtomicReference<URI>()
    private val authorization = AtomicReference<String>()
    private val paths = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val requests = AtomicInteger()
    @Volatile private var handler: (HttpExchange) -> Unit = { respond(it, 200, SUCCESS) }

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = workers
        server.createContext("/v2/local/search/") { exchange ->
            recorded.set(exchange.requestURI)
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            paths.add(exchange.requestURI.path)
            requests.incrementAndGet()
            try { handler(exchange) } catch (_: java.io.IOException) { } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            finally { exchange.close() }
        }
        server.start()
        client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(250)).followRedirects(HttpClient.Redirect.NEVER).build()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
        workers.shutdownNow()
        client.close()
    }

    private fun provider(key: String = FAKE_KEY, timeout: Duration = Duration.ofSeconds(2), limiter: PlaceSearchRateLimiter? = null): KakaoPlaceSearchProvider {
        val ticker = AtomicLong()
        return KakaoPlaceSearchProvider(PlaceSearchProperties(key, Duration.ofMillis(250), timeout), client,
            limiter ?: PlaceSearchRateLimiter(4) { ticker.getAndAdd(1_000_000_000) }, URI("http://127.0.0.1:${server.address.port}"))
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String, type: String = "application/json") {
        exchange.responseHeaders.set("Content-Type", type)
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    @Test
    fun `sends normalized Korean and reserved characters as one UTF8 query value`() {
        val query = "서울숲 카페 & A+B / #1?%"
        SearchPlacesUseCase(provider()).execute("  서울숲\t카페 & A+B / #1?%  ")
        val params = recorded.get().rawQuery.split('&').associate { pair ->
            val parts = pair.split('=', limit = 2)
            parts[0] to URLDecoder.decode(parts[1], Charsets.UTF_8)
        }
        assertEquals(query, params["query"])
        assertEquals("KakaoAK $FAKE_KEY", authorization.get())
        assertFalse(recorded.get().toString().contains(FAKE_KEY))
        assertEquals("10", params["size"])
        assertEquals("1", params["page"])
        assertEquals(3, params.size)
        assertEquals(listOf("/v2/local/search/keyword.json"), paths.toList())
    }

    @Test
    fun `maps supplier fields numeric strings and provenance to independent domain values`() {
        val result = provider().search("서울숲", 10)
        val place = result.places.single()
        assertEquals("kakao:place:fixture-1", place.id)
        assertEquals("합성 테스트 장소", place.name)
        assertEquals("합성 테스트 주소", place.address)
        assertEquals(127.0374, place.coordinate.longitude)
        assertEquals(37.5445, place.coordinate.latitude)
        assertEquals(listOf(PlaceAttribution("장소 정보: 카카오맵", "https://map.kakao.com/")), result.attributions)
        assertEquals(1, requests.get())
    }

    @Test
    fun `missing or null place address becomes empty string`() {
        handler = { respond(it, 200, """{"documents":[{"id":"fixture","place_name":"합성 장소","road_address_name":null,"x":127.1,"y":37.5}]}""") }
        val result = provider().search("합성 장소", 10)
        assertEquals("합성 장소", result.places.single().name)
        assertEquals("", result.places.single().address)
    }

    @Test
    fun `empty keyword results use the address endpoint and preserve building name and road address`() {
        handler = { respond(it, 200, if (it.requestURI.path.endsWith("keyword.json")) EMPTY else ADDRESS) }
        val result = provider().search("합성 주소 1", 10)
        val place = result.places.single()
        assertEquals(listOf("/v2/local/search/keyword.json", "/v2/local/search/address.json"), paths.toList())
        assertEquals("합성 건물", place.name)
        assertEquals("합성 도로명 주소 1", place.address)
        assertEquals(127.0374, place.coordinate.longitude)
        assertTrue(place.id.startsWith("kakao:address:"))
        assertEquals(2, requests.get())
    }

    @Test
    fun `address without road details uses the address as display name`() {
        handler = { respond(it, 200, if (it.requestURI.path.endsWith("keyword.json")) EMPTY else
            """{"documents":[{"address_name":"합성 지번 주소","road_address":null,"x":"127","y":"37"}]}""") }
        val place = provider().search("합성 지번 주소", 10).places.single()
        assertEquals("합성 지번 주소", place.name)
        assertEquals("합성 지번 주소", place.address)
    }

    @Test
    fun `empty provider array is a successful empty result`() {
        handler = { respond(it, 200, """{"documents":[]}""") }
        assertEquals(PlaceSearchResult(emptyList()), provider().search("없는 장소", 10))
        assertEquals(2, requests.get())
    }

    @Test
    fun `malformed provider response never becomes partial or empty success`() {
        val invalid = listOf("", "not-json", "[]", "{}", "{\"documents\":null}", "{\"documents\":{}}",
            SUCCESS.replace("127.0374", "NaN"), SUCCESS.replace("127.0374", "181"), SUCCESS.replace("37.5445", "91"),
            SUCCESS.replace("\"127.0374\"", "true"), SUCCESS.replace("\"fixture-1\"", "42"),
            SUCCESS.replace("합성 테스트 장소", " "))
        val adapter = provider()
        invalid.forEach { body ->
            handler = { respond(it, 200, body) }
            val failure = assertFailsWith<PlaceSearchFailure> { adapter.search("서울숲", 10) }
            assertEquals(PlaceSearchFailureReason.BAD_RESPONSE, failure.reason)
            assertNull(failure.cause)
        }
        assertEquals(invalid.size, requests.get())
    }

    @Test
    fun `valid candidates do not hide another invalid candidate`() {
        val valid = """{"id":"valid","place_name":"합성 후보","x":127.1,"y":37.5}"""
        handler = { respond(it, 200, """{"documents":[$valid,{}]}""") }
        assertEquals(PlaceSearchFailureReason.BAD_RESPONSE, assertFailsWith<PlaceSearchFailure> { provider().search("서울숲", 10) }.reason)
    }

    @Test
    fun `provider cannot exceed the requested candidate count or the body limit`() {
        handler = { respond(it, 200, """{"documents":[{},{}]}""") }
        assertEquals(PlaceSearchFailureReason.BAD_RESPONSE, assertFailsWith<PlaceSearchFailure> { provider().search("서울숲", 1) }.reason)
        handler = { respond(it, 200, " ".repeat(KakaoPlaceSearchProvider.MAX_RESPONSE_BYTES + 1)) }
        assertEquals(PlaceSearchFailureReason.BAD_RESPONSE, assertFailsWith<PlaceSearchFailure> { provider().search("서울숲", 10) }.reason)
    }

    @Test
    fun `provider authentication server and invalid status responses remain safe failures`() {
        val adapter = provider()
        mapOf(401 to PlaceSearchFailureReason.PROVIDER_AUTH_FAILED, 403 to PlaceSearchFailureReason.PROVIDER_AUTH_FAILED,
            500 to PlaceSearchFailureReason.UNAVAILABLE, 503 to PlaceSearchFailureReason.UNAVAILABLE,
            400 to PlaceSearchFailureReason.BAD_RESPONSE, 404 to PlaceSearchFailureReason.BAD_RESPONSE,
            302 to PlaceSearchFailureReason.BAD_RESPONSE).forEach { (status, expected) ->
            handler = { it.responseHeaders.set("Location", "/search"); respond(it, status, "upstream secret=$FAKE_KEY") }
            val failure = assertFailsWith<PlaceSearchFailure> { adapter.search("서울숲", 10) }
            assertEquals(expected, failure.reason)
            assertFalse(failure.toString().contains(FAKE_KEY))
            assertNull(failure.cause)
        }
        assertEquals(7, requests.get())
    }

    @Test
    fun `provider rate limit preserves only safe numeric retry after`() {
        val adapter = provider()
        listOf("30" to 30L, "secret-$FAKE_KEY" to null, "-1" to null).forEach { (value, expected) ->
            handler = { it.responseHeaders.set("Retry-After", value); respond(it, 429, "private provider error") }
            val failure = assertFailsWith<PlaceSearchFailure> { adapter.search("서울숲", 10) }
            assertEquals(PlaceSearchFailureReason.RATE_LIMITED, failure.reason)
            assertEquals(expected, failure.retryAfterSeconds)
        }
    }

    @Test
    fun `response deadline includes slow headers and slow body delivery`() {
        handler = { Thread.sleep(1000); respond(it, 200, SUCCESS) }
        assertEquals(PlaceSearchFailureReason.TIMEOUT, assertFailsWith<PlaceSearchFailure> { provider(timeout = Duration.ofMillis(100)).search("서울숲", 10) }.reason)
        handler = {
            it.responseHeaders.set("Content-Type", "application/json")
            it.sendResponseHeaders(200, 0)
            it.responseBody.write('{'.code)
            it.responseBody.flush()
            Thread.sleep(1000)
            it.responseBody.write('}'.code)
        }
        assertEquals(PlaceSearchFailureReason.TIMEOUT, assertFailsWith<PlaceSearchFailure> { provider(timeout = Duration.ofMillis(100)).search("서울숲", 10) }.reason)
    }

    @Test
    fun `keyword and address calls share a single response deadline`() {
        handler = {
            if (it.requestURI.path.endsWith("keyword.json")) {
                Thread.sleep(160)
                respond(it, 200, EMPTY)
            } else {
                Thread.sleep(300)
                respond(it, 200, ADDRESS)
            }
        }
        val failure = assertFailsWith<PlaceSearchFailure> {
            provider(timeout = Duration.ofMillis(400)).search("합성 주소", 10)
        }
        assertEquals(PlaceSearchFailureReason.TIMEOUT, failure.reason)
        assertEquals(2, requests.get())
    }

    @Test
    fun `address failure never becomes empty success`() {
        handler = { respond(it, if (it.requestURI.path.endsWith("keyword.json")) 200 else 503, EMPTY) }
        assertEquals(PlaceSearchFailureReason.UNAVAILABLE,
            assertFailsWith<PlaceSearchFailure> { provider().search("주소", 10) }.reason)
        assertEquals(2, requests.get())
    }

    @Test
    fun `duplicate place IDs are removed without losing the original ordering`() {
        val item = """{"id":"same","place_name":"합성 장소","x":127,"y":37}"""
        handler = { respond(it, 200, """{"documents":[$item,$item]}""") }
        assertEquals(1, provider().search("합성 장소", 10).places.size)
    }

    @Test
    fun `connection failure is unavailable and never empty`() {
        server.stop(0)
        assertEquals(PlaceSearchFailureReason.UNAVAILABLE, assertFailsWith<PlaceSearchFailure> { provider().search("서울숲", 10) }.reason)
    }

    @Test
    fun `missing key and local throttle do not contact the provider`() {
        val unavailable = provider(key = " \t ")
        assertEquals(PlaceSearchFailureReason.NOT_CONFIGURED, assertFailsWith<PlaceSearchFailure> { unavailable.search("서울숲", 10) }.reason)
        assertEquals(0, requests.get())
        val limited = provider(limiter = PlaceSearchRateLimiter(4) { 0L })
        limited.search("서울숲", 10)
        val failure = assertFailsWith<PlaceSearchFailure> { limited.search("서울역", 10) }
        assertEquals(PlaceSearchFailureReason.RATE_LIMITED, failure.reason)
        assertEquals(1, requests.get())
        assertFalse(PlaceSearchProperties(FAKE_KEY).toString().contains(FAKE_KEY))
    }

    @Test
    fun `non JSON success content is rejected`() {
        handler = { respond(it, 200, SUCCESS, "text/html") }
        assertEquals(PlaceSearchFailureReason.BAD_RESPONSE, assertFailsWith<PlaceSearchFailure> { provider().search("서울숲", 10) }.reason)
    }

    companion object {
        private const val FAKE_KEY = "fixture-key-not-a-real-api-key"
        private const val SUCCESS = """{"documents":[{"id":"fixture-1","place_name":"합성 테스트 장소","address_name":"합성 지번 주소","road_address_name":"합성 테스트 주소","x":"127.0374","y":37.5445,"unknown_new_field":true}]}"""
        private const val EMPTY = """{"documents":[]}"""
        private const val ADDRESS = """{"documents":[{"address_name":"합성 지번 주소 1","road_address":{"address_name":"합성 도로명 주소 1","building_name":"합성 건물"},"x":"127.0374","y":"37.5445"}]}"""
    }
}
