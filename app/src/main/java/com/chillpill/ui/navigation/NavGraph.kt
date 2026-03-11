package com.chillpill.ui.navigation

import com.chillpill.ChillpillApp
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.chillpill.ui.home.HomeScreen
import com.chillpill.ui.setup.SetupScreen
import com.chillpill.ui.settings.AppSelectionScreen
import com.chillpill.ui.settings.SettingsScreen
import com.chillpill.ui.statistics.StatisticsScreen

object Routes {
    const val HOME = "home"
    const val SETUP = "setup"
    const val SETTINGS = "settings"
    const val APP_SELECTION = "app_selection"
    const val STATISTICS = "statistics"
}

private const val NavTransitionDuration = 300

@Composable
fun ChillpillNavHost(
    app: ChillpillApp,
    startDestination: String,
    onFixPermissions: () -> Unit = {},
    onFixUsageAccess: () -> Unit = {},
    openSettingsOnLaunch: Boolean = false,
    navController: NavHostController = rememberNavController()
): NavHostController {
    LaunchedEffect(openSettingsOnLaunch) {
        if (openSettingsOnLaunch) navController.navigate(Routes.SETTINGS)
    }
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(
            route = Routes.SETUP,
            enterTransition = { slideInHorizontally(tween(NavTransitionDuration)) { it } },
            exitTransition = { slideOutHorizontally(tween(NavTransitionDuration)) { -it / 4 } },
            popEnterTransition = { slideInHorizontally(tween(NavTransitionDuration)) { -it / 4 } },
            popExitTransition = { slideOutHorizontally(tween(NavTransitionDuration)) { it } }
        ) {
            SetupScreen(
                app = app,
                onFixPermissions = onFixPermissions,
                onFixUsageAccess = onFixUsageAccess,
                onOpenAppSelection = { navController.navigate(Routes.APP_SELECTION) },
                onComplete = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.SETUP) { inclusive = true }
                    }
                }
            )
        }
        composable(
            route = Routes.HOME,
            enterTransition = { slideInHorizontally(tween(NavTransitionDuration)) { it } },
            exitTransition = { slideOutHorizontally(tween(NavTransitionDuration)) { -it / 4 } },
            popEnterTransition = { slideInHorizontally(tween(NavTransitionDuration)) { -it / 4 } },
            popExitTransition = { slideOutHorizontally(tween(NavTransitionDuration)) { it } }
        ) {
            HomeScreen(
                app = app,
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenStatistics = { navController.navigate(Routes.STATISTICS) },
                onFixPermissions = onFixPermissions,
                onFixUsageAccess = onFixUsageAccess
            )
        }
        composable(
            route = Routes.SETTINGS,
            enterTransition = { slideInHorizontally(tween(NavTransitionDuration)) { it } },
            exitTransition = { slideOutHorizontally(tween(NavTransitionDuration)) { -it / 4 } },
            popEnterTransition = { slideInHorizontally(tween(NavTransitionDuration)) { -it / 4 } },
            popExitTransition = { slideOutHorizontally(tween(NavTransitionDuration)) { it } }
        ) {
            SettingsScreen(
                app = app,
                onBack = { navController.popBackStack() },
                onEditMonitoredApps = { navController.navigate(Routes.APP_SELECTION) }
            )
        }
        composable(
            route = Routes.APP_SELECTION,
            enterTransition = { slideInHorizontally(tween(NavTransitionDuration)) { it } },
            exitTransition = { slideOutHorizontally(tween(NavTransitionDuration)) { -it / 4 } },
            popEnterTransition = { slideInHorizontally(tween(NavTransitionDuration)) { -it / 4 } },
            popExitTransition = { slideOutHorizontally(tween(NavTransitionDuration)) { it } }
        ) {
            AppSelectionScreen(
                app = app,
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Routes.STATISTICS,
            enterTransition = { slideInHorizontally(tween(NavTransitionDuration)) { it } },
            exitTransition = { slideOutHorizontally(tween(NavTransitionDuration)) { -it / 4 } },
            popEnterTransition = { slideInHorizontally(tween(NavTransitionDuration)) { -it / 4 } },
            popExitTransition = { slideOutHorizontally(tween(NavTransitionDuration)) { it } }
        ) {
            StatisticsScreen(
                app = app,
                onBack = { navController.popBackStack() }
            )
        }
    }
    return navController
}
