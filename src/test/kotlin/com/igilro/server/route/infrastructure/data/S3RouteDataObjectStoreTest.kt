package com.igilro.server.route.infrastructure.data

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class S3RouteDataObjectStoreTest {
    @Test fun `real SDK signs a local request with the exact version ID and returns bounded stream content`() {
        val version=AtomicReference<String>()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/") { exchange ->
            version.set(URLDecoder.decode(exchange.requestURI.rawQuery.substringAfter("versionId="),Charsets.UTF_8))
            val raw="synthetic object".toByteArray()
            exchange.sendResponseHeaders(200,raw.size.toLong())
            exchange.responseBody.use { it.write(raw) }
        }
        server.start()
        try {
            S3Client.builder().region(Region.AP_NORTHEAST_2).endpointOverride(URI("http://127.0.0.1:${server.address.port}"))
                .forcePathStyle(true).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("fixture-access","fixture-secret")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build().use { client ->
                    val raw=S3RouteDataObjectStore(client,"fixture-bucket").open("datasets/fixture/scene.json","v+1/2").use { it.readAllBytes() }
                    assertEquals("synthetic object",raw.toString(Charsets.UTF_8))
                    assertEquals("v+1/2",version.get())
                }
        } finally { server.stop(0) }
    }
}
