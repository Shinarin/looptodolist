package com.gametodolist.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gametodolist.ui.calendar.CalendarScreen
import com.gametodolist.ui.category.CategoryScreen
import com.gametodolist.ui.edit.TaskEditScreen
import com.gametodolist.ui.home.HomeScreen
import com.gametodolist.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val CALENDAR = "calendar"
    const val SETTINGS = "settings"
    const val CATEGORIES = "categories"
    const val EDIT = "edit?templateId={templateId}&anchorDate={anchorDate}"

    fun edit(templateId: Long? = null, anchorDate: Long? = null) =
        "edit?templateId=${templateId ?: -1L}&anchorDate=${anchorDate ?: -1L}"
}

@Composable
fun AppNav() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val mainRoutes = listOf(Routes.HOME, Routes.CALENDAR, Routes.SETTINGS)

    fun navigateMain(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        bottomBar = {
            // 三个主功能页：清单 / 日历 / 设置
            if (currentRoute in mainRoutes) {
                // 选中态：主色图标文字 + primaryContainer 胶囊指示
                val itemColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer
                )
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == Routes.HOME,
                        onClick = { navigateMain(Routes.HOME) },
                        icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                        label = { Text("清单") },
                        colors = itemColors
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.CALENDAR,
                        onClick = { navigateMain(Routes.CALENDAR) },
                        icon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                        label = { Text("日历") },
                        colors = itemColors
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.SETTINGS,
                        onClick = { navigateMain(Routes.SETTINGS) },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("设置") },
                        colors = itemColors
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding),
            // 快速切换动画，避免拖沓感
            enterTransition = { fadeIn(tween(150)) },
            exitTransition = { fadeOut(tween(130)) },
            popEnterTransition = { fadeIn(tween(150)) },
            popExitTransition = { fadeOut(tween(130)) }
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onEditTask = { id -> navController.navigate(Routes.edit(id)) }
                )
            }
            composable(Routes.CALENDAR) {
                CalendarScreen(
                    onEditTask = { id -> navController.navigate(Routes.edit(id)) },
                    onAddTask = { dateMillis -> navController.navigate(Routes.edit(null, dateMillis)) }
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onManageCategories = { navController.navigate(Routes.CATEGORIES) }
                )
            }
            composable(Routes.CATEGORIES) {
                CategoryScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.EDIT,
                arguments = listOf(
                    navArgument("templateId") {
                        type = NavType.LongType
                        defaultValue = -1L
                    },
                    navArgument("anchorDate") {
                        type = NavType.LongType
                        defaultValue = -1L
                    }
                )
            ) {
                TaskEditScreen(
                    onBack = { navController.popBackStack() },
                    onManageCategories = { navController.navigate(Routes.CATEGORIES) }
                )
            }
        }
    }
}
