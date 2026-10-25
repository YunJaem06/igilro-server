package com.igilro.server.place.api

import com.igilro.server.place.domain.PlaceSearchFailure
import com.igilro.server.place.domain.PlaceSearchFailureReason
import com.igilro.server.shared.api.error.ApiErrorResponder
import com.igilro.server.shared.api.error.FieldViolation
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import java.time.Clock

@RestControllerAdvice(assignableTypes = [PlaceController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class PlaceExceptionHandler(clock: Clock) {
    private val responder = ApiErrorResponder(clock)

    @ExceptionHandler(PlaceSearchFailure::class)
    fun handle(failure: PlaceSearchFailure, request: WebRequest): org.springframework.http.ResponseEntity<Any> {
        val (status, code, message) = when (failure.reason) {
            PlaceSearchFailureReason.INVALID_QUERY -> Triple(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "검색어 길이와 형식을 확인해 주세요.")
            PlaceSearchFailureReason.NOT_CONFIGURED -> Triple(HttpStatus.SERVICE_UNAVAILABLE, "PLACE_SEARCH_NOT_CONFIGURED", "장소 검색 서비스가 준비되지 않았습니다.")
            PlaceSearchFailureReason.PROVIDER_AUTH_FAILED -> Triple(HttpStatus.SERVICE_UNAVAILABLE, "PLACE_SEARCH_PROVIDER_AUTH_FAILED", "장소 검색 공급자 설정을 확인해야 합니다.")
            PlaceSearchFailureReason.RATE_LIMITED -> Triple(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", "검색 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.")
            PlaceSearchFailureReason.TIMEOUT -> Triple(HttpStatus.GATEWAY_TIMEOUT, "PLACE_SEARCH_TIMEOUT", "장소 검색 응답 시간이 초과되었습니다.")
            PlaceSearchFailureReason.UNAVAILABLE -> Triple(HttpStatus.BAD_GATEWAY, "PLACE_SEARCH_UNAVAILABLE", "장소 검색 공급자를 일시적으로 사용할 수 없습니다.")
            PlaceSearchFailureReason.BAD_RESPONSE -> Triple(HttpStatus.BAD_GATEWAY, "PLACE_SEARCH_BAD_RESPONSE", "장소 검색 공급자의 응답을 처리할 수 없습니다.")
        }
        val headers = HttpHeaders().apply {
            cacheControl = "no-store"
            failure.retryAfterSeconds?.let { set(HttpHeaders.RETRY_AFTER, it.toString()) }
        }
        val violations = if (failure.reason == PlaceSearchFailureReason.INVALID_QUERY) listOf(FieldViolation("query", "공백 정규화 후 1~100자로 입력해 주세요.")) else emptyList()
        return responder.respond(status, code, message, request, headers, violations)
    }
}
