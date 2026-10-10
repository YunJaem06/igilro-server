package com.igilro.server.route.api

import com.igilro.server.route.domain.RouteFailure
import com.igilro.server.route.domain.RouteFailureReason
import com.igilro.server.shared.api.error.ApiErrorResponder
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import java.time.Clock

@RestControllerAdvice(assignableTypes = [RouteController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class RouteExceptionHandler(clock: Clock) {
    private val responder = ApiErrorResponder(clock)

    @ExceptionHandler(RouteFailure::class)
    fun handle(failure: RouteFailure, request: WebRequest) = when (failure.reason) {
        RouteFailureReason.INVALID_QUERY -> responder.respond(HttpStatus.BAD_REQUEST, "INVALID_ROUTE_QUERY", "지원하는 경로 조건과 검색 범위를 확인해 주세요.", request)
        RouteFailureReason.OUTSIDE_SUPPORTED_AREA -> responder.respond(HttpStatus.UNPROCESSABLE_CONTENT, "OUTSIDE_SUPPORTED_AREA", "출발·도착 지점이 같은 지원 지역 안에 있어야 합니다.", request)
        RouteFailureReason.NOT_FOUND -> responder.respond(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND", "연결 가능한 보행 경로를 찾을 수 없습니다.", request)
        RouteFailureReason.DATA_UNAVAILABLE -> responder.respond(HttpStatus.SERVICE_UNAVAILABLE, "ROUTING_DATA_UNAVAILABLE", "보행 경로 데이터를 사용할 수 없습니다.", request)
    }
}
