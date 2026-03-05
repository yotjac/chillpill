package com.chillpill.ui.navigation

import com.chillpill.ChillpillApp
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.chillpill.ui.home.HomeScreen
import com.chillpill.ui.settings.SettingsScreen
import com.chillpill.ui.statistics.StatisticsScreen

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val STATISTICS = "statistics"
}

private const val NavTransitionDuration = 300

@Composable
fun ChillpillNavHost(
    app: ChillpillApp,
    onFixPermissions: () -> Unit = {},
    navController: NavHostController = rememberNavController()
): NavHostController {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME
    ) {
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
                onFixPermissions = onFixPermissions
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
            StatisticsScreen(onBack = { navController.popBackStack() })
        }
    }
    return navController
}
