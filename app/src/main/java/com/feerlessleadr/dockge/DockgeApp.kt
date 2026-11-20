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
import com.feerlessleadr.dockge.notify.AlertController
import com.feerlessleadr.dockge.notify.AlertEngine
import com.feerlessleadr.dockge.notify.AlertNotifier
import com.feerlessleadr.dockge.notify.AlertTarget
import com.feerlessleadr.dockge.notify.AlertWatcher
import com.feerlessleadr.dockge.notify.MonitoringClient
import com.feerlessleadr.dockge.notify.PrefsAlertSettingsStore
import com.feerlessleadr.dockge.notify.PrefsSeenStore
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
    // Alerts: the app watches the saved instances itself (no notification server). New updates come from the update
    // status the sessions already keep; crash loops, unhealthy containers, failed backups and security findings from
    // each instance's monitoring feed. Both go through one engine that remembers what it already told the owner.
    private val alertSettings = PrefsAlertSettingsStore(app)
    private val alertEngine = AlertEngine(PrefsSeenStore(app), alertSettings::load, notifier::post)
    val alerts = AlertController(
        app,
        AlertWatcher(
            instances = instances.instances,
            states = sessions.states,
            updates = sessions.updates,
            stacksOf = { id -> sessions.stackLists.value[id].orEmpty().flatMap { s -> s.stacks.map { s.endpoint to it.name } } },
            token = vault::get,
            source = MonitoringClient(),
            engine = alertEngine,
            settingsStore = alertSettings,
            scope = scope,
        ),
        alertSettings,
    )

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
        // Alerts: while the process lives the watcher runs; the foreground service keeps the process alive.
        container.alerts.watcher.apply()
    }
}
