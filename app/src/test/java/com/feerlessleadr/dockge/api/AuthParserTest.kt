package com.feerlessleadr.dockge.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Payload samples follow docs/dockge-api.md part A (taken from the fork's source, not yet from a live capture). */
class AuthParserTest {
    private fun login(json: String) = AuthParser.parseLogin(Json.parseToJsonElement(json))
    private fun tokenLogin(json: String) = AuthParser.parseTokenLogin(Json.parseToJsonElement(json))

    @Test fun loginSuccess() = assertEquals(LoginResult.Success("jwt.value"), login("""{"ok":true,"token":"jwt.value"}"""))

    @Test fun loginNeedsCode() =
        assertEquals(LoginResult.TwoFactorRequired("abc"), login("""{"tokenRequired":true,"challenge":"abc"}"""))

    @Test fun codeRequiredWithoutChallengeIsAFailure() {
        assertTrue(login("""{"tokenRequired":true}""") is LoginResult.Failure)
    }

    @Test fun successWithoutTokenIsAFailure() {
        assertTrue(login("""{"ok":true}""") is LoginResult.Failure)
    }

    @Test fun failureKinds() {
        fun kind(msg: String) = (login("""{"ok":false,"msg":"$msg","msgi18n":true}""") as LoginResult.Failure).kind
        assertEquals(FailureKind.InvalidCredentials, kind("authIncorrectCreds"))
        assertEquals(FailureKind.InvalidCode, kind("twoFactor.invalidCode"))
        assertEquals(FailureKind.ChallengeExpired, kind("twoFactor.restartLogin"))
        assertEquals(FailureKind.Turnstile, kind("authTurnstileFailed"))
        assertEquals(FailureKind.LocalLoginDisabled, kind("oidc.localLoginDisabled"))
        assertEquals(FailureKind.Other, kind("something.new"))
    }

    @Test fun rateLimitMessageHasNoI18nFlag() {
        val r = login("""{"ok":false,"msg":"Too frequently, try again later."}""") as LoginResult.Failure
        assertEquals(FailureKind.RateLimited, r.kind)
    }

    @Test fun nonObjectReplyIsAFailure() {
        assertTrue(login("\"nope\"") is LoginResult.Failure)
        assertTrue(tokenLogin("[]") is TokenLoginResult.Failure)
    }

    @Test fun tokenLogin() {
        assertEquals(TokenLoginResult.Ok, tokenLogin("""{"ok":true}"""))
        assertEquals(
            FailureKind.Revoked,
            (tokenLogin("""{"ok":false,"msg":"authInvalidToken","msgi18n":true}""") as TokenLoginResult.Failure).kind,
        )
        assertEquals(
            FailureKind.Revoked,
            (tokenLogin("""{"ok":false,"msg":"authUserInactiveOrDeleted","msgi18n":true}""") as TokenLoginResult.Failure).kind,
        )
        assertEquals(
            FailureKind.Other,
            (tokenLogin("""{"ok":false,"msg":"boom"}""") as TokenLoginResult.Failure).kind,
        )
    }
}
