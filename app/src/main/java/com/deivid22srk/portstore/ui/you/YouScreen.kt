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
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.Brightness7
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Update
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deivid22srk.portstore.catalog.CatalogUrls
import com.deivid22srk.portstore.catalog.Game
import com.deivid22srk.portstore.github.VersionState
import com.deivid22srk.portstore.install.InstallState
import com.deivid22srk.portstore.settings.AppSettings
import com.deivid22srk.portstore.ui.components.AppLogo
import com.deivid22srk.portstore.ui.theme.Lime
import com.deivid22srk.portstore.ui.theme.TextSecondary
import com.deivid22srk.portstore.util.LinkOpener
import kotlinx.coroutines.launch

@Composable
fun YouScreen(
    onOpenGame: (String) -> Unit,
    onOpenLegal: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val context = LocalContext.current
    val vm: YouViewModel = viewModel {
        YouViewModel(
            app = context.applicationContext as android.app.Application,
        )
    }
    val settings by vm.settingsFlow.collectAsStateWithLifecycle()
    var showThemeDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showSegmentsDialog by remember { mutableStateOf(false) }
    var showTokenDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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

        // Meus jogos instalados + atualizações
        InstalledLibrarySection(
            onOpenGame = onOpenGame,
        )

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
            icon = Icons.Rounded.Wifi,
            title = "Baixar só no Wi-Fi",
            checked = settings.wifiOnly,
            onChecked = vm::setWifiOnly,
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
        ) { vm.openSetup(1) }

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
            title = "Refazer setup",
            subtitle = "Permissões e repositórios em etapas",
        ) { vm.openSetup(0) }

        SettingRow(icon = Icons.Rounded.Info, title = "Sobre o app", subtitle = "Port Store 1.0.0") { onOpenAbout() }
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
    }
}

@Composable
private fun SettingSwitch(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Lime, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

/** "Meus jogos instalados" + "Atualizações" (catálogo × PackageManager). */
@Composable
private fun InstalledLibrarySection(onOpenGame: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val catalog by com.deivid22srk.portstore.AppGraph.catalog.catalog
        .collectAsStateWithLifecycle()
    val installStates by com.deivid22srk.portstore.AppGraph.installedApps.states
        .collectAsStateWithLifecycle()

    val games = catalog?.games.orEmpty()
    val installed = games.filter { g ->
        !g.isWeb && g.packageName.isNotEmpty() &&
            installStates[g.primaryPackage] is InstallState.Installed
    }
    val updates = installed.filter { g ->
        installStates[g.primaryPackage] is InstallState.UpdateAvailable
    }

    if (installed.isEmpty()) return

    Text(
        "Meus jogos instalados",
        style = MaterialTheme.typography.titleSmall,
        color = TextSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    installed.take(12).forEach { g ->
        val state = installStates[g.primaryPackage] as? InstallState.Installed
        GameLibraryRow(
            game = g,
            subtitle = "Instalado" + (state?.versionName?.let { " • $it" } ?: ""),
            highlight = false,
            onOpen = { onOpenGame(g.id) },
            onUpdate = null,
        )
    }

    if (updates.isNotEmpty()) {
        Text(
            "Atualizações",
            style = MaterialTheme.typography.titleSmall,
            color = Lime,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        updates.forEach { g ->
            GameLibraryRow(
                game = g,
                subtitle = "Nova versão disponível",
                highlight = true,
                onOpen = { onOpenGame(g.id) },
                onUpdate = {
                    scope.launch {
                        val release = when (val st = com.deivid22srk.portstore.AppGraph.versions
                            .resolve(g.id, g.links.github ?: g.links.releases)) {
                            is VersionState.Resolved -> st.release
                            else -> null
                        }
                        val asset = release?.assets?.firstOrNull()
                        val url = asset?.url ?: g.links.download ?: return@launch
                        com.deivid22srk.portstore.AppGraph.downloads.startDownload(g, url, asset?.sha256)
                        android.widget.Toast.makeText(
                            context,
                            "Baixando atualização de ${g.title}",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
        }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun GameLibraryRow(
    game: Game,
    subtitle: String,
    highlight: Boolean,
    onOpen: () -> Unit,
    onUpdate: (() -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        com.deivid22srk.portstore.ui.components.GameImage(
            url = game.coverUrl,
            modifier = Modifier.size(width = 46.dp, height = 60.dp),
            shape = RoundedCornerShape(8.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(game.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (highlight) Lime else TextSecondary,
            )
        }
        if (onUpdate != null) {
            TextButton(onClick = onUpdate) { Text("Atualizar", color = Lime) }
        } else {
            Icon(
                Icons.Rounded.SportsEsports,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
