package com.deivid22srk.portstore.launcher

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExitToApp
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.deivid22srk.portstore.R
import com.deivid22srk.portstore.catalog.Game
import com.deivid22srk.portstore.launcher.input.LocalGamepadMonitor

/**
 * Home do Game Launcher, inspirada na home do Nintendo Switch:
 * - linha horizontal de jogos instalados com o jogo focado maior e em destaque;
 * - nome do jogo focado exibido acima da linha;
 * - barra superior com hora/bateria/conexão/perfil;
 * - barra inferior de ícones circulares (Loja, Downloads, Configurações, Sair);
 * - dicas de botões no rodapé, mudando conforme o último tipo de entrada.
 *
 * Ordenação: "jogados recentemente" primeiro (dado registrado ao abrir um
 * jogo); sem esse dado, ordem alfabética.
 */
@Composable
fun LauncherHomeScreen(
    viewModel: LauncherViewModel,
    onOpenDetails: (String) -> Unit,
    onOpenStore: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onExitLauncher: () -> Unit,
) {
    val context = LocalContext.current
    val games by viewModel.installedGames.collectAsStateWithLifecycle()
    val monitor = LocalGamepadMonitor.current
    val gamepadInput = if (monitor != null) {
        monitor.lastInputGamepad.collectAsStateWithLifecycle().value
    } else {
        false
    }

    var focusedGame by remember { mutableStateOf<Game?>(null) }
    var optionsMenuGame by remember { mutableStateOf<Game?>(null) }
    var confirmUninstallGame by remember { mutableStateOf<Game?>(null) }

    val openError = stringResource(R.string.launcher_open_error)

    // Foco inicial/restaurado: volta para o card de onde o usuário saiu;
    // sem memória, foca o primeiro jogo (ou o botão da loja se não houver).
    val cardRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val storeButtonRequester = remember { FocusRequester() }
    val initialFocusDone = remember { mutableStateOf(false) }

    // Quando a tela de carregamento/erro fecha, devolve o foco ao card do
    // jogo (o foco do gamepad nunca fica "perdido"). Só age DEPOIS de uma
    // abertura — nunca rouba o foco na entrada normal da tela.
    val gexState by GameExperienceController.state.collectAsStateWithLifecycle()
    var gexWasActive by remember { mutableStateOf(false) }
    LaunchedEffect(gexState) {
        if (gexState is GameExperienceController.State.Idle) {
            if (gexWasActive) {
                gexWasActive = false
                delay(80)
                viewModel.homeFocusedGameId
                    ?.let { id -> cardRequesters[id] }
                    ?.let { runCatching { it.requestFocus() } }
            }
        } else {
            gexWasActive = true
        }
    }
    LaunchedEffect(games.isNotEmpty()) {
        if (initialFocusDone.value) return@LaunchedEffect
        if (games.isNotEmpty()) {
            val target = viewModel.homeFocusedGameId
                ?.let { id -> games.firstOrNull { it.id == id } }
                ?: games.first()
            cardRequesters[target.id]?.let { requester ->
                runCatching { requester.requestFocus() }
                focusedGame = target
            }
            initialFocusDone.value = true
        } else {
            runCatching { storeButtonRequester.requestFocus() }
            initialFocusDone.value = true
        }
    }

    fun openGame(game: Game) {
        val activity = context.findLauncherActivity()
        if (activity != null) {
            // Abertura estilo console: tela de carregamento do Port Store
            // (Recurso 1) com prontidão real do jogo — virtual ou normal.
            viewModel.markGamePlayed(game)
            GameExperienceController.launch(activity, game)
        } else if (!viewModel.launchGame(context, game)) {
            Toast.makeText(context, openError, Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        LauncherTopBar()
        Spacer(Modifier.height(8.dp))

        // Nome do jogo focado, como na home do Switch.
        Text(
            text = focusedGame?.title.orEmpty(),
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 32.dp),
        )

        if (games.isEmpty()) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                LauncherEmptyState(
                    message = stringResource(R.string.launcher_empty_message),
                    actionLabel = stringResource(R.string.launcher_empty_action),
                    onAction = onOpenStore,
                )
            }
        } else {
            BoxWithConstraints(Modifier.weight(1f)) {
                // Em paisagem apertada os cards encolhem; em retrato crescem até o teto.
                val cardSize = minOf(172.dp, maxHeight * 0.78f)
                LazyRow(
                    verticalAlignment = Alignment.CenterVertically,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(games, key = { it.id }) { game ->
                        LauncherGameCard(
                            game = game,
                            size = cardSize,
                            onClick = { openGame(game) },
                            onOptions = { optionsMenuGame = game },
                            onFocusChange = { focused ->
                                if (focused) {
                                    focusedGame = game
                                    viewModel.homeFocusedGameId = game.id
                                }
                            },
                            focusRequester = cardRequesters.getOrPut(game.id) { FocusRequester() },
                        )
                    }
                }
            }
        }

        LauncherBottomBar(
            items = listOf(
                LauncherBottomItem(
                    icon = Icons.Rounded.Storefront,
                    label = stringResource(R.string.launcher_menu_store),
                    onClick = onOpenStore,
                    focusRequester = storeButtonRequester,
                ),
                LauncherBottomItem(
                    icon = Icons.Rounded.Download,
                    label = stringResource(R.string.launcher_menu_downloads),
                    onClick = onOpenDownloads,
                ),
                LauncherBottomItem(
                    icon = Icons.Rounded.Settings,
                    label = stringResource(R.string.launcher_menu_settings),
                    onClick = onOpenSettings,
                ),
                LauncherBottomItem(
                    icon = Icons.Rounded.ExitToApp,
                    label = stringResource(R.string.launcher_menu_exit),
                    onClick = onExitLauncher,
                ),
            ),
        )

        Spacer(Modifier.height(6.dp))
        LauncherHints(
            stringResource(
                if (gamepadInput) R.string.launcher_hint_home else R.string.launcher_hint_touch,
            ),
        )
    }

    // Menu de opções do jogo (Y no gamepad / toque longo no card).
    optionsMenuGame?.let { game ->
        LauncherMenuDialog(
            title = game.title,
            items = listOf(
                LauncherMenuItem(
                    icon = Icons.Rounded.PlayArrow,
                    label = stringResource(R.string.launcher_action_open),
                ) {
                    optionsMenuGame = null
                    openGame(game)
                },
                LauncherMenuItem(
                    icon = Icons.Rounded.Info,
                    label = stringResource(R.string.launcher_action_details),
                ) {
                    optionsMenuGame = null
                    viewModel.homeFocusedGameId = game.id
                    onOpenDetails(game.id)
                },
                LauncherMenuItem(
                    icon = Icons.Rounded.Delete,
                    label = stringResource(R.string.launcher_action_uninstall),
                    destructive = true,
                ) {
                    optionsMenuGame = null
                    confirmUninstallGame = game
                },
            ),
            onDismiss = { optionsMenuGame = null },
        )
    }

    // Confirmação de desinstalação (virtual: jogo + dados; Android: sistema).
    confirmUninstallGame?.let { game ->
        val isVirtual = viewModel.isVirtualGame(game)
        LauncherConfirmDialog(
            title = stringResource(R.string.launcher_uninstall_title),
            message = if (isVirtual) {
                stringResource(R.string.virtual_uninstall_message, game.title)
            } else {
                stringResource(R.string.launcher_uninstall_message, game.title)
            },
            confirmLabel = stringResource(R.string.launcher_action_uninstall),
            onConfirm = {
                confirmUninstallGame = null
                if (isVirtual) {
                    viewModel.uninstallVirtualGame(game)
                } else {
                    viewModel.uninstallIntent(game)?.let { intent ->
                        runCatching { context.startActivity(intent) }
                    }
                }
            },
            onDismiss = { confirmUninstallGame = null },
        )
    }
}
