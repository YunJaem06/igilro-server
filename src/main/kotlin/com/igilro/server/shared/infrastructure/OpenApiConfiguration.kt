package com.igilro.server.shared.infrastructure

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.responses.ApiResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {
    @Bean
    fun openAPI(): OpenAPI {
        val violation = ObjectSchema()
            .addProperty("field", StringSchema())
            .addProperty("message", StringSchema())
        val problem = ObjectSchema()
            .addProperty("type", StringSchema().format("uri"))
            .addProperty("title", StringSchema())
            .addProperty("status", IntegerSchema())
            .addProperty("detail", StringSchema())
            .addProperty("instance", StringSchema().format("uri-reference"))
            .addProperty("code", StringSchema().example("INTERNAL_SERVER_ERROR"))
            .addProperty("timestamp", StringSchema().format("date-time"))
            .addProperty("requestId", StringSchema().format("uuid"))
            .addProperty("errors", ArraySchema().items(violation))
        val serverError = ApiResponse().description("서버 오류")
            .content(Content().addMediaType("application/problem+json", MediaType().schema(Schema<Any>().`$ref`("#/components/schemas/Problem"))))
        return OpenAPI()
            .info(Info().title("이길로 서버 API").version("v1").description("Phase 1 서비스 기반 API. 모든 시각은 RFC 3339 UTC로 응답합니다."))
            .components(Components().addSchemas("Problem", problem).addResponses("InternalServerError", serverError))
    }
}
