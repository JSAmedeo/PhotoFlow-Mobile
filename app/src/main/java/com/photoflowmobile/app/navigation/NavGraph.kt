package com.photoflowmobile.app.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.photoflowmobile.app.ui.screens.ConfigScreen
import com.photoflowmobile.app.ui.screens.MainScreen
import com.photoflowmobile.app.ui.screens.ScanCardScreen
import com.photoflowmobile.app.viewmodel.MainViewModel

sealed class Screen(val route: String) {
    object ScanCard : Screen("scan_card")
    object Main : Screen("main")
    object Config : Screen("config")
}

@Composable
fun PhotoFlowNavGraph(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Main.route
    ) {
        composable(Screen.ScanCard.route) {
            ScanCardScreen(
                onSessionStarted = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(Screen.Main.route) {
            // Scope MainViewModel to the Activity, not the NavBackStackEntry, so it's the
            // same instance MainActivity holds. Otherwise we get two MainViewModels and two
            // MtpCameraManagers — both racing to claim the camera's USB interface.
            val activity = LocalContext.current as ComponentActivity
            MainScreen(
                viewModel = viewModel(activity),
                onMenuClick = { navController.navigate(Screen.Config.route) },
                onNewSession = { navController.navigate(Screen.ScanCard.route) }
            )
        }
        composable(Screen.Config.route) {
            ConfigScreen(onNavigateBack = { navController.popBackStack() })
        }
    }
}
