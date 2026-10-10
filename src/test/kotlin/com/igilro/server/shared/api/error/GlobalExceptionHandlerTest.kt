package com.igilro.server.shared.api.error

import com.igilro.server.shared.api.RequestIdFilter
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlobalExceptionHandlerTest {
    private lateinit var mvc: MockMvc
    private val clock = Clock.fixed(Instant.parse("2026-10-10T03:00:00Z"), ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.standaloneSetup(ErrorTestController())
            .setControllerAdvice(GlobalExceptionHandler(clock))
            .addFilters<org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder>(RequestIdFilter())
            .build()
    }

    @Test
    fun `invalid body returns field errors without the rejected value`() {
        mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.errors[0].field").value("name"))
            .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist())
    }

    @Test
    fun `malformed JSON returns a safe JSON error`() {
        mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{bad-json"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
    }

    @Test
    fun `missing query parameter is a 400`() {
        mvc.perform(get("/test/number"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
    }

    @Test
    fun `query type mismatch is a 400`() {
        mvc.perform(get("/test/number").param("value", "secret-input"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("요청 형식 또는 입력값이 올바르지 않습니다."))
    }

    @Test
    fun `query constraint violation includes the parameter name`() {
        mvc.perform(get("/test/number").param("value", "0"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("value"))
    }

    @Test
    fun `unknown path returns 404`() {
        mvc.perform(get("/missing"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
    }

    @Test
    fun `unsupported method retains the Allow header`() {
        mvc.perform(post("/test/number"))
            .andExpect(status().isMethodNotAllowed)
            .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
            .andExpect(header().exists("Allow"))
    }

    @Test
    fun `unsupported content type is a 415`() {
        mvc.perform(post("/test/body").contentType(MediaType.TEXT_PLAIN).content("name"))
            .andExpect(status().isUnsupportedMediaType)
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
    }

    @Test
    fun `unexpected error hides internals and preserves the request ID`() {
        val requestId = UUID.randomUUID().toString()
        val response = mvc.perform(get("/test/failure").header(RequestIdFilter.HEADER, requestId))
            .andExpect(status().isInternalServerError)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
            .andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."))
            .andExpect(jsonPath("$.timestamp").value("2026-10-10T03:00:00Z"))
            .andExpect(jsonPath("$.requestId").value(requestId))
            .andExpect(header().string(RequestIdFilter.HEADER, requestId))
            .andReturn().response
        assertFalse(response.contentAsString.contains("database-password"))
        assertNull(MDC.get("requestId"))
    }

    @Test
    fun `JSON accept header receives top-level error identifiers`() {
        val requestId = UUID.randomUUID().toString()
        mvc.perform(get("/missing").accept(MediaType.APPLICATION_JSON).header(RequestIdFilter.HEADER, requestId))
            .andExpect(status().isNotFound)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.requestId").value(requestId))
            .andExpect(header().string(RequestIdFilter.HEADER, requestId))
            .andExpect(jsonPath("$.errors").isArray)
    }

    @Test
    fun `client error request ID is recorded in server logs and cleared after the request`() {
        val requestId = UUID.randomUUID().toString()
        val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java) as Logger
        val appender = object : ListAppender<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                event.prepareForDeferredProcessing()
                super.append(event)
            }
        }
        appender.start()
        logger.addAppender(appender)
        try {
            mvc.perform(get("/test/number").header(RequestIdFilter.HEADER, requestId))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.requestId").value(requestId))
            assertTrue(appender.list.any {
                it.mdcPropertyMap["requestId"] == requestId && it.formattedMessage.contains("INVALID_REQUEST")
            })
            assertNull(MDC.get("requestId"))
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `unacceptable response still uses the JSON error contract`() {
        mvc.perform(get("/test/number").param("value", "1").accept(MediaType.TEXT_PLAIN))
            .andExpect(status().isNotAcceptable)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"))
    }

    @Test
    fun `HTTP rate limit errors preserve their status and stable code`() {
        mvc.perform(get("/test/rate-limit").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isTooManyRequests)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
    }

    @Test
    fun `invalid request ID is replaced with a UUID`() {
        val response = mvc.perform(get("/test/number").param("value", "1").header(RequestIdFilter.HEADER, "invalid"))
            .andExpect(status().isOk).andReturn().response
        assertNotNull(UUID.fromString(response.getHeader(RequestIdFilter.HEADER)))
        assertNull(MDC.get("requestId"))
    }
}

@RestController
@Profile("error-contract-test")
class ErrorTestController {
    data class Input(@field:NotBlank val name: String)

    @PostMapping("/test/body", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun body(@Valid @RequestBody input: Input) = mapOf("name" to input.name)

    @GetMapping("/test/number")
    fun number(@RequestParam("value") @Min(1) value: Int) = mapOf("value" to value)

    @GetMapping("/test/failure")
    fun failure(): String = throw IllegalStateException("database-password")

    @GetMapping("/test/rate-limit")
    fun rateLimit(): String = throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS)
}
