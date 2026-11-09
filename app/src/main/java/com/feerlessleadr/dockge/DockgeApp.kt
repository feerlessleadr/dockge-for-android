package com.feerlessleadr.dockge

import android.app.Application
import com.feerlessleadr.dockge.api.SocketIoDockgeSocket
import com.feerlessleadr.dockge.core.KeystoreTokenVault
import com.feerlessleadr.dockge.core.PrefsInstanceStore
import com.feerlessleadr.dockge.core.TokenVault
import com.feerlessleadr.dockge.data.InstanceRepository
import com.feerlessleadr.dockge.data.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Plain constructor injection: the app is small, so there is no DI framework. */
class AppContainer(app: Application) {
    val vault: TokenVault = KeystoreTokenVault(app)
    val instances = InstanceRepository(PrefsInstanceStore(app), vault, allowCleartext = BuildConfig.DEBUG)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val sessions = SessionManager(instances, vault, scope) { url -> SocketIoDockgeSocket(url) }
}

class DockgeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Saved instances reconnect (with their saved tokens) as soon as the process starts.
        container.sessions.start()
    }
}
