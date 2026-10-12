package com.deivid22srk.portstore.launcher

import android.app.Application
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInbox
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deivid22srk.portstore.AppGraph
import com.deivid22srk.portstore.R
import com.deivid22srk.portstore.catalog.categoryLabel
import com.deivid22srk.portstore.core.DlState
import com.deivid22srk.portstore.install.InstallState
import com.deivid22srk.portstore.install.InstalledAppsMonitor
import com.deivid22srk.portstore.ui.virtual.virtualFailureRes
import com.deivid22srk.portstore.virtual.VirtualInstallPhase
import com.deivid22srk.portstore.virtual.VirtualInstallProgress
import com.deivid22srk.portstore.virtual.VirtualInstallStatus
import kotlinx.coroutines.delay
import com.deivid22srk.portstore.launcher.input.LauncherInitialFocus
import com.deivid22srk.portstore.ui.components.DownloadProgressBar
import com.deivid22srk.portstore.ui.components.GameImage
import com.deivid22srk.portstore.ui.components.Pill
import com.deivid22srk.portstore.ui.detail.DetailViewModel
import com.deivid22srk.portstore.ui.theme.Lime
import com.deivid22srk.portstore.ui.theme.TextPrimary
import com.deivid22srk.portstore.ui.theme.TextSecondary
import com.deivid22srk.portstore.util.LinkOpener

/**
 * Página do jogo no Game Launcher: capa, descrição, plataforma de origem,
 * tamanho e botão principal (Baixar / Instalar / Abrir / Atualizar) conforme
 * o estado — reutilizando o [DetailViewModel] e todo o fluxo de
 * download/instalação já existente do Port Store, com barra de progresso.
 */
