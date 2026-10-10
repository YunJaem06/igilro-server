package com.igilro.server.shared.api

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Component
class RequestIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val requestId = request.getHeader(HEADER)
            ?.takeIf { it.length == 36 }
            ?.let { runCatching { UUID.fromString(it).toString() }.getOrNull() }
            ?: UUID.randomUUID().toString()
        request.setAttribute(ATTRIBUTE, requestId)
        response.setHeader(HEADER, requestId)
        val previous = MDC.get("requestId")
        MDC.put("requestId", requestId)
        try {
            chain.doFilter(request, response)
        } finally {
            if (previous == null) MDC.remove("requestId") else MDC.put("requestId", previous)
        }
    }

    companion object {
        const val HEADER = "X-Request-Id"
        const val ATTRIBUTE = "igilro.requestId"
    }
}
