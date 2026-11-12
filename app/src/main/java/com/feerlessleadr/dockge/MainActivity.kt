package com.feerlessleadr.dockge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.feerlessleadr.dockge.notify.DeepLink
import com.feerlessleadr.dockge.ui.instances.DiagnosticsScreen
import com.feerlessleadr.dockge.ui.instances.InstanceEditScreen
import com.feerlessleadr.dockge.ui.instances.InstanceListScreen
import com.feerlessleadr.dockge.ui.instances.InstancesViewModel
import com.feerlessleadr.dockge.ui.instances.NotificationsScreen
import com.feerlessleadr.dockge.ui.stacks.StackDetailScreen
import com.feerlessleadr.dockge.ui.stacks.StackListScreen
import com.feerlessleadr.dockge.ui.theme.DockgeTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as DockgeApp).container

    override fun onStart() {
        super.onStart()
        // Coming back after the screen was off: sockets may be half dead, so check them and rebuild stale ones.
        container.sessions.recover()
        // The app is in the foreground, so this is a safe moment to (re)start the alert service if it is enabled.
        container.ntfy.startServiceIfEnabled()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A tap on an alert starts (or returns to) this activity with a dockge:// link.
        if (savedInstanceState == null) route(intent)
        setContent { DockgeTheme { AppNav() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        route(intent)
    }

    private fun route(intent: Intent?) {
        DeepLink.parse(intent?.dataString)?.let { container.pendingTarget.value = it }
    }
}

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    val vm: InstancesViewModel = viewModel()
    val pending = (androidx.compose.ui.platform.LocalContext.current.applicationContext as DockgeApp).container.pendingTarget
    val target by pending.collectAsState()
    LaunchedEffect(target) {
        val t = target ?: return@LaunchedEffect
        pending.value = null
        // An alert for an instance that is no longer saved just leaves the user on the list.
        if (vm.get(t.instanceId) == null) return@LaunchedEffect
        nav.popBackStack("list", inclusive = false)
        nav.navigate("stacks/${Uri.encode(t.instanceId)}")
        t.stackName?.let { nav.navigate("stack/${Uri.encode(t.instanceId)}/${Uri.encode(it)}?endpoint=${Uri.encode(t.endpoint)}") }
    }
    NavHost(navController = nav, startDestination = "list") {
        composable("list") {
            InstanceListScreen(
                vm = vm,
                onAdd = { nav.navigate("edit") },
                onEdit = { nav.navigate("edit?id=$it") },
                onOpen = { nav.navigate("stacks/${Uri.encode(it)}") },
                onDiagnostics = { nav.navigate("diag/${Uri.encode(it)}") },
                onNotifications = { nav.navigate("notifications") },
            )
        }
        composable("notifications") {
            NotificationsScreen(vm = vm, onBack = { nav.popBackStack() })
        }
        composable(
            route = "edit?id={id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            InstanceEditScreen(vm = vm, id = entry.arguments?.getString("id"), onDone = { nav.popBackStack() })
        }
        composable(
            route = "diag/{iid}",
            arguments = listOf(navArgument("iid") { type = NavType.StringType }),
        ) { entry ->
            val iid = entry.arguments?.getString("iid") ?: return@composable
            DiagnosticsScreen(vm = vm, instanceId = iid, onBack = { nav.popBackStack() })
        }
        composable(
            route = "stacks/{iid}",
            arguments = listOf(navArgument("iid") { type = NavType.StringType }),
        ) { entry ->
            val iid = entry.arguments?.getString("iid") ?: return@composable
            StackListScreen(
                vm = vm, instanceId = iid, onBack = { nav.popBackStack() },
                onOpen = { endpoint, name -> nav.navigate("stack/${Uri.encode(iid)}/${Uri.encode(name)}?endpoint=${Uri.encode(endpoint)}") },
            )
        }
        composable(
            route = "stack/{iid}/{name}?endpoint={endpoint}",
            arguments = listOf(
                navArgument("iid") { type = NavType.StringType },
                navArgument("name") { type = NavType.StringType },
                navArgument("endpoint") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val iid = entry.arguments?.getString("iid") ?: return@composable
            val name = entry.arguments?.getString("name") ?: return@composable
            val endpoint = entry.arguments?.getString("endpoint") ?: ""
            StackDetailScreen(vm = vm, instanceId = iid, endpoint = endpoint, stackName = name, onBack = { nav.popBackStack() })
        }
    }
}
