package com.deivid22srk.portstore.launcher

import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.deivid22srk.portstore.AppGraph
import com.deivid22srk.portstore.R
import com.deivid22srk.portstore.launcher.input.GamepadMonitor
import com.deivid22srk.portstore.launcher.input.LauncherFeedbackConfig
import com.deivid22srk.portstore.launcher.input.LocalGamepadMonitor
import com.deivid22srk.portstore.launcher.input.LocalLauncherFeedback
import com.deivid22srk.portstore.launcher.input.launcherGamepadNav
import com.deivid22srk.portstore.ui.theme.LauncherTheme
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.launch

/** Rotas de nível superior do Game Launcher (para L1/R1). */
private val launcherTabs = listOf(
    "launcher_home",
    "launcher_store",
    "launcher_downloads",
    "launcher_settings",
)

/**
 * Raiz do modo Game Launcher: um "app dentro do app". Contém o NavHost das
 * telas do launcher, o tema escuro fixo, o modo tela cheia imersivo, o
 * monitor de gamepad (conexão/desconexão + último tipo de entrada) e o
 * diálogo de saída para a interface normal do Port Store. Ao desativar o
 * modo, o MainActivity volta a compor o AppRoot — sem perder nada.
 */
@Composable
fun LauncherRoot() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val viewModel: LauncherViewModel = viewModel {
        // Factory explícita: LauncherViewModel tem construtor com Application e
        // o factory padrão (reflexão) não encontra construtor vazio — crash.
        LauncherViewModel(context.applicationContext as Application)
    }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val monitor = remember { GamepadMonitor(context) }
    DisposableEffect(Unit) {
        monitor.start()
        onDispose { monitor.stop() }
    }
    val connected by monitor.connected.collectAsStateWithLifecycle()

    // Aviso discreto ao conectar/desconectar o controle em tempo real.
    var previousConnected by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(connected) {
        if (previousConnected != null && previousConnected != connected) {
            Toast.makeText(
                context,
                if (connected) {
                    R.string.launcher_gamepad_connected
                } else {
                    R.string.launcher_gamepad_disconnected
                },
                Toast.LENGTH_SHORT,
            ).show()
        }
        previousConnected = connected
    }

    // Tela cheia imersiva + modo console: travado em paisagem enquanto o
    // Launcher estiver ativo (barras e rotação livre voltam ao sair do modo).
    DisposableEffect(Unit) {
        val activity = context.findLauncherActivity()
        val controller = activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView)
        }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())

        // Paisagem nos dois lados (segue o sensor, como um portátil).
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            // Manifest não define orientation: UNSPECIFIED restaura a rotação
            // livre da interface normal do Port Store.
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Re-verifica instalações ao voltar ao app (ex.: após instalar um port).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                AppGraph.installedApps.refresh()
                AppGraph.virtual.refresh()
                // Selo "Em execução": processos virtuais vivos agora.
                AppGraph.virtual.refreshRunning()
            }
            if (event == Lifecycle.Event.ON_STOP) {
                // Algo cobriu o Launcher (ex.: jogo normal aberto): sinal de
                // prontidão da tela de carregamento.
                GameExperienceController.onHostActivityStopped()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Experiência de abertura (tela de carregamento/erro) — Recurso 1.
    val gexState by GameExperienceController.state.collectAsStateWithLifecycle()

    var showExitDialog by remember { mutableStateOf(false) }

    CompositionLocalProvider(
        LocalLauncherFeedback provides LauncherFeedbackConfig(
            haptics = settings.launcherHaptics,
            sounds = settings.launcherSounds,
        ),
        LocalGamepadMonitor provides monitor,
    ) {
        LauncherTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .launcherGamepadNav(
                            monitor = monitor,
                            onBack = {
                                // Enquanto a tela de carregamento/erro está ativa,
                                // B pertence a ela (cancelar/fechar) — o restante do
                                // Launcher não reage (nem abre diálogo de saída).
                                when (gexState) {
                                    is GameExperienceController.State.Loading -> GameExperienceController.cancel()
                                    is GameExperienceController.State.Error -> GameExperienceController.dismissError()
                                    GameExperienceController.State.Idle -> {
                                        // B volta uma tela; na Home abre o diálogo de saída.
                                        if (!navController.popBackStack()) showExitDialog = true
                                    }
                                }
                            },
                            onTabSwitch = { direction ->
                                if (gexState == GameExperienceController.State.Idle) {
                                    switchLauncherTab(navController, direction)
                                }
                            },
                            onStart = {
                                if (gexState == GameExperienceController.State.Idle) {
                                    navController.navigate("launcher_settings") { launchSingleTop = true }
                                }
                            },
                            onSearch = {
                                if (gexState == GameExperienceController.State.Idle) {
                                    navController.navigate("launcher_search") { launchSingleTop = true }
                                }
                            },
                        ),
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = "launcher_home",
                    ) {
                        composable("launcher_home") {
                            LauncherHomeScreen(
                                viewModel = viewModel,
                                onOpenDetails = { gameId ->
                                    navController.navigate("launcher_game/$gameId")
                                },
                                onOpenStore = {
                                    navController.navigate("launcher_store") { launchSingleTop = true }
                                },
                                onOpenDownloads = {
                                    navController.navigate("launcher_downloads") { launchSingleTop = true }
                                },
                                onOpenSettings = {
                                    navController.navigate("launcher_settings") { launchSingleTop = true }
                                },
                                onExitLauncher = { showExitDialog = true },
                            )
                        }
                        composable("launcher_store") {
                            LauncherStoreScreen(
                                viewModel = viewModel,
                                onOpenGame = { gameId ->
                                    navController.navigate("launcher_game/$gameId")
                                },
                                onOpenSearch = {
                                    navController.navigate("launcher_search") { launchSingleTop = true }
                                },
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable("launcher_game/{gameId}") { entry ->
                            val gameId = entry.arguments?.getString("gameId").orEmpty()
                            LauncherGameDetailScreen(
                                gameId = gameId,
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable("launcher_search") {
                            LauncherSearchScreen(
                                onOpenGame = { gameId ->
                                    navController.navigate("launcher_game/$gameId")
                                },
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable("launcher_downloads") {
                            LauncherDownloadsScreen(
                                viewModel = viewModel,
                                onBack = { navController.popBackStack() },
                                onOpenStore = {
                                    navController.navigate("launcher_store") { launchSingleTop = true }
                                },
                            )
                        }
                        composable("launcher_settings") {
                            LauncherSettingsScreen(
                                viewModel = viewModel,
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }

                    // Tela de carregamento / erro da abertura de jogos (Recurso 1).
                    // Sobre TODAS as telas do Launcher; sai em fade quando o jogo
                    // está na frente (ou o erro foi tratado). Durante o fade-out o
                    // estado já é Idle — o último conteúdo é retido para a saída
                    // ser suave (sem piscar).
                    var gexContent by remember {
                        mutableStateOf<GameExperienceController.State>(GameExperienceController.State.Idle)
                    }
                    if (gexState != GameExperienceController.State.Idle) {
                        gexContent = gexState
                    }
                    AnimatedVisibility(
                        visible = gexState != GameExperienceController.State.Idle,
                        enter = fadeIn(tween(200)),
                        exit = fadeOut(tween(280)),
                    ) {
                        GameLoadingOverlay(gexContent)
                    }
                }
            }
        }

        // Confirmação de saída para a interface normal do Port Store.
        if (showExitDialog) {
            LauncherConfirmDialog(
                title = stringResource(R.string.launcher_exit_title),
                message = stringResource(R.string.launcher_exit_message),
                confirmLabel = stringResource(R.string.launcher_exit_confirm),
                onConfirm = {
                    showExitDialog = false
                    scope.launch { AppGraph.settings.setGameLauncherEnabled(false) }
                },
                onDismiss = { showExitDialog = false },
            )
        }
    }
}

/** L1/R1: cicla entre as seções raiz do Launcher (Home, Loja, Downloads, Config). */
private fun switchLauncherTab(navController: NavHostController, direction: Int) {
    val current = navController.currentBackStackEntry?.destination?.route
    val index = launcherTabs.indexOf(current).takeIf { it >= 0 } ?: 0
    val next = (index + direction + launcherTabs.size) % launcherTabs.size
    navController.navigate(launcherTabs[next]) {
        popUpTo("launcher_home") { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
