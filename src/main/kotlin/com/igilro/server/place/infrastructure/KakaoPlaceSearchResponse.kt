package com.igilro.server.place.infrastructure

import tools.jackson.databind.JsonNode
import java.security.MessageDigest

/** Provider fields never cross the infrastructure boundary. No response is persisted. */
internal data class KakaoPlace(
    val id: String,
    val name: String,
    val address: String,
    val longitude: Double,
    val latitude: Double,
)

internal data class KakaoPlaceSearchResponse(val places: List<KakaoPlace>) {
    companion object {
        fun fromJson(root: JsonNode, limit: Int, address: Boolean): KakaoPlaceSearchResponse {
            require(root.isObject)
            val documents = requireNotNull(root["documents"])
            require(documents.isArray && documents.size() <= limit)
            return KakaoPlaceSearchResponse((0 until documents.size()).map { index ->
                val item = documents[index]
                require(item.isObject)
                val longitude = number(item, "x")
                val latitude = number(item, "y")
                require(longitude.isFinite() && longitude in -180.0..180.0)
                require(latitude.isFinite() && latitude in -90.0..90.0)
                if (address) {
                    val displayAddress = requireNotNull(text(item, "address_name")).trim()
                    require(displayAddress.isNotEmpty())
                    val road = item["road_address"]?.takeUnless { it.isNull }?.also { require(it.isObject) }
                    val name = road?.let { text(it, "building_name") }?.trim()?.takeIf { it.isNotEmpty() } ?: displayAddress
                    val roadAddress = road?.let { text(it, "address_name") }?.trim()?.takeIf { it.isNotEmpty() } ?: displayAddress
                    // The address API has no place ID. This response-local identifier is not a Kakao lookup key.
                    val digest = MessageDigest.getInstance("SHA-256")
                        .digest("$longitude|$latitude|$displayAddress".toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    KakaoPlace("kakao:address:$digest", name, roadAddress, longitude, latitude)
                } else {
                    val id = requireNotNull(text(item, "id"))
                    val name = requireNotNull(text(item, "place_name")).trim()
                    require(id.isNotBlank() && id.length <= 2048 && name.isNotEmpty())
                    val displayAddress = sequenceOf(text(item, "road_address_name"), text(item, "address_name"))
                        .filterNotNull().map(String::trim).firstOrNull(String::isNotEmpty).orEmpty()
                    KakaoPlace("kakao:place:$id", name, displayAddress, longitude, latitude)
                }
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
