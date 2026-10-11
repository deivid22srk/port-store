package com.deivid22srk.portstore.ui.virtual

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AllInbox
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.deivid22srk.portstore.AppGraph
import com.deivid22srk.portstore.R
import com.deivid22srk.portstore.catalog.Game
import com.deivid22srk.portstore.ui.theme.Lime
import com.deivid22srk.portstore.ui.theme.TextSecondary
import com.deivid22srk.portstore.virtual.VirtualAppInfo
import java.text.DateFormat
import java.util.Date

/**
 * "Jogos virtuais" — gerenciamento do ambiente de instalação virtual:
 * espaço total usado, lista com versão/tamanho/data e ações por jogo
 * (Abrir, Detalhes, Limpar dados, Desinstalar — sempre com confirmação).
 */
@Composable
fun VirtualAppsScreen(
    onBack: () -> Unit,
    onOpenGame: (String) -> Unit,
) {
    val context = LocalContext.current
    val apps by AppGraph.virtual.apps.collectAsStateWithLifecycle()
    val catalog by AppGraph.catalog.catalog.collectAsStateWithLifecycle()
    val modeEnabled = AppGraph.settings.settings.collectAsStateWithLifecycle().value
        .virtualInstallEnabled

    // Reavalia ao abrir a tela (jogo pode ter sido instalado/desinstalado fora).
    LaunchedEffect(Unit) { AppGraph.virtual.refresh() }

    var totalBytes by remember { mutableStateOf(0L) }
    LaunchedEffect(apps) {
        totalBytes = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                AppGraph.virtual.storageUsageBytes()
            }
        }.getOrDefault(0L)
    }

    val catalogByPackage = remember(catalog) {
        val map = mutableMapOf<String, Game>()
        catalog?.games.orEmpty().forEach { g ->
            g.packageName.forEach { pkg -> if (pkg !in map) map[pkg] = g }
        }
        map
    }

    var confirmClearData by remember { mutableStateOf<VirtualAppInfo?>(null) }
    var confirmUninstall by remember { mutableStateOf<VirtualAppInfo?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        // Barra superior
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.virtual_back))
            }
            Text(
                stringResource(R.string.virtual_apps_title),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Resumo de espaço do ambiente virtual
        if (apps.isNotEmpty()) {
            Text(
                stringResource(R.string.virtual_storage_summary, formatBytes(totalBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        if (apps.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 32.dp),
                ) {
                    Icon(
                        Icons.Rounded.AllInbox,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (modeEnabled) {
                            stringResource(R.string.virtual_empty_hint)
                        } else {
                            stringResource(R.string.virtual_empty_mode_off)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.virtual_empty_hint_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(apps, key = { it.packageName }) { app ->
                    val game = catalogByPackage[app.packageName]
                    VirtualAppRow(
                        app = app,
                        game = game,
                        onOpen = {
                            val ok = AppGraph.virtual.launch(app.packageName)
                            if (!ok) {
                                Toast.makeText(
                                    context,
                                    R.string.virtual_open_failed,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        onDetails = {
                            val gameId = game?.id
                                ?: AppGraph.virtual.gameIdFor(app.packageName)
                            if (gameId != null) onOpenGame(gameId) else {
                                Toast.makeText(
                                    context,
                                    R.string.virtual_no_catalog_link,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        onClearData = { confirmClearData = app },
                        onUninstall = { confirmUninstall = app },
                    )
                }
            }
        }
    }

    // Confirmações destrutivas (dados do jogo são perdidos)
    confirmClearData?.let { app ->
        AlertDialog(
            onDismissRequest = { confirmClearData = null },
            title = { Text(stringResource(R.string.virtual_clear_title)) },
            text = { Text(stringResource(R.string.virtual_clear_message, displayName(app, catalogByPackage))) },
            confirmButton = {
                TextButton(onClick = {
                    val ok = AppGraph.virtual.clearData(app.packageName)
                    Toast.makeText(
                        context,
                        if (ok) R.string.virtual_clear_done else R.string.virtual_action_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                    confirmClearData = null
                }) { Text(stringResource(R.string.virtual_clear_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearData = null }) { Text(stringResource(R.string.virtual_cancel)) }
            },
        )
    }

    confirmUninstall?.let { app ->
        AlertDialog(
            onDismissRequest = { confirmUninstall = null },
            title = { Text(stringResource(R.string.virtual_uninstall_title)) },
            text = { Text(stringResource(R.string.virtual_uninstall_message, displayName(app, catalogByPackage))) },
            confirmButton = {
                TextButton(onClick = {
                    val ok = AppGraph.virtual.uninstall(app.packageName)
                    Toast.makeText(
                        context,
                        if (ok) R.string.virtual_uninstall_done else R.string.virtual_action_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                    confirmUninstall = null
                }) { Text(stringResource(R.string.virtual_uninstall_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUninstall = null }) { Text(stringResource(R.string.virtual_cancel)) }
            },
        )
    }
}

@Composable
private fun VirtualAppRow(
    app: VirtualAppInfo,
    game: Game?,
    onOpen: () -> Unit,
    onDetails: () -> Unit,
    onClearData: () -> Unit,
    onUninstall: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onDetails)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        // Capa (quando o jogo está no catálogo) ou ícone genérico
        if (game?.cover != null) {
            AsyncImage(
                model = game.cover,
                contentDescription = null,
                modifier = Modifier
                    .size(width = 56.dp, height = 44.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
        } else {
            Icon(
                Icons.Rounded.AllInbox,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(40.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                game?.title ?: app.packageName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = buildString {
                append(stringResource(R.string.virtual_badge))
                append(" • ")
                append(app.versionName ?: stringResource(R.string.virtual_version_unknown))
                append(" • ")
                append(formatBytes(app.apkSizeBytes + (app.dataBytes ?: 0L)))
                if (app.installedAt > 0) {
                    append(" • ")
                    append(
                        DateFormat.getDateInstance(DateFormat.SHORT)
                            .format(Date(app.installedAt)),
                    )
                }
            }
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onOpen, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Rounded.PlayArrow,
                contentDescription = stringResource(R.string.virtual_open),
                tint = Lime,
            )
        }
        IconButton(onClick = onClearData, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Rounded.DeleteSweep,
                contentDescription = stringResource(R.string.virtual_clear_action),
                tint = TextSecondary,
            )
        }
        IconButton(onClick = onUninstall, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Rounded.Delete,
                contentDescription = stringResource(R.string.virtual_uninstall_action),
                tint = TextSecondary,
            )
        }
    }
}

private fun displayName(
    app: VirtualAppInfo,
    catalogByPackage: Map<String, Game>,
): String = catalogByPackage[app.packageName]?.title ?: app.packageName

/** Formata bytes para exibição (KB/MB/GB). */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.1f KB".format(bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}
