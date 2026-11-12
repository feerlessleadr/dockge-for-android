package com.feerlessleadr.dockge

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import com.feerlessleadr.dockge.api.SocketIoDockgeSocket
import com.feerlessleadr.dockge.core.KeystoreTokenVault
import com.feerlessleadr.dockge.core.PrefsInstanceStore
import com.feerlessleadr.dockge.core.TokenVault
import com.feerlessleadr.dockge.data.InstanceRepository
import com.feerlessleadr.dockge.data.SessionManager
import com.feerlessleadr.dockge.notify.AlertClassifier
import com.feerlessleadr.dockge.notify.AlertNotifier
import com.feerlessleadr.dockge.notify.AlertRouting
import com.feerlessleadr.dockge.notify.AlertTarget
import com.feerlessleadr.dockge.notify.NtfyClient
import com.feerlessleadr.dockge.notify.NtfyController
import com.feerlessleadr.dockge.notify.NtfyListener
import com.feerlessleadr.dockge.notify.PrefsNtfyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Plain constructor injection: the app is small, so there is no DI framework. */
class AppContainer(app: Application) {
    val vault: TokenVault = KeystoreTokenVault(app)
    val instances = InstanceRepository(PrefsInstanceStore(app), vault, allowCleartext = BuildConfig.DEBUG)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val sessions = SessionManager(instances, vault, scope) { url -> SocketIoDockgeSocket(url) }

    /** A tapped alert asks the UI to open this; the UI clears it once it has navigated. */
    val pendingTarget = MutableStateFlow<AlertTarget?>(null)

    val notifier = AlertNotifier(app)
    private val ntfyStore = PrefsNtfyStore(app)
    private val ntfyClient = NtfyClient()
    private val ntfyListener = NtfyListener(ntfyStore, vault, ntfyClient, scope) { message ->
        val kind = AlertClassifier.kind(message.title, message.message)
        val target = AlertRouting.resolve(message, instances.instances.value) { id ->
            sessions.stackLists.value[id].orEmpty().flatMap { server -> server.stacks.map { server.endpoint to it.name } }
        }
        notifier.post(message, kind, target)
    }
    val ntfy = NtfyController(app, ntfyStore, vault, ntfyListener, ntfyClient, allowCleartext = BuildConfig.DEBUG)

    init {
        // When a network (or the VPN tunnel) comes back, do not wait for the socket library's backoff: rebuild the
        // sockets that are not connected. A socket opened in the last 2 seconds is still trying and is left alone.
        val cm = app.getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch { sessions.recover(minAgeMs = 2_000) }
            }
        })
    }
}

class DockgeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Saved instances reconnect (with their saved tokens) as soon as the process starts.
        container.sessions.start()
        // Alerts: while the process lives the ntfy stream runs; the foreground service keeps the process alive.
        container.ntfy.listener.apply()
    }
}