@Composable
fun LauncherGameDetailScreen(
    gameId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val viewModel: DetailViewModel = viewModel(key = "launcher-detail-$gameId") {
        DetailViewModel(
            app = app,
            gameId = gameId,
            catalogRepo = AppGraph.catalog,
            downloads = AppGraph.downloads,
            resolver = AppGraph.resolver,
        )
    }

    val game by viewModel.game.collectAsStateWithLifecycle()
    val version by viewModel.version.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val item by viewModel.downloadItem.collectAsStateWithLifecycle()

    val monitor = com.deivid22srk.portstore.launcher.input.LocalGamepadMonitor.current
    val gamepadInput = if (monitor != null) {
        monitor.lastInputGamepad.collectAsStateWithLifecycle().value
    } else {
        false
    }

    val backRequester = remember { FocusRequester() }
    val actionRequester = remember { FocusRequester() }

    // Estado do modo Instalação Virtual para este jogo.
    val virtualMode by viewModel.virtualModeEnabled.collectAsStateWithLifecycle()
    val vApp by viewModel.virtualApp.collectAsStateWithLifecycle()
    var confirmVirtualUninstall by remember { mutableStateOf(false) }

    // Feedback da instalação virtual (sucesso/falha) no Launcher: o progresso
    // do tracker ([virtualAutoProgress] abaixo) é a fonte única — Toast no
    // sucesso, texto inline na falha. O virtualInstallEvent segue para o
    // GameDetailScreen do modo normal, que não tem a barra de progresso.

    // Progresso da instalação virtual deste jogo (automática pós-download OU
    // manual). Enquanto phase != DONE, a barra/fase substitui o botão de ação;
    // DONE com sucesso vira Toast e limpa (o botão passa a ser "Jogar").
    val vAutoRaw by viewModel.virtualAutoProgress.collectAsStateWithLifecycle()
    val vAuto = vAutoRaw
    LaunchedEffect(vAuto) {
        val upd = vAuto ?: return@LaunchedEffect
        if (upd.phase == VirtualInstallPhase.DONE) {
            if (upd.status is VirtualInstallStatus.Success) {
                Toast.makeText(context, R.string.virtual_install_done, Toast.LENGTH_SHORT).show()
                viewModel.clearVirtualAutoProgress()
            }
            // O foco do gamepad não fica "perdido" quando a barra substitui/
            // devolve o botão de ação.
            delay(120)
            runCatching { actionRequester.requestFocus() }
        }
    }

    // Desinstalação via desinstalador do sistema, com resultado devolvido ao VM.
    val uninstallLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.onUninstallResult(result.resultCode)
    }

    val g = game

    // Ao fechar a tela de carregamento/erro da abertura, devolve o foco ao
    // botão de ação (o foco do gamepad nunca fica "perdido"). Só age DEPOIS
    // de uma abertura — nunca rouba o foco na entrada normal da tela.
    val gexState by GameExperienceController.state.collectAsStateWithLifecycle()
    var gexWasActive by remember { mutableStateOf(false) }
    LaunchedEffect(gexState) {
        if (gexState is GameExperienceController.State.Idle) {
            if (gexWasActive) {
                gexWasActive = false
                delay(80)
                runCatching { actionRequester.requestFocus() }
            }
        } else {
            gexWasActive = true
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LauncherIconButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.launcher_hint_details),
                onClick = onBack,
                focusRequester = backRequester,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.launcher_details_title),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (g == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Lime)
            }
        } else {
            val state = item?.state
            val activeState = state != null && state in DlState.ACTIVE
            val installed = version.install as? InstallState.Installed
            val archiveInfo = remember(item?.destPath, item?.id, item?.state) {
                item?.takeIf { it.state == DlState.COMPLETED }
                    ?.let { InstalledAppsMonitor.archivePackageInfo(context, it.destPath) }
            }
            val completedIsPendingUpdate = archiveInfo != null && installed != null &&
                archiveInfo.longVersionCode > installed.versionCode
            // Comparação equivalente para a instalação virtual deste jogo.
            val virtualPendingUpdate = archiveInfo != null && vApp?.let { v ->
                    archiveInfo.longVersionCode > v.versionCode
                } == true
            val anythingInstalled = installed != null || vApp != null
            val completedIsCta = archiveInfo != null && (
                !anythingInstalled || completedIsPendingUpdate || virtualPendingUpdate
                )
            // Com o modo virtual ativo, o download concluído é instalado no
            // ambiente virtual por padrão ("Instalar no Android" fica como
            // alternativa explícita). O motor é inicializado sob demanda no
            // clique — se não subir, aviso pede reinício em vez do Android.
            val installVirtualFirst = virtualMode &&
                (installed == null || completedIsPendingUpdate)
            // Instalação virtual em andamento (automática pós-download ou
            // manual): a barra de progresso ao vivo substitui os botões.
            val vAutoInstall = vAuto?.takeIf { it.phase != VirtualInstallPhase.DONE }
            val vAutoFailure = vAuto
                ?.takeIf { it.phase == VirtualInstallPhase.DONE }
                ?.status as? VirtualInstallStatus.Failure

            Row(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp)) {
                // Capa
                GameImage(
                    url = g.coverUrl,
                    modifier = Modifier
                        .width(190.dp)
                        .fillMaxHeight(0.92f),
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.width(20.dp))

                // Informações e ações
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        g.title,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        g.categories.take(2).forEach { slug ->
                            Pill(categoryLabel(slug))
                        }
                        g.performanceLabel?.let { Pill(it, highlight = true) }
                        if (g.isWeb) Pill("Web", highlight = true)
                    }
                    Spacer(Modifier.height(12.dp))
                    InfoLine(
                        label = stringResource(R.string.launcher_label_original),
                        value = g.original ?: g.port ?: "—",
                    )
                    InfoLine(
                        label = stringResource(R.string.launcher_label_size),
                        value = listOfNotNull(g.apkSize?.takeIf { it.isNotBlank() }, g.storage?.takeIf { it.isNotBlank() })
                            .joinToString(" + ").ifEmpty { "—" },
                    )
                    (installed)?.versionName?.let {
                        InfoLine(
                            label = stringResource(R.string.launcher_installed),
                            value = it,
                        )
                    }
                    vApp?.let { v ->
                        InfoLine(
                            label = stringResource(R.string.launcher_installed_virtual),
                            value = v.versionName
                                ?: stringResource(R.string.virtual_version_unknown),
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    val description = g.shortDescription?.takeIf { it.isNotBlank() }
                        ?: g.description.joinToString("\n")
                    if (description.isNotBlank()) {
                        Text(
                            description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    // Barra de progresso durante o download.
                    if (item != null && (activeState || state == DlState.PAUSED)) {
                        DownloadProgressBar(
                            progress = item!!.progress,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = downloadProgressLabel(item!!),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                        Spacer(Modifier.height(10.dp))
                    }

                    // Botão principal conforme o estado (mesma máquina do app normal).
                    when {
                        g.isRemoved -> LauncherButton(
                            label = stringResource(R.string.launcher_removed),
                            onClick = {},
                            enabled = false,
                        )
                        g.isSoon -> LauncherButton(
                            label = stringResource(R.string.soon),
                            onClick = {},
                            enabled = false,
                        )
                        g.isWeb -> LauncherButton(
                            label = stringResource(R.string.play_in_browser),
                            onClick = { LinkOpener.open(context, g.playableUrl) },
                            icon = Icons.Rounded.SportsEsports,
                            container = Lime,
                            contentColor = androidx.compose.ui.graphics.Color(0xFF171800),
                            focusRequester = actionRequester,
                        )
                        ui.resolving -> LauncherButton(
                            label = stringResource(R.string.launcher_resolving),
                            onClick = {},
                            enabled = false,
                            icon = Icons.Rounded.Download,
                        )
                        vAutoInstall != null -> Column {
                            VirtualInstallProgressRow(update = vAutoInstall)
                            Spacer(Modifier.height(4.dp))
                        }
                        state != null && state in DlState.ACTIVE -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LauncherButton(
                                label = stringResource(R.string.launcher_action_pause),
                                onClick = viewModel::pause,
                                icon = Icons.Rounded.Pause,
                                focusRequester = actionRequester,
                            )
                            LauncherButton(
                                label = stringResource(R.string.cancel),
                                onClick = viewModel::cancel,
                            )
                        }
                        state == DlState.PAUSED -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LauncherButton(
                                label = stringResource(R.string.launcher_action_resume),
                                onClick = viewModel::resume,
                                icon = Icons.Rounded.PlayArrow,
                                container = Lime,
                                contentColor = androidx.compose.ui.graphics.Color(0xFF171800),
                                focusRequester = actionRequester,
                            )
                            LauncherButton(
                                label = stringResource(R.string.cancel),
                                onClick = viewModel::cancel,
                            )
                        }
                        state == DlState.FAILED -> LauncherButton(
                            label = stringResource(R.string.launcher_action_retry),
                            onClick = viewModel::retry,
                            icon = Icons.Rounded.Refresh,
                            focusRequester = actionRequester,
                        )
                        state == DlState.COMPLETED && completedIsCta -> Column {
                            if (installVirtualFirst) {
                                LauncherButton(
                                    label = if (vApp != null || installed != null) {
                                        stringResource(R.string.virtual_update_button)
                                    } else {
                                        stringResource(R.string.virtual_install_button)
                                    },
                                    onClick = { viewModel.installDownloadedApk(item!!.destPath) },
                                    icon = Icons.Rounded.AllInbox,
                                    container = Lime,
                                    contentColor = androidx.compose.ui.graphics.Color(0xFF171800),
                                    focusRequester = actionRequester,
                                )
                                Spacer(Modifier.height(6.dp))
                                LauncherButton(
                                    label = stringResource(R.string.virtual_install_android_alternative),
                                    onClick = { viewModel.installApk(item!!.destPath) },
                                    icon = Icons.Rounded.Download,
                                )
                            } else {
                                LauncherButton(
                                    label = stringResource(R.string.launcher_action_install),
                                    onClick = { viewModel.installApk(item!!.destPath) },
                                    icon = Icons.Rounded.Download,
                                    container = Lime,
                                    contentColor = androidx.compose.ui.graphics.Color(0xFF171800),
                                    focusRequester = actionRequester,
                                )
                            }
                        }
                        version.updateAvailable -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LauncherButton(
                                label = stringResource(R.string.launcher_action_update),
                                onClick = { viewModel.onInstallClicked(g) },
                                icon = Icons.Rounded.Download,
                                container = Lime,
                                contentColor = androidx.compose.ui.graphics.Color(0xFF171800),
                                focusRequester = actionRequester,
                            )
                            LauncherButton(
                                label = stringResource(R.string.launcher_action_open),
                                onClick = {
                                    val activity = context.findLauncherActivity()
                                    val game0 = g
                                    if (activity != null && game0 != null) {
                                        // Com tela de carregamento (Recurso 1).
                                        GameExperienceController.launch(activity, game0)
                                    } else {
                                        viewModel.openInstalled()
                                    }
                                },
                                icon = Icons.Rounded.PlayArrow,
                            )
                        }
                        vApp != null -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LauncherButton(
                                label = stringResource(R.string.virtual_play_button),
                                onClick = {
                                    // Gate do repositório de dados: dados pendentes
                                    // disparam o download; em andamento, bloqueiam.
                                    when (com.deivid22srk.portstore.AppGraph.dataRepo.gateFor(gameId)) {
                                        com.deivid22srk.portstore.gamedata.GameDataInstaller.Gate.RUNNING -> {
                                            Toast.makeText(context, R.string.data_repo_busy, Toast.LENGTH_LONG).show()
                                            return@LauncherButton
                                        }
                                        com.deivid22srk.portstore.gamedata.GameDataInstaller.Gate.PENDING -> {
                                            com.deivid22srk.portstore.AppGraph.dataRepo.startInstall(gameId)
                                            Toast.makeText(context, R.string.data_repo_started, Toast.LENGTH_LONG).show()
                                            return@LauncherButton
                                        }
                                        com.deivid22srk.portstore.gamedata.GameDataInstaller.Gate.OPEN -> Unit
                                    }
                                    val activity = context.findLauncherActivity()
                                    val game0 = g
                                    if (activity != null && game0 != null) {
                                        // Com tela de carregamento (Recurso 1).
                                        GameExperienceController.launch(activity, game0)
                                    } else if (!viewModel.openVirtual()) {
                                        Toast.makeText(
                                            context,
                                            R.string.virtual_open_failed,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                                icon = Icons.Rounded.PlayArrow,
                                container = Lime,
                                contentColor = androidx.compose.ui.graphics.Color(0xFF171800),
                                focusRequester = actionRequester,
                            )
                            LauncherIconButton(
                                icon = Icons.Rounded.Delete,
                                contentDescription = stringResource(R.string.launcher_action_uninstall),
                                onClick = {
                                    if (installed == null) {
                                        confirmVirtualUninstall = true
                                    } else {
                                        viewModel.buildUninstallIntent()?.let { intent ->
                                            runCatching { uninstallLauncher.launch(intent) }
                                        }
                                    }
                                },
                            )
                        }
                        installed != null -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LauncherButton(
                                label = stringResource(R.string.open),
                                onClick = {
                                    val activity = context.findLauncherActivity()
                                    val game0 = g
                                    if (activity != null && game0 != null) {
                                        // Com tela de carregamento (Recurso 1).
                                        GameExperienceController.launch(activity, game0)
                                    } else {
                                        viewModel.openInstalled()
                                    }
                                },
                                icon = Icons.Rounded.PlayArrow,
                                container = Lime,
                                contentColor = androidx.compose.ui.graphics.Color(0xFF171800),
                                focusRequester = actionRequester,
                            )
                            LauncherIconButton(
                                icon = Icons.Rounded.Delete,
                                contentDescription = stringResource(R.string.launcher_action_uninstall),
                                onClick = {
                                    viewModel.buildUninstallIntent()?.let { intent ->
                                        runCatching { uninstallLauncher.launch(intent) }
                                    }
                                },
                            )
                        }
                        else -> LauncherButton(
                            label = stringResource(R.string.launcher_action_download),
                            onClick = { viewModel.onInstallClicked(g) },
                            icon = Icons.Rounded.Download,
                            container = Lime,
                            contentColor = androidx.compose.ui.graphics.Color(0xFF171800),
                            focusRequester = actionRequester,
                        )
                    }

                    // Erros e avisos (sem internet, APK não encontrado etc.).
                    ui.resolveError?.let { error ->
                        Spacer(Modifier.height(10.dp))
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    // Falha da instalação virtual (automática ou manual): texto
                    // inline persistente até o progresso ser limpo (auto em ~15s).
                    vAutoFailure?.let { failure ->
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(
                                R.string.virtual_auto_failed,
                                stringResource(virtualFailureRes(failure.reason)),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    ui.installWarning?.let { warning ->
                        Spacer(Modifier.height(10.dp))
                        Text(
                            warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                    if (version.rateLimited) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.github_rate_limit),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                }
            }
        }

        LauncherHints(
            stringResource(
                if (gamepadInput) R.string.launcher_hint_details else R.string.launcher_hint_touch,
            ),
        )
    }

    // Escolha de variante do APK (quando o release tem mais de um asset).
    ui.showVariants?.let { assets ->
        val currentGame = game
        if (currentGame != null) {
            LauncherMenuDialog(
                title = stringResource(R.string.choose_variant),
                items = assets.map { asset ->
                    LauncherMenuItem(
                        icon = Icons.Rounded.Download,
                        label = asset.name +
                            (if (asset.size > 0) {
                                " · " + com.deivid22srk.portstore.util.Formatters.formatBytes(asset.size)
                            } else {
                                ""
                            }),
                    ) {
                        viewModel.startDownload(currentGame, asset)
                    }
                },
                onDismiss = viewModel::dismissVariants,
            )
        }
    }

    // Confirmação de desinstalação do jogo virtual (apaga jogo + dados).
    if (confirmVirtualUninstall) {
        LauncherConfirmDialog(
            title = stringResource(R.string.launcher_uninstall_title),
            message = stringResource(
                R.string.virtual_uninstall_message,
                game?.title.orEmpty(),
            ),
            confirmLabel = stringResource(R.string.launcher_action_uninstall),
            onConfirm = {
                confirmVirtualUninstall = false
                viewModel.uninstallVirtual()
            },
            onDismiss = { confirmVirtualUninstall = false },
        )
    }

    LauncherInitialFocus(backRequester)
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun downloadProgressLabel(item: com.deivid22srk.portstore.core.DownloadItem): String = when (item.state) {
    DlState.VERIFYING -> stringResource(R.string.launcher_state_verifying)
    DlState.CONNECTING, DlState.QUEUED -> stringResource(R.string.launcher_state_connecting)
    DlState.PAUSED ->
        stringResource(R.string.launcher_dl_paused) + " " + (item.progress * 100).toInt() + "%"
    else -> stringResource(R.string.launcher_state_downloading_pct, (item.progress * 100).toInt())
}

/**
 * Barra e fase da instalação virtual em andamento na tela de detalhes do
 * Game Launcher — instalação automática (pós-download) ou manual. VERIFYING,
 * DOWNLOADING_DATA e INSTALLING_DATA têm percentual real; PREPARING/INSTALLING
 * são indeterminados.
 */
@Composable
private fun VirtualInstallProgressRow(update: VirtualInstallProgress) {
    Column {
        when (update.phase) {
            VirtualInstallPhase.VERIFYING,
            VirtualInstallPhase.DOWNLOADING_DATA,
            VirtualInstallPhase.INSTALLING_DATA,
            -> {
                val fraction = if (update.totalBytes > 0) {
                    (update.verifiedBytes.toFloat() / update.totalBytes.toFloat())
                        .coerceIn(0f, 1f)
                } else {
                    0f
                }
                DownloadProgressBar(progress = fraction, modifier = Modifier.fillMaxWidth())
            }
            VirtualInstallPhase.PREPARING,
            VirtualInstallPhase.INSTALLING,
            VirtualInstallPhase.DONE,
            -> LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = Lime,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = when (update.phase) {
                VirtualInstallPhase.PREPARING -> stringResource(R.string.virtual_auto_preparing)
                VirtualInstallPhase.VERIFYING -> stringResource(R.string.virtual_auto_verifying)
                VirtualInstallPhase.INSTALLING -> stringResource(R.string.virtual_auto_installing)
                VirtualInstallPhase.DOWNLOADING_DATA -> stringResource(R.string.virtual_auto_downloading_data)
                VirtualInstallPhase.INSTALLING_DATA -> stringResource(R.string.virtual_auto_installing_data)
                VirtualInstallPhase.DONE -> ""
            },
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
    }
}
