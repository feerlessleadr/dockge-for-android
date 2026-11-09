package com.feerlessleadr.dockge.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class FailureKind {
    InvalidCredentials,
    InvalidCode,

    /** The 5 minute 2FA challenge ran out, or the socket was replaced. Start again with the password. */
    ChallengeExpired,

    /** Server-wide login limit (20 per minute for everyone). Do not retry in a loop. */
    RateLimited,

    /** The stored token no longer works (password or 2FA changed, or the user was deactivated). */
    Revoked,

    /** The server wants a Cloudflare Turnstile check, which a native client cannot do. */
    Turnstile,
    LocalLoginDisabled,

    /** No answer or no connection. */
    Network,
    Other,
}

sealed interface LoginResult {
    data class Success(val token: String) : LoginResult
    data class TwoFactorRequired(val challenge: String) : LoginResult
    data class Failure(val kind: FailureKind, val message: String) : LoginResult
}

sealed interface TokenLoginResult {
    data object Ok : TokenLoginResult
    data class Failure(val kind: FailureKind, val message: String) : TokenLoginResult
}

/** Turns the server's acknowledgements into typed results. Shapes are in docs/dockge-api.md, part A. */
object AuthParser {
    fun parseLogin(reply: JsonElement): LoginResult {
        val obj = reply as? JsonObject ?: return LoginResult.Failure(FailureKind.Other, "Unexpected reply from the server.")
        if (obj["tokenRequired"]?.jsonPrimitive?.booleanOrNull == true) {
            val challenge = obj["challenge"]?.jsonPrimitive?.contentOrNull
            return if (challenge.isNullOrEmpty()) {
                LoginResult.Failure(FailureKind.Other, "The server asked for a code but sent no challenge.")
            } else {
                LoginResult.TwoFactorRequired(challenge)
            }
        }
        if (obj["ok"]?.jsonPrimitive?.booleanOrNull == true) {
            val token = obj["token"]?.jsonPrimitive?.contentOrNull
            return if (token.isNullOrEmpty()) {
                LoginResult.Failure(FailureKind.Other, "The server accepted the login but sent no token.")
            } else {
                LoginResult.Success(token)
            }
        }
        val msg = message(obj)
        return LoginResult.Failure(kindOf(msg), msg)
    }

    fun parseTokenLogin(reply: JsonElement): TokenLoginResult {
        val obj = reply as? JsonObject
            ?: return TokenLoginResult.Failure(FailureKind.Other, "Unexpected reply from the server.")
        if (obj["ok"]?.jsonPrimitive?.booleanOrNull == true) return TokenLoginResult.Ok
        val msg = message(obj)
        return TokenLoginResult.Failure(kindOf(msg), msg)
    }

    private fun message(obj: JsonObject): String = obj["msg"]?.jsonPrimitive?.contentOrNull ?: "Login failed."

    fun kindOf(msg: String): FailureKind = when {
        msg == "authIncorrectCreds" -> FailureKind.InvalidCredentials
        msg == "twoFactor.invalidCode" -> FailureKind.InvalidCode
        msg == "twoFactor.restartLogin" -> FailureKind.ChallengeExpired
        msg.startsWith("Too frequently") -> FailureKind.RateLimited
        msg == "authInvalidToken" || msg == "authUserInactiveOrDeleted" -> FailureKind.Revoked
        msg == "authTurnstileFailed" -> FailureKind.Turnstile
        msg == "oidc.localLoginDisabled" -> FailureKind.LocalLoginDisabled
        else -> FailureKind.Other
    }
}

/** The login calls. The password and the code only live in the arguments of one call. */
class AuthApi(private val socket: DockgeSocket) {
    suspend fun loginByToken(token: String, timeoutMs: Long = DockgeSocket.DEFAULT_TIMEOUT_MS): TokenLoginResult =
        AuthParser.parseTokenLogin(socket.call("loginByToken", listOf(JsonPrimitive(token)), timeoutMs))

    suspend fun login(username: String, password: String, code: String? = null, challenge: String? = null): LoginResult {
        val body = buildJsonObject {
            put("username", username)
            put("password", password)
            if (code != null) put("token", code)
            if (challenge != null) put("challenge", challenge)
        }
        return AuthParser.parseLogin(socket.call("login", listOf(body)))
    }
}
