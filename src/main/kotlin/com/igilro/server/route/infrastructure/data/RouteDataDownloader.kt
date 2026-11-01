package com.igilro.server.route.infrastructure.data

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Comparator

fun interface RouteDataObjectStore { fun open(key: String, versionId: String): InputStream }
data class PreparedRouteData(val directory: Path, val manifest: RouteDataManifest)
class RouteDataFiles(val prepared: PreparedRouteData? = null)

/** A private, verified snapshot. Partial downloads are never exposed to routing. */
class RouteDataDownloader(private val store: RouteDataObjectStore, private val nanoTime: () -> Long = System::nanoTime) {
    fun download(key: String, version: String, sha256: String, parent: Path): PreparedRouteData {
        require(key.matches(Regex("datasets/[a-f0-9]{64}/manifest\\.json")))
        require(version.isNotBlank() && version != "null" && version.length <= 1024 && version.none(Char::isISOControl))
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        val started = nanoTime()
        fun checkTime() = check(nanoTime()-started <= 120_000_000_000L) { "Route data preparation exceeded its time budget" }
        val bytes = store.open(key,version).use { it.readNBytes(65_537) }
        checkTime()
        require(bytes.size in 1..65_536 && hash(bytes) == sha256) { "Route manifest integrity check failed" }
        val manifest = RouteDataManifest.parse(bytes,key)
        Files.createDirectories(parent)
        val pending = Files.createTempDirectory(parent,".route-data-")
        try {
            manifest.files.forEach { (name, reference) ->
                checkTime()
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                store.open(reference.key,reference.versionId).use { input ->
                    Files.newOutputStream(pending.resolve(name)).use { output ->
                        val buffer = ByteArray(65_536)
                        while (true) {
                            val count = input.read(buffer)
                            checkTime()
                            if (count < 0) break
                            total += count
                            require(total <= reference.sizeBytes) { "Route object exceeds its manifest size" }
                            digest.update(buffer,0,count); output.write(buffer,0,count)
                        }
                    }
                }
                require(total == reference.sizeBytes && hex(digest.digest()) == reference.sha256) { "Route object integrity check failed" }
            }
            val ready = parent.resolve("verified-$sha256-${pending.fileName.toString().removePrefix(".route-data-")}")
            Files.move(pending,ready,StandardCopyOption.ATOMIC_MOVE)
            return PreparedRouteData(ready,manifest)
        } catch (error: Exception) {
            Files.walk(pending).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            // Transport/JSON diagnostics may contain signed URLs or source text.
            throw IllegalStateException("Route data preparation failed; no partial snapshot was activated")
        }
    }

    companion object {
        fun hash(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}
