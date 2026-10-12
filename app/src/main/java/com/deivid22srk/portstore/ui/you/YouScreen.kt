package com.deivid22srk.portstore.ui.you

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInbox
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.Brightness7
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwipeRight
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.VideogameAsset
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deivid22srk.portstore.catalog.CatalogUrls
import com.deivid22srk.portstore.install.InstallState
import com.deivid22srk.portstore.settings.AppSettings
import com.deivid22srk.portstore.ui.components.AppLogo
import com.deivid22srk.portstore.ui.theme.Lime
import com.deivid22srk.portstore.ui.theme.TextSecondary
import com.deivid22srk.portstore.util.LinkOpener

@Composable
fun YouScreen(
    onOpenInstalled: () -> Unit,
    onOpenRepositories: () -> Unit,
    onOpenLegal: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenVirtualApps: () -> Unit = {},
) {
    val context = LocalContext.current
    val vm: YouViewModel = viewModel {
        YouViewModel(
            app = context.applicationContext as android.app.Application,
        )
    }
    val settings by vm.settingsFlow.collectAsStateWithLifecycle()
    val selfUpdateState by com.deivid22srk.portstore.AppGraph.selfUpdate.state
        .collectAsStateWithLifecycle()
    var showThemeDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showSegmentsDialog by remember { mutableStateOf(false) }
    var showTokenDialog by remember { mutableStateOf(false) }
    var showVirtualNoticeDialog by remember { mutableStateOf(false) }
    var showVirtualDisableDialog by remember { mutableStateOf(false) }

    // Estado do modo Instalação Virtual (lista de jogos + prontidão do motor).
    val virtualApps by com.deivid22srk.portstore.AppGraph.virtual.apps
        .collectAsStateWithLifecycle()
    val virtualEngineReady by com.deivid22srk.portstore.AppGraph.virtual.engineReadyFlow
        .collectAsStateWithLifecycle(initialValue = com.deivid22srk.portstore.AppGraph.virtual.engineReady)
    val virtualUsage = remember(virtualApps) {
        virtualApps.sumOf { it.apkSizeBytes + (it.dataBytes ?: 0L) }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        vm.setDownloadTreeUri(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // Cabeçalho
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            AppLogo()
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Hail Games", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Ports de jogos para Android",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Navegação para a lista de jogos instalados (tela separada)
        val installStates by com.deivid22srk.portstore.AppGraph.installedApps.states
            .collectAsStateWithLifecycle()
        val catalog by com.deivid22srk.portstore.AppGraph.catalog.catalog
            .collectAsStateWithLifecycle()
        val installedCount = catalog?.games.orEmpty().count { g ->
            !g.isWeb && g.packageName.isNotEmpty() && g.packageName.any { pkg ->
                val s = installStates[pkg]
                s is InstallState.Installed || s is InstallState.UpdateAvailable
            }
        }
        SettingRow(
            icon = Icons.Rounded.SportsEsports,
            title = "Meus jogos instalados",
            subtitle = when {
                installedCount == 0 -> "Nenhum jogo instalado"
                installedCount == 1 -> "1 jogo"
                else -> "$installedCount jogos"
            },
            trailing = {
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(22.dp),
                )
            },
        ) { onOpenInstalled() }

        SettingRow(
            icon = Icons.Rounded.Brightness7,
            title = "Tema",
            subtitle = when (settings.theme) {
                AppSettings.THEME_SYSTEM -> "Sistema"
                AppSettings.THEME_LIGHT -> "Claro"
                else -> "Escuro"
            },
        ) { showThemeDialog = true }

        SettingRow(
            icon = Icons.Rounded.Folder,
            title = "Pasta de downloads",
            subtitle = if (settings.downloadTreeUri.isBlank()) {
                "Interna do app (Android/data)"
            } else {
                "Pasta escolhida (cópia ao concluir)"
            },
        ) { folderPicker.launch(null) }

        SettingRow(
            icon = Icons.Rounded.Speed,
            title = "Conexões paralelas",
            subtitle = "${settings.segments} segmentos • ${settings.maxConcurrent} downloads simultâneos",
        ) { showSegmentsDialog = true }

        SettingRow(
            icon = Icons.Rounded.Speed,
            title = "Limite de velocidade",
            subtitle = if (settings.speedLimitMbps > 0) "${settings.speedLimitMbps} Mbps" else "Sem limite",
        ) { showSpeedDialog = true }

        SettingSwitch(
            icon = Icons.Rounded.VideogameAsset,
            title = stringResource(com.deivid22srk.portstore.R.string.launcher_mode_title),
            subtitle = stringResource(com.deivid22srk.portstore.R.string.launcher_mode_subtitle),
            checked = settings.gameLauncherEnabled,
            onChecked = vm::setGameLauncherEnabled,
        )

        // ===== Modo Instalação Virtual =====
        SettingRow(
            icon = Icons.Rounded.AllInbox,
            title = stringResource(com.deivid22srk.portstore.R.string.virtual_apps_title),
            subtitle = when {
                virtualApps.isEmpty() ->
                    stringResource(com.deivid22srk.portstore.R.string.virtual_apps_none)
                else -> stringResource(
                    com.deivid22srk.portstore.R.string.virtual_apps_summary,
                    virtualApps.size,
                    formatVirtualBytes(virtualUsage),
                )
            },
            trailing = {
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(22.dp),
                )
            },
        ) { onOpenVirtualApps() }

        SettingSwitch(
            icon = Icons.Rounded.Inventory2,
            title = stringResource(com.deivid22srk.portstore.R.string.virtual_mode_title),
            subtitle = when {
                settings.virtualInstallEnabled && !virtualEngineReady ->
                    stringResource(com.deivid22srk.portstore.R.string.virtual_mode_restart_hint)
                settings.virtualInstallEnabled ->
                    stringResource(com.deivid22srk.portstore.R.string.virtual_mode_on_subtitle)
                else ->
                    stringResource(com.deivid22srk.portstore.R.string.virtual_mode_off_subtitle)
            },
            checked = settings.virtualInstallEnabled,
            onChecked = { want ->
                when {
                    // Primeira ativação: aviso claro antes de ligar.
                    want && !settings.virtualNoticeAcknowledged -> showVirtualNoticeDialog = true
                    // Desligar: oferecer manter ou remover os jogos virtuais.
                    !want -> showVirtualDisableDialog = true
                    else -> vm.setVirtualInstallEnabled(true)
                }
            },
        )

        SettingSwitch(
            icon = Icons.Rounded.Wifi,
            title = "Baixar só no Wi-Fi",
            checked = settings.wifiOnly,
            onChecked = vm::setWifiOnly,
        )

        SettingSwitch(
            icon = Icons.Rounded.SwipeRight,
            title = stringResource(com.deivid22srk.portstore.R.string.settings_quick_menu_title),
            subtitle = stringResource(com.deivid22srk.portstore.R.string.settings_quick_menu_subtitle),
            checked = settings.quickMenuGestureEnabled,
            onChecked = vm::setQuickMenuGestureEnabled,
        )

        SettingSwitch(
            icon = Icons.Rounded.NotificationsActive,
            title = stringResource(com.deivid22srk.portstore.R.string.settings_launch_feedback_title),
            subtitle = stringResource(com.deivid22srk.portstore.R.string.settings_launch_feedback_subtitle),
            checked = settings.launchFeedbackEnabled,
            onChecked = vm::setLaunchFeedbackEnabled,
        )

        SettingRow(
            icon = Icons.Rounded.CleaningServices,
            title = "Limpar cache",
            subtitle = "Imagens e thumbnails",
        ) { vm.clearImageCache(context) }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 8.dp))

        SettingRow(
            icon = Icons.Rounded.Settings,
            title = "Gerenciar repositórios",
            subtitle = "Fontes de dados do catálogo",
        ) { onOpenRepositories() }

        SettingRow(
            icon = Icons.Rounded.Key,
            title = "Token do GitHub",
            subtitle = if (settings.githubToken.isBlank()) "Opcional — aumenta o limite de consultas de versão"
            else "Configurado",
        ) { showTokenDialog = true }

        SettingSwitch(
            icon = Icons.Rounded.Update,
            title = "Incluir versões de teste (pré-lançamento)",
            checked = settings.allowPrerelease,
            onChecked = vm::setAllowPrerelease,
        )

        SettingRow(
            icon = Icons.Rounded.Replay,
            title = "Refazer configuração inicial",
            subtitle = "Modo de uso, destino, permissões e repositórios em 5 etapas",
        ) { vm.openSetup(0) }

        // Atualização do próprio app (consulta releases deste repositório).
        SettingRow(
            icon = Icons.Rounded.SystemUpdate,
            title = "Verificar atualizações",
            subtitle = when (val su = selfUpdateState) {
                is com.deivid22srk.portstore.update.SelfUpdateState.Checking -> "Verificando…"
                is com.deivid22srk.portstore.update.SelfUpdateState.UpToDate ->
                    "Você já está na versão mais recente"
                is com.deivid22srk.portstore.update.SelfUpdateState.Available ->
                    "Nova versão ${su.update.version} disponível — toque para ver"
                is com.deivid22srk.portstore.update.SelfUpdateState.Downloading ->
                    "Baixando atualização… ${su.progress}%"
                is com.deivid22srk.portstore.update.SelfUpdateState.ReadyToInstall ->
                    "Atualização baixada — toque para instalar"
                is com.deivid22srk.portstore.update.SelfUpdateState.Error -> su.message
                com.deivid22srk.portstore.update.SelfUpdateState.Idle ->
                    "Versão atual: " + com.deivid22srk.portstore.BuildConfig.VERSION_NAME
            },
        ) {
            com.deivid22srk.portstore.AppGraph.selfUpdate.check()
        }

        SettingRow(icon = Icons.Rounded.Info, title = "Sobre o app", subtitle = "Port Store " + com.deivid22srk.portstore.BuildConfig.VERSION_NAME) { onOpenAbout() }
        SettingRow(icon = Icons.Rounded.Gavel, title = "Aviso legal", subtitle = "Leia antes de usar") { onOpenLegal() }
        SettingRow(icon = Icons.Rounded.OpenInNew, title = "Canal no YouTube", subtitle = "@Hail-Games1") {
            LinkOpener.open(context, CatalogUrls.YOUTUBE_CHANNEL)
        }
        SettingRow(icon = Icons.Rounded.OpenInNew, title = "Telegram", subtitle = "t.me/hailgames2") {
            LinkOpener.open(context, CatalogUrls.TELEGRAM)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("Tema") },
            text = {
                Column {
                    ThemeOption("Sistema", AppSettings.THEME_SYSTEM, settings.theme) {
                        vm.setTheme(it); showThemeDialog = false
                    }
                    ThemeOption("Escuro", AppSettings.THEME_DARK, settings.theme) {
                        vm.setTheme(it); showThemeDialog = false
                    }
                    ThemeOption("Claro", AppSettings.THEME_LIGHT, settings.theme) {
                        vm.setTheme(it); showThemeDialog = false
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showThemeDialog = false }) { Text("Fechar") }
            },
        )
    }

    if (showVirtualNoticeDialog) {
        AlertDialog(
            onDismissRequest = { showVirtualNoticeDialog = false },
            title = { Text(stringResource(com.deivid22srk.portstore.R.string.virtual_notice_title)) },
            text = {
                Text(stringResource(com.deivid22srk.portstore.R.string.virtual_notice_message))
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.acknowledgeVirtualNotice()
                    vm.setVirtualInstallEnabled(true)
                    showVirtualNoticeDialog = false
                }) {
                    Text(
                        stringResource(com.deivid22srk.portstore.R.string.virtual_notice_confirm),
                        color = Lime,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showVirtualNoticeDialog = false }) {
                    Text(stringResource(com.deivid22srk.portstore.R.string.virtual_notice_cancel))
                }
            },
        )
    }

    if (showVirtualDisableDialog) {
        AlertDialog(
            onDismissRequest = { showVirtualDisableDialog = false },
            title = { Text(stringResource(com.deivid22srk.portstore.R.string.virtual_disable_title)) },
            text = {
                Text(stringResource(com.deivid22srk.portstore.R.string.virtual_disable_message))
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setVirtualInstallEnabled(false)
                    showVirtualDisableDialog = false
                }) {
                    Text(
                        stringResource(com.deivid22srk.portstore.R.string.virtual_disable_keep),
                        color = Lime,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.uninstallAllVirtualGames()
                    vm.setVirtualInstallEnabled(false)
                    showVirtualDisableDialog = false
                }) {
                    Text(stringResource(com.deivid22srk.portstore.R.string.virtual_disable_remove))
                }
            },
        )
    }

    if (showSegmentsDialog) {
        var segments by remember { mutableStateOf(settings.segments.toFloat()) }
        var concurrent by remember { mutableStateOf(settings.maxConcurrent.toFloat()) }
        AlertDialog(
            onDismissRequest = { showSegmentsDialog = false },
            title = { Text("Conexões paralelas") },
            text = {
                Column {
                    Text("Segmentos por download: ${segments.toInt()}", color = Lime)
                    Slider(
                        value = segments,
                        onValueChange = { segments = it },
                        valueRange = 1f..16f,
                        steps = 15,
                    )
                    Text("Downloads simultâneos: ${concurrent.toInt()}", color = Lime)
                    Slider(
                        value = concurrent,
                        onValueChange = { concurrent = it },
                        valueRange = 1f..6f,
                        steps = 5,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setSegments(segments.toInt())
                    vm.setMaxConcurrent(concurrent.toInt())
                    showSegmentsDialog = false
                }) { Text("Salvar", color = Lime) }
            },
            dismissButton = {
                TextButton(onClick = { showSegmentsDialog = false }) { Text("Cancelar") }
            },
        )
    }

    if (showSpeedDialog) {
        var mbps by remember { mutableStateOf(settings.speedLimitMbps.toFloat()) }
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            title = { Text("Limite de velocidade") },
            text = {
                Column {
                    Text(
                        if (mbps.toInt() == 0) "Sem limite" else "${mbps.toInt()} Mbps",
                        color = Lime,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Slider(
                        value = mbps,
                        onValueChange = { mbps = it },
                        valueRange = 0f..100f,
                        steps = 19,
                    )
                    Text(
                        "0 = sem limite. O limite é compartilhado por todos os downloads.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setSpeedLimit(mbps.toInt())
                    showSpeedDialog = false
                }) { Text("Salvar", color = Lime) }
            },
            dismissButton = {
                TextButton(onClick = { showSpeedDialog = false }) { Text("Cancelar") }
            },
        )
    }

    if (showTokenDialog) {
        var token by remember { mutableStateOf(settings.githubToken) }
        AlertDialog(
            onDismissRequest = { showTokenDialog = false },
            title = { Text("Token do GitHub") },
            text = {
                Column {
                    Text(
                        "A API do GitHub limita consultas sem autenticação (~60/hora). " +
                            "Um token pessoal (read-only) aumenta o limite. Fica guardado apenas " +
                            "no armazenamento privado do app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("ghp_… (opcional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setGithubToken(token)
                    showTokenDialog = false
                }) { Text("Salvar", color = Lime) }
            },
            dismissButton = {
                if (settings.githubToken.isNotBlank()) {
                    TextButton(onClick = {
                        vm.setGithubToken("")
                        showTokenDialog = false
                    }) { Text("Remover") }
                } else {
                    TextButton(onClick = { showTokenDialog = false }) { Text("Cancelar") }
                }
            },
        )
    }
}

@Composable
private fun ThemeOption(label: String, value: Int, current: Int, onPick: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick(value) }
            .padding(vertical = 4.dp),
    ) {
        RadioButton(selected = current == value, onClick = { onPick(value) })
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Lime, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** Formata bytes para exibição (KB/MB/GB) na linha de jogos virtuais. */
private fun formatVirtualBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.1f KB".format(bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}

@Composable
private fun SettingSwitch(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Lime, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
