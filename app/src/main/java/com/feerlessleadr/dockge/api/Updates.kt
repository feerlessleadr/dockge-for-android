package com.feerlessleadr.dockge.api

import java.time.Instant

/** One image of one stack as the server's image watcher last checked it (digest comparison against the registry). */
data class ImageUpdate(
    val stack: String,
    /** The reference as written in the compose file, for example "nginx:latest". */
    val image: String,
    val localDigest: String,
    val remoteDigest: String,
    val hasUpdate: Boolean,
    val lastChecked: Instant?,
    /** The owner set this image to "ignored" on the server. */
    val ignored: Boolean,
    /** The check itself failed (registry unreachable, rate limited ...). Then [hasUpdate] cannot be trusted. */
    val error: String?,
) {
    /** True only when the server found a newer image and the check did not fail. */
    val updateAvailable: Boolean get() = hasUpdate && error == null && !ignored
}

/** The result of one status refresh for one server. */
data class ServerUpdates(
    /** False when the image watcher is switched off on that server: the list is then empty or stale. */
    val watcherEnabled: Boolean,
    val images: List<ImageUpdate>,
) {
    fun forStack(stack: String): List<ImageUpdate> = images.filter { it.stack == stack }
    fun availableFor(stack: String): List<ImageUpdate> = forStack(stack).filter { it.updateAvailable }
    val availableCount: Int get() = images.filter { it.updateAvailable }.map { it.stack }.distinct().size
}

object ImageLinks {
    /**
     * Where to read about an image: its registry page. Dockge has no changelog or release notes, so this page (and the
     * digests) is all there is. Port of the fork's `imageRegistryUrl` (common/util-common.ts); null for private or
     * unknown registries, where no reliable link exists.
     */
    fun registryUrl(image: String): String? {
        if (image.isBlank()) return null
        val ref = image.trim().substringBefore("@")
        var registry = ""
        var remainder = ref
        val firstSlash = ref.indexOf('/')
        if (firstSlash != -1) {
            val maybeHost = ref.substring(0, firstSlash)
            if (maybeHost.contains('.') || maybeHost.contains(':') || maybeHost == "localhost") {
                registry = maybeHost.lowercase()
                remainder = ref.substring(firstSlash + 1)
            }
        }
        val lastColon = remainder.lastIndexOf(':')
        val lastSlash = remainder.lastIndexOf('/')
        if (lastColon != -1 && lastColon > lastSlash) remainder = remainder.substring(0, lastColon)
        if (remainder.isEmpty()) return null
        return when (registry) {
            "ghcr.io" -> "https://github.com/" + remainder.split("/").take(2).joinToString("/")
            "quay.io" -> "https://quay.io/repository/$remainder"
            "lscr.io" -> "https://hub.docker.com/r/$remainder"
            "registry.gitlab.com" -> "https://gitlab.com/$remainder"
            "", "docker.io", "registry-1.docker.io", "index.docker.io" -> {
                var parts = remainder.split("/")
                if (parts[0] == "library") parts = parts.drop(1)
                if (parts.size == 1) "https://hub.docker.com/_/${parts[0]}" else "https://hub.docker.com/r/" + parts.take(2).joinToString("/")
            }
            else -> null
        }
    }

    /** "sha256:0123456789ab..." shortened for a phone screen. */
    fun shortDigest(digest: String): String = digest.removePrefix("sha256:").take(12).ifBlank { "unknown" }
}
