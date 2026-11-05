package com.feerlessleadr.dockge.ui.debug

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.feerlessleadr.dockge.BuildConfig
import com.feerlessleadr.dockge.api.SocketIoDockgeSocket
import com.feerlessleadr.dockge.api.StackListParser
import com.feerlessleadr.dockge.core.BaseUrl
import com.feerlessleadr.dockge.core.KeystoreTokenVault
import com.feerlessleadr.dockge.data.ConnectionState
import com.feerlessleadr.dockge.data.InstanceSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * A throw-away screen for M1 (debug builds only): connect to one instance, sign in (with 2FA) and list the stack names.
 * It reads only. It sends no start, stop, restart or update call.
 */
class DebugViewModel(app: Application) : AndroidViewModel(app) {
    private val vault = KeystoreTokenVault(app)
    private var session: InstanceSession? = null

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _stacks = MutableStateFlow<List<String>?>(null)
    val stacks: StateFlow<List<String>?> = _stacks.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun connect(address: String) {
        val url = BaseUrl.parse(address, allowCleartext = BuildConfig.DEBUG).getOrElse {
            _error.value = it.message
            return
        }
        _error.value = null
        session?.stop()
        val socket = SocketIoDockgeSocket(url)
        val s = InstanceSession(INSTANCE_ID, socket, vault, viewModelScope)
        session = s
        viewModelScope.launch { s.state.collect { _connection.value = it } }
        viewModelScope.launch {
            socket.events("agent").collect { args -> StackListParser.names(args)?.let { _stacks.value = it } }
        }
        s.start()
    }

    fun submitCredentials(username: String, password: String) = session?.submitCredentials(username, password)

    fun submitCode(code: String) = session?.submitCode(code)

    fun signOut() {
        session?.signOut()
        _stacks.value = null
    }

    override fun onCleared() {
        session?.stop()
    }

    private companion object {
        const val INSTANCE_ID = "debug"
    }
}
