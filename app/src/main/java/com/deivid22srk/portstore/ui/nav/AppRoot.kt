package com.deivid22srk.portstore.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.deivid22srk.portstore.AppGraph
import com.deivid22srk.portstore.ui.detail.DetailViewModel
import com.deivid22srk.portstore.ui.detail.GameDetailScreen
import com.deivid22srk.portstore.ui.downloads.DownloadsScreen
import com.deivid22srk.portstore.ui.games.GamesScreen
import com.deivid22srk.portstore.ui.home.CatalogScreen
import com.deivid22srk.portstore.ui.home.CatalogViewModel
import com.deivid22srk.portstore.ui.search.SearchScreen
import com.deivid22srk.portstore.ui.you.AboutScreen
import com.deivid22srk.portstore.ui.you.InstalledGamesScreen
import com.deivid22srk.portstore.ui.you.LegalScreen
import com.deivid22srk.portstore.ui.you.YouScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import android.app.Application

data class TopLevelTab(
    val route: String,
    val label: String,
    val iconOutlined: ImageVector,
    val iconSelected: ImageVector,
)

private val tabs = listOf(
    TopLevelTab("home", "Início", Icons.Outlined.Home, Icons.Rounded.Home),
    TopLevelTab("games", "Jogos", Icons.Outlined.SportsEsports, Icons.Rounded.SportsEsports),
    TopLevelTab("search", "Pesquisa", Icons.Outlined.Search, Icons.Rounded.Search),
    TopLevelTab("downloads", "Downloads", Icons.Outlined.Download, Icons.Rounded.Download),
    TopLevelTab("you", "Você", Icons.Outlined.Person, Icons.Rounded.Person),
)

@Composable
fun AppRoot(startTab: String) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBar = tabs.any { it.route == currentRoute }

    val activeDownloads = AppGraph.downloads.items
        .collectAsStateWithLifecycle()
        .value
        .count { it.isActive }

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = currentRoute == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                if (tab.route == "downloads" && activeDownloads > 0) {
                                    BadgedBox(badge = { Badge { Text("$activeDownloads") } }) {
                                        Icon(
                                            if (selected) tab.iconSelected else tab.iconOutlined,
                                            contentDescription = tab.label,
                                        )
                                    }
                                } else {
                                    Icon(
                                        if (selected) tab.iconSelected else tab.iconOutlined,
                                        contentDescription = tab.label,
                                    )
                                }
                            },
                            label = {
                                Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = if (startTab in tabs.map { it.route }) startTab else "home",
            modifier = Modifier.padding(padding),
        ) {
            composable("home") {
                val context = LocalContext.current
                val vm: CatalogViewModel = viewModel(key = "home") {
                    CatalogViewModel(AppGraph.catalog, CatalogViewModel.Mode.HOME)
                }
                CatalogScreen(
                    viewModel = vm,
                    mode = CatalogViewModel.Mode.HOME,
                    onOpenGame = { navController.navigate("game/$it") },
                    onOpenDownloads = { },
                    onGoToTab = { route -> navController.navigateTopLevel(route) },
                )
            }
            composable("games") {
                GamesScreen(
                    onOpenGame = { navController.navigate("game/$it") },
                    onGoToTab = { route -> navController.navigateTopLevel(route) },
                )
            }
            composable("search") {
                SearchScreen(
                    onOpenGame = { navController.navigate("game/$it") },
                    onBack = { navController.navigateTopLevel("home") },
                )
            }
            composable("downloads") {
                DownloadsScreen(
                    onOpenGame = { navController.navigate("game/$it") },
                )
            }
            composable("you") {
                YouScreen(
                    onOpenInstalled = { navController.navigate("installed") },
                    onOpenLegal = { navController.navigate("legal") },
                    onOpenAbout = { navController.navigate("about") },
                )
            }
            composable("installed") {
                InstalledGamesScreen(
                    onBack = { navController.popBackStack() },
                    onOpenGame = { navController.navigate("game/$it") },
                    onGoToTab = { route -> navController.navigateTopLevel(route) },
                )
            }
            composable("game/{gameId}") { entry ->
                val gameId = entry.arguments?.getString("gameId").orEmpty()
                val context = LocalContext.current
                val vm: DetailViewModel = viewModel(key = "detail-$gameId") {
                    DetailViewModel(
                        app = context.applicationContext as Application,
                        gameId = gameId,
                        catalogRepo = AppGraph.catalog,
                        downloads = AppGraph.downloads,
                        resolver = AppGraph.resolver,
                    )
                }
                GameDetailScreen(viewModel = vm, onBack = { navController.popBackStack() })
            }
            composable("legal") {
                LegalScreen(onBack = { navController.popBackStack() })
            }
            composable("about") {
                AboutScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

private fun androidx.navigation.NavController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
