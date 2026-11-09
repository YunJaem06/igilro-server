package com.igilro.server.route.api

import com.igilro.server.route.domain.RouteFailure
import com.igilro.server.route.domain.RouteFailureReason
import com.igilro.server.route.application.RouteSearchRejected
import com.igilro.server.route.application.RouteSearchRejectionReason
import com.igilro.server.shared.api.error.ApiErrorResponder
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import java.time.Clock

@RestControllerAdvice(assignableTypes = [RouteController::class, ModelPredictionController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class RouteExceptionHandler(clock: Clock) {
    private val responder = ApiErrorResponder(clock)

    @ExceptionHandler(RouteSearchRejected::class)
    fun handleRejection(failure: RouteSearchRejected, request: WebRequest): org.springframework.http.ResponseEntity<Any> {
        val (status, code, message) = when (failure.reason) {
            RouteSearchRejectionReason.RATE_LIMITED -> Triple(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", "경로 검색 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.")
            RouteSearchRejectionReason.BUSY -> Triple(HttpStatus.SERVICE_UNAVAILABLE, "ROUTE_SEARCH_BUSY", "처리 중인 경로 검색이 많습니다. 잠시 후 다시 시도해 주세요.")
        }
        val headers = HttpHeaders().apply {
            cacheControl = "no-store"
            set(HttpHeaders.RETRY_AFTER, failure.retryAfterSeconds.toString())
        }
        return responder.respond(status, code, message, request, headers)
    }

    @ExceptionHandler(RouteFailure::class)
    fun handle(failure: RouteFailure, request: WebRequest) = when (failure.reason) {
        RouteFailureReason.INVALID_QUERY -> responder.respond(HttpStatus.BAD_REQUEST, "INVALID_ROUTE_QUERY", "지원하는 경로 조건과 검색 범위를 확인해 주세요.", request)
        RouteFailureReason.OUTSIDE_SUPPORTED_AREA -> responder.respond(HttpStatus.UNPROCESSABLE_CONTENT, "OUTSIDE_SUPPORTED_AREA", "출발·도착 지점이 같은 지원 지역 안에 있어야 합니다.", request)
        RouteFailureReason.NOT_FOUND -> responder.respond(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND", "연결 가능한 보행 경로를 찾을 수 없습니다.", request)
        RouteFailureReason.DATA_UNAVAILABLE -> responder.respond(HttpStatus.SERVICE_UNAVAILABLE, "ROUTING_DATA_UNAVAILABLE", "보행 경로 데이터를 사용할 수 없습니다.", request)
        RouteFailureReason.PREDICTION_DATA_UNAVAILABLE -> responder.respond(HttpStatus.SERVICE_UNAVAILABLE, "PREDICTION_DATA_UNAVAILABLE", "모델 기반 예측 자료를 사용할 수 없습니다.", request)
        RouteFailureReason.RUNNING_DATA_UNAVAILABLE -> responder.respond(HttpStatus.SERVICE_UNAVAILABLE, "RUNNING_DATA_UNAVAILABLE", "러닝길 비교에 필요한 보행로 횡단 자료를 사용할 수 없습니다.", request)
        RouteFailureReason.SHADE_DATA_UNAVAILABLE -> responder.respond(HttpStatus.SERVICE_UNAVAILABLE, "SHADE_DATA_UNAVAILABLE", "그늘 경로를 비교할 건물 자료가 충분하지 않습니다.", request)
        RouteFailureReason.SHADE_TIME_UNSUPPORTED -> responder.respond(HttpStatus.UNPROCESSABLE_CONTENT, "SHADE_TIME_UNSUPPORTED", "해당 시각에는 그늘 경로를 비교할 수 없습니다.", request)
        RouteFailureReason.SEARCH_LIMIT_EXCEEDED -> responder.respond(HttpStatus.SERVICE_UNAVAILABLE, "ROUTE_SEARCH_LIMIT_EXCEEDED", "검색 범위를 줄여 다시 시도해 주세요.", request)
    }
}
