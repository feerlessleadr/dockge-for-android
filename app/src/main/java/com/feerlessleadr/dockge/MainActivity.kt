package com.feerlessleadr.dockge

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.feerlessleadr.dockge.ui.instances.InstanceEditScreen
import com.feerlessleadr.dockge.ui.instances.InstanceListScreen
import com.feerlessleadr.dockge.ui.instances.InstancesViewModel
import com.feerlessleadr.dockge.ui.stacks.StackDetailScreen
import com.feerlessleadr.dockge.ui.stacks.StackListScreen
import com.feerlessleadr.dockge.ui.theme.DockgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DockgeTheme { AppNav() } }
    }
}

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    val vm: InstancesViewModel = viewModel()
    NavHost(navController = nav, startDestination = "list") {
        composable("list") {
            InstanceListScreen(
                vm = vm,
                onAdd = { nav.navigate("edit") },
                onEdit = { nav.navigate("edit?id=$it") },
                onOpen = { nav.navigate("stacks/${Uri.encode(it)}") },
            )
        }
        composable(
            route = "edit?id={id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            InstanceEditScreen(vm = vm, id = entry.arguments?.getString("id"), onDone = { nav.popBackStack() })
        }
        composable(
            route = "stacks/{iid}",
            arguments = listOf(navArgument("iid") { type = NavType.StringType }),
        ) { entry ->
            val iid = entry.arguments?.getString("iid") ?: return@composable
            StackListScreen(
                vm = vm, instanceId = iid, onBack = { nav.popBackStack() },
                onOpen = { name -> nav.navigate("stack/${Uri.encode(iid)}/${Uri.encode(name)}") },
            )
        }
        composable(
            route = "stack/{iid}/{name}",
            arguments = listOf(navArgument("iid") { type = NavType.StringType }, navArgument("name") { type = NavType.StringType }),
        ) { entry ->
            val iid = entry.arguments?.getString("iid") ?: return@composable
            val name = entry.arguments?.getString("name") ?: return@composable
            StackDetailScreen(vm = vm, instanceId = iid, stackName = name, onBack = { nav.popBackStack() })
        }
    }
}
