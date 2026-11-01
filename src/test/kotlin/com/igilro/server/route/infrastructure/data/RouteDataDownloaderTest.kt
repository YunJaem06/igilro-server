package com.igilro.server.route.infrastructure.data

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class RouteDataDownloaderTest {
    @TempDir lateinit var folder: Path
    private val key = "datasets/${"a".repeat(64)}/manifest.json"
    private val files = mapOf("scene.json" to "scene".toByteArray(), "roads.osm" to "roads".toByteArray(), "walking.osm" to "walking".toByteArray())
    private fun manifest(transform: (MutableMap<String,Any>) -> Unit = {}): ByteArray {
        val root = mutableMapOf<String,Any>("schemaVersion" to 1,
            "files" to files.mapValues { (name, bytes) -> mapOf("key" to key.substringBeforeLast('/')+"/"+name,"versionId" to "version-$name",
                "sha256" to RouteDataDownloader.hash(bytes),"sizeBytes" to bytes.size) },
            "graph" to mapOf("areaId" to "fixture","areaName" to "Fixture","source" to "https://example.com/public.osm",
                "bounds" to listOf(127.03,37.54,127.04,37.55),"expectedDataVersion" to "osm-v1-${"b".repeat(64)}"),
            "smokeQuery" to mapOf("origin" to mapOf("longitude" to 127.031,"latitude" to 37.541),
                "destination" to mapOf("longitude" to 127.032,"latitude" to 37.542),"travelMode" to "walking","departureAt" to "2026-10-28T11:00:00+09:00"))
        transform(root)
        return JsonMapper.builder().build().writeValueAsBytes(root)
    }
    private fun store(manifest: ByteArray, replacement: ByteArray? = null, opened: MutableList<Pair<String,String>> = mutableListOf()) = RouteDataObjectStore { objectKey, version ->
        opened.add(objectKey to version)
        ByteArrayInputStream(if(objectKey==key) manifest else replacement ?: files.getValue(objectKey.substringAfterLast('/')))
    }

    @Test fun `disabled data configuration uses no AWS credentials or network`() {
        assertNull(RouteDataConfiguration().routeDataFiles(RouteDataProperties()).prepared)
        assertFailsWith<IllegalArgumentException> { RouteDataProperties(bucket="fixture-bucket").enabled() }
    }
    @Test fun `versioned manifest and all object bytes are verified before exposing a snapshot`() {
        val bytes = manifest(); val opened = mutableListOf<Pair<String,String>>()
        val data = RouteDataDownloader(store(bytes,opened=opened)).download(key,"manifest-version",RouteDataDownloader.hash(bytes),folder)
        assertEquals(key to "manifest-version",opened.first())
        files.forEach { (name, raw) ->
            assertContentEquals(raw,Files.readAllBytes(data.directory.resolve(name)))
            assertTrue(opened.contains(key.substringBeforeLast('/')+"/"+name to "version-$name"))
        }
        assertEquals(1L,Files.list(folder).use { it.count() })
        assertTrue(data.directory.fileName.toString().startsWith("verified-"))
    }
    @Test fun `wrong manifest digest fails before any file download`() {
        val bytes = manifest(); val opened = mutableListOf<Pair<String,String>>()
        assertFailsWith<IllegalArgumentException> { RouteDataDownloader(store(bytes,opened=opened)).download(key,"pin","c".repeat(64),folder) }
        assertEquals(1,opened.size)
        assertEquals(0L,Files.list(folder).use { it.count() })
    }
    @Test fun `truncated oversized and tampered object bodies leave no partial snapshot`() {
        val bytes = manifest()
        for (bad in listOf(byteArrayOf(1), ByteArray(100), "wrong".toByteArray())) {
            assertFailsWith<IllegalStateException> { RouteDataDownloader(store(bytes,bad)).download(key,"pin",RouteDataDownloader.hash(bytes),folder) }
            assertEquals(0L,Files.list(folder).use { it.count() })
        }
    }
    @Test fun `cross prefix object references and missing inventory fields are rejected`() {
        val bytes = manifest { root -> root["files"] = files.mapValues { (name, raw) ->
            mapOf("key" to "other/$name","versionId" to "pin","sha256" to RouteDataDownloader.hash(raw),"sizeBytes" to raw.size)
        } }
        assertFailsWith<IllegalArgumentException> { RouteDataManifest.parse(bytes,key) }
        assertFailsWith<IllegalArgumentException> { RouteDataManifest.parse(manifest { it["files"]=emptyMap<String,Any>() },key) }
    }
    @Test fun `unsupported schema unversioned references and duplicate fields fail closed`() {
        assertFailsWith<IllegalArgumentException> { RouteDataManifest.parse(manifest { it["schemaVersion"]=2 },key) }
        val raw=manifest().toString(Charsets.UTF_8).replace("version-scene.json","null").toByteArray()
        assertFailsWith<IllegalArgumentException> { RouteDataManifest.parse(raw,key) }
        val duplicate=manifest().toString(Charsets.UTF_8).replaceFirst("\"schemaVersion\":1","\"schemaVersion\":1,\"schemaVersion\":1").toByteArray()
        assertFails { RouteDataManifest.parse(duplicate,key) }
    }
    @Test fun `deadline expiry removes partially written data`() {
        val bytes=manifest();val now=AtomicLong()
        val source=RouteDataObjectStore { name,_ ->
            if(name!=key) now.set(121_000_000_000)
            ByteArrayInputStream(if(name==key)bytes else files.getValue(name.substringAfterLast('/')))
        }
        assertFailsWith<IllegalStateException> { RouteDataDownloader(source,now::get).download(key,"pin",RouteDataDownloader.hash(bytes),folder) }
        assertEquals(0L,Files.list(folder).use { it.count() })
    }
}
