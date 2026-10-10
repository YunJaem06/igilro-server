package com.igilro.server.shared.api.error

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import java.time.Clock
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order

@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
class GlobalExceptionHandler(private val clock: Clock) : ResponseEntityExceptionHandler() {
    private val log = LoggerFactory.getLogger(javaClass)
    private val responder = ApiErrorResponder(clock)

    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        if ((request as? ServletWebRequest)?.response?.isCommitted == true) return null
        if (statusCode.value() == 500) log.error("HTTP 처리 실패", ex)
        val violations = if (statusCode.is4xxClientError) when (ex) {
            is MethodArgumentNotValidException -> ex.bindingResult.fieldErrors.map {
                FieldViolation(it.field, it.defaultMessage ?: "유효하지 않은 값입니다.")
            }
            is HandlerMethodValidationException -> ex.parameterValidationResults.flatMap { result ->
                result.resolvableErrors.map {
                    FieldViolation(result.methodParameter.parameterName ?: "parameter", it.defaultMessage ?: "유효하지 않은 값입니다.")
                }
            }
            else -> emptyList()
        } else emptyList()
        return errorResponse(statusCode, headers, request, violations)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception, request: WebRequest): ResponseEntity<Any> {
        log.error("처리하지 못한 서버 오류", ex)
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, HttpHeaders(), request)
    }

    private fun errorResponse(
        status: HttpStatusCode,
        headers: HttpHeaders,
        request: WebRequest,
        violations: List<FieldViolation> = emptyList(),
    ): ResponseEntity<Any> {
        val (code, message) = when (status.value()) {
            400 -> "INVALID_REQUEST" to "요청 형식 또는 입력값이 올바르지 않습니다."
            404 -> "NOT_FOUND" to "요청한 리소스를 찾을 수 없습니다."
            405 -> "METHOD_NOT_ALLOWED" to "지원하지 않는 HTTP 메서드입니다."
            406 -> "NOT_ACCEPTABLE" to "지원하지 않는 응답 형식입니다."
            415 -> "UNSUPPORTED_MEDIA_TYPE" to "지원하지 않는 요청 본문 형식입니다."
            429 -> "TOO_MANY_REQUESTS" to "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."
            503 -> "SERVICE_UNAVAILABLE" to "서비스를 일시적으로 사용할 수 없습니다."
            else -> if (status.is5xxServerError) {
                "INTERNAL_SERVER_ERROR" to "서버 오류가 발생했습니다."
            } else {
                "INVALID_REQUEST" to "요청을 처리할 수 없습니다."
            }
        }
        return responder.respond(status, code, message, request, headers, violations)
    }
}
