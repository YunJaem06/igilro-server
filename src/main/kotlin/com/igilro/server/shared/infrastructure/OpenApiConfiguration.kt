package com.igilro.server.shared.infrastructure

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {
    @Bean
    fun openAPI(): OpenAPI = OpenAPI()
        .info(Info().title("이길로 서버 API").version("v1").description("Phase 1 서비스 기반 API. 모든 시각은 RFC 3339 UTC로 응답합니다."))
}
