package com.feerlessleadr.dockge.data

import com.feerlessleadr.dockge.api.AuthApi
import com.feerlessleadr.dockge.api.DockgeSocket
import com.feerlessleadr.dockge.api.FailureKind
import com.feerlessleadr.dockge.api.LoginResult
import com.feerlessleadr.dockge.api.SocketCallException
import com.feerlessleadr.dockge.api.SocketState
import com.feerlessleadr.dockge.api.TokenLoginResult
import com.feerlessleadr.dockge.core.TokenVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState

    /** [lastFailure] explains why the form is shown again (null on the first prompt). */
    data class NeedsLogin(val lastFailure: FailureKind? = null) : ConnectionState
    data class NeedsTwoFactor(val lastFailure: FailureKind? = null) : ConnectionState
    data object LoggingIn : ConnectionState
    data object Connected : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

/**
 * One instance: one socket, one stored token. It logs in with the stored token on every (re)connect, and asks for
 * the password and the 2FA code only when there is no usable token. The password is held in memory only between the
 * password step and the code step; the TOTP seed is never asked for or stored.
 */
class InstanceSession(
    private val instanceId: String,
    val socket: DockgeSocket,
    private val vault: TokenVault,
    private val scope: CoroutineScope,
) {
    private class Pending(val username: String, val password: String, val challenge: String)

    private val auth = AuthApi(socket)
    private val authLock = Mutex()
    private var pending: Pending? = null
    private var job: Job? = null

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    fun start() {
        if (job != null) return
        _state.value = ConnectionState.Connecting
        job = scope.launch {
            launch { socket.state.collect(::onSocketState) }
            socket.connect()
        }
    }

    fun stop() {
        retryJob?.cancel()
        job?.cancel()
        job = null
        pending = null
        socket.close()
        _state.value = ConnectionState.Disconnected
    }

    /** There is no server-side logout: the token is only forgotten here. */
    fun signOut() {
        vault.remove(instanceId)
        stop()
    }

    fun submitCredentials(username: String, password: String) {
        scope.launch {
            authLock.withLock {
                if (_state.value !is ConnectionState.NeedsLogin && _state.value !is ConnectionState.NeedsTwoFactor) return@withLock
                _state.value = ConnectionState.LoggingIn
                pending = null
                try {
                    when (val result = auth.login(username, password)) {
                        is LoginResult.Success -> finishLogin(result.token)
                        is LoginResult.TwoFactorRequired -> {
                            pending = Pending(username, password, result.challenge)
                            _state.value = ConnectionState.NeedsTwoFactor()
                        }
                        is LoginResult.Failure -> _state.value = ConnectionState.NeedsLogin(result.kind)
                    }
                } catch (e: SocketCallException) {
                    _state.value = ConnectionState.NeedsLogin(FailureKind.Network)
                }
            }
        }
    }

    fun submitCode(code: String) {
        scope.launch {
            authLock.withLock {
                val p = pending
                if (p == null || _state.value !is ConnectionState.NeedsTwoFactor) return@withLock
                _state.value = ConnectionState.LoggingIn
                try {
                    when (val result = auth.login(p.username, p.password, code, p.challenge)) {
                        is LoginResult.Success -> finishLogin(result.token)
                        is LoginResult.TwoFactorRequired -> {
                            pending = null
                            _state.value = ConnectionState.NeedsLogin(FailureKind.ChallengeExpired)
                        }
                        is LoginResult.Failure -> when (result.kind) {
                            FailureKind.InvalidCode, FailureKind.RateLimited ->
                                _state.value = ConnectionState.NeedsTwoFactor(result.kind)
                            else -> {
                                pending = null
                                _state.value = ConnectionState.NeedsLogin(result.kind)
                            }
                        }
                    }
                } catch (e: SocketCallException) {
                    _state.value = ConnectionState.NeedsTwoFactor(FailureKind.Network)
                }
            }
        }
    }

    private fun finishLogin(token: String) {
        vault.put(instanceId, token)
        pending = null
        _state.value = ConnectionState.Connected
    }

    private fun onSocketState(s: SocketState) {
        when (s) {
            // The library reconnects on its own, so a drop is "connecting" again. A pending 2FA challenge belongs to
            // the old socket and is useless on the new one.
            SocketState.Disconnected, SocketState.Connecting -> {
                pending = null
                _state.value = ConnectionState.Connecting
            }
            is SocketState.Connected -> scope.launch { onConnected(s) }
            is SocketState.Error -> _state.value = ConnectionState.Failed(s.message)
        }
    }

    private suspend fun onConnected(connected: SocketState.Connected, attempt: Int = 0) {
        authLock.withLock {
            if (socket.state.value != connected) return
            val token = vault.get(instanceId)
            if (token == null) {
                _state.value = ConnectionState.NeedsLogin()
                return
            }
            _state.value = ConnectionState.LoggingIn
            try {
                when (val result = auth.loginByToken(token)) {
                    TokenLoginResult.Ok -> _state.value = ConnectionState.Connected
                    is TokenLoginResult.Failure ->
                        if (result.kind == FailureKind.Revoked) {
                            vault.remove(instanceId)
                            _state.value = ConnectionState.NeedsLogin(FailureKind.Revoked)
                        } else {
                            // Keep the token: a transient server problem must not log the user out.
                            _state.value = ConnectionState.Failed(result.message)
                        }
                }
            } catch (e: SocketCallException) {
                _state.value = ConnectionState.Failed(e.message ?: "No answer from the server.")
                scheduleRetry(connected, attempt + 1)
            }
        }
    }

    private var retryJob: Job? = null

    /**
     * A connected socket that does not answer the token login (typical right after the phone wakes up, when the old
     * connection is half dead) must not stay failed: ask again with a growing delay. A new connect event restarts it.
     */
    private fun scheduleRetry(connected: SocketState.Connected, attempt: Int = 1) {
        retryJob?.cancel()
        if (attempt > MAX_RETRIES) return
        retryJob = scope.launch {
            delay(minOf(2_000L shl (attempt - 1), 30_000L))
            if (socket.state.value != connected || _state.value !is ConnectionState.Failed) return@launch
            onConnected(connected, attempt)
        }
    }

    /**
     * Checks that a connection that looks fine still works (the phone may have slept). Returns false when the server
     * does not answer in [timeoutMs], so the caller can rebuild the socket. A session with no token is fine as is.
     */
    suspend fun verify(timeoutMs: Long = 5_000L): Boolean {
        val token = vault.get(instanceId) ?: return true
        return try {
            auth.loginByToken(token, timeoutMs) == TokenLoginResult.Ok
        } catch (e: SocketCallException) {
            false
        }
    }

    private companion object {
        const val MAX_RETRIES = 6
    }
}
