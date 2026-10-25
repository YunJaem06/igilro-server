package com.igilro.server.place.infrastructure

import tools.jackson.databind.JsonNode
import java.net.URI

/** Supplier DTOs stay here; neither the controller nor the domain sees Jackson or supplier fields. */
internal data class GeoapifyPlace(
    val placeId: String,
    val name: String?,
    val addressLine1: String?,
    val formatted: String?,
    val longitude: Double,
    val latitude: Double,
    val attribution: GeoapifyAttribution?,
)

internal data class GeoapifyAttribution(val text: String, val url: String?)

internal data class GeoapifyResponse(val results: List<GeoapifyPlace>) {
    companion object {
        fun fromJson(root: JsonNode, limit: Int): GeoapifyResponse {
            require(root.isObject)
            val results = requireNotNull(root["results"])
            require(results.isArray && results.size() <= limit)
            return GeoapifyResponse((0 until results.size()).map { index ->
                val item = results[index]
                require(item.isObject)
                val id = requireNotNull(text(item, "place_id"))
                require(id.isNotBlank() && id.length <= 2048)
                val source = item["datasource"]?.takeUnless { it.isNull }?.let { data ->
                    require(data.isObject)
                    text(data, "attribution")?.takeIf { it.isNotBlank() }?.let { attribution ->
                        val url = text(data, "url")?.takeIf { it.isNotBlank() }
                        if (url != null) {
                            val uri = URI.create(url)
                            require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null)
                        }
                        GeoapifyAttribution(attribution, url)
                    }
                }
                GeoapifyPlace(id, text(item, "name"), text(item, "address_line1"), text(item, "formatted"),
                    number(item, "lon"), number(item, "lat"), source)
            })
        }

        private fun text(node: JsonNode, key: String): String? = node[key]?.takeUnless { it.isNull }?.let {
            require(it.isString)
            it.asString()
        }

        private fun number(node: JsonNode, key: String): Double {
            val value = requireNotNull(node[key])
            require(value.isNumber || value.isString)
            return if (value.isNumber) value.asDouble() else requireNotNull(value.asString().trim().toDoubleOrNull())
        }
    }
}
