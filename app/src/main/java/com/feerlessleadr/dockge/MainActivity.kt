package com.feerlessleadr.dockge

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
            )
        }
        composable(
            route = "edit?id={id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            InstanceEditScreen(vm = vm, id = entry.arguments?.getString("id"), onDone = { nav.popBackStack() })
        }
    }
}
