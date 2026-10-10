package com.igilro.server.shared.api.error

import com.igilro.server.shared.api.RequestIdFilter
import org.slf4j.LoggerFactory
import org.springframework.http.*
import org.springframework.web.context.request.WebRequest
import java.time.Clock
import java.util.UUID

class ApiErrorResponder(private val clock: Clock) {
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    fun respond(
        status: HttpStatusCode,
        code: String,
        message: String,
        request: WebRequest,
        headers: HttpHeaders = HttpHeaders(),
        violations: List<FieldViolation> = emptyList(),
    ): ResponseEntity<Any> {
        val requestId = request.getAttribute(RequestIdFilter.ATTRIBUTE, WebRequest.SCOPE_REQUEST) as? String
            ?: UUID.randomUUID().toString()
        log.warn("API 오류 응답: status={}, code={}, requestId={}", status.value(), code, requestId)
        val response = ApiErrorResponse(code, message, requestId, clock.instant(), violations)
        val responseHeaders = HttpHeaders()
        responseHeaders.putAll(headers)
        responseHeaders.contentType = MediaType.APPLICATION_JSON
        responseHeaders.set(RequestIdFilter.HEADER, requestId)
        return ResponseEntity(response, responseHeaders, status)
    }
}
