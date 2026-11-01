package com.igilro.server.route.infrastructure.data

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.retries.StandardRetryStrategy
import java.nio.file.Path
import java.time.Duration

class S3RouteDataObjectStore(private val client: S3Client, private val bucket: String) : RouteDataObjectStore {
    override fun open(key: String, versionId: String) =
        client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).versionId(versionId).build())
}

@ConfigurationProperties("igilro.route-data")
class RouteDataProperties(
    val bucket: String = "",
    val manifestKey: String = "",
    val manifestVersionId: String = "",
    val manifestSha256: String = "",
    val directory: String = "/tmp/igilro-route-data",
) {
    fun enabled(): Boolean {
        val values = listOf(bucket,manifestKey,manifestVersionId,manifestSha256)
        require(values.all(String::isBlank) || values.none(String::isBlank)) { "Configure all four route data references or none" }
        if (values.all(String::isBlank)) return false
        require(bucket.matches(Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) && ".." !in bucket)
        return true
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RouteDataProperties::class)
class RouteDataConfiguration {
    @Bean
    fun routeDataFiles(properties: RouteDataProperties): RouteDataFiles {
        if (!properties.enabled()) return RouteDataFiles()
        try {
            S3Client.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(10)))
                .overrideConfiguration { it.apiCallTimeout(Duration.ofSeconds(60)).apiCallAttemptTimeout(Duration.ofSeconds(20))
                    .retryStrategy(StandardRetryStrategy.builder().maxAttempts(2).build()) }
                .build().use { client ->
                    val store = S3RouteDataObjectStore(client,properties.bucket)
                    return RouteDataFiles(RouteDataDownloader(store).download(properties.manifestKey,properties.manifestVersionId,
                        properties.manifestSha256,Path.of(properties.directory)))
                }
        } catch (_: Exception) {
            throw IllegalStateException("Unable to prepare the pinned route dataset; check object access and integrity")
        }
    }
}
