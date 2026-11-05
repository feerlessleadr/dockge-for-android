package com.feerlessleadr.dockge.core

import java.net.URI

/**
 * A validated instance address. Release builds accept only https. The Socket.IO connection uses [origin] plus
 * [socketPath]; a reverse-proxy sub-path (for example https://host/dockge) is kept in [pathPrefix].
 */
class BaseUrl private constructor(
    val origin: String,
    val pathPrefix: String,
) {
    /** Canonical form without a trailing slash. */
    val value: String get() = origin + pathPrefix

    /** Path of the Socket.IO endpoint on this instance. */
    val socketPath: String get() = "$pathPrefix/socket.io/"

    override fun toString(): String = value

    companion object {
        /** Returns the parsed address or a failure whose message can be shown to the user. */
        fun parse(input: String, allowCleartext: Boolean = false): Result<BaseUrl> {
            val text = input.trim()
            if (text.isEmpty()) return fail("Enter the instance address.")
            val withScheme = if (text.contains("://")) text else "https://$text"
            val uri = try {
                URI(withScheme)
            } catch (e: Exception) {
                return fail("That is not a valid address.")
            }
            val scheme = uri.scheme?.lowercase()
            if (scheme != "https" && !(allowCleartext && scheme == "http")) {
                return fail("Only https addresses are allowed.")
            }
            val host = uri.host
            if (host.isNullOrBlank()) return fail("The address has no host name.")
            if (uri.userInfo != null) return fail("Do not put a user name or password in the address.")
            if (uri.query != null || uri.fragment != null) return fail("Remove the query or fragment from the address.")
            val prefix = (uri.rawPath ?: "").trimEnd('/')
            val port = if (uri.port == -1) "" else ":${uri.port}"
            return Result.success(BaseUrl("$scheme://${host.lowercase()}$port", prefix))
        }

        private fun fail(message: String): Result<BaseUrl> = Result.failure(IllegalArgumentException(message))
    }
}
