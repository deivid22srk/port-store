package com.deivid22srk.portstore.ui.you

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
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Speed
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deivid22srk.portstore.catalog.CatalogUrls
import com.deivid22srk.portstore.settings.AppSettings
import com.deivid22srk.portstore.ui.components.AppLogo
import com.deivid22srk.portstore.ui.theme.Lime
import com.deivid22srk.portstore.ui.theme.TextSecondary
import com.deivid22srk.portstore.util.LinkOpener

@Composable
fun YouScreen(
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
