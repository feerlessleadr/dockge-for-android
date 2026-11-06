package com.feerlessleadr.dockge.ui.instances

import com.feerlessleadr.dockge.api.FailureKind
import com.feerlessleadr.dockge.data.ConnectionState

fun FailureKind.text(): String = when (this) {
    FailureKind.InvalidCredentials -> "Wrong user name or password."
    FailureKind.InvalidCode -> "That code was not accepted. Wait for the next code and try again."
    FailureKind.ChallengeExpired -> "The code step timed out. Enter your password again."
    FailureKind.RateLimited -> "The server is rate limiting logins. Wait a minute."
    FailureKind.Revoked -> "Your saved sign-in is no longer valid. Sign in again."
    FailureKind.Turnstile -> "This server requires a Turnstile check, which the app cannot do."
    FailureKind.LocalLoginDisabled -> "Password login is turned off on this server."
    FailureKind.Network -> "No answer from the server."
    FailureKind.Other -> "Sign-in failed."
}

/** Short status text for the instance list. */
fun ConnectionState.label(): String = when (this) {
    ConnectionState.Disconnected -> "Disconnected"
    ConnectionState.Connecting -> "Connecting"
    is ConnectionState.NeedsLogin -> "Sign-in needed"
    is ConnectionState.NeedsTwoFactor -> "Code needed"
    ConnectionState.LoggingIn -> "Signing in"
    ConnectionState.Connected -> "Connected"
    is ConnectionState.Failed -> "Can't connect"
}
