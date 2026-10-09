package com.deivid22srk.portstore.ui.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deivid22srk.portstore.AppGraph
import com.deivid22srk.portstore.core.DlState
import com.deivid22srk.portstore.core.DownloadItem
import com.deivid22srk.portstore.ui.components.DownloadProgressBar
import com.deivid22srk.portstore.ui.components.GameImage
import com.deivid22srk.portstore.ui.theme.Lime
import com.deivid22srk.portstore.ui.theme.TextSecondary
import com.deivid22srk.portstore.util.Formatters

@Composable
fun DownloadsScreen(
    onOpenGame: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val vm: DownloadsViewModel = viewModel {
        DownloadsViewModel(
            app = context.applicationContext as android.app.Application,
            downloads = AppGraph.downloads,
        )
    }
    val items by vm.items.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
        ) {
            Text(
                text = "Downloads",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (items.any { it.isCompleted }) {
                TextButton(onClick = vm::clearFinished) {
                    Text("Limpar concluídos", color = TextSecondary)
                }
            }
        }

        if (items.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Rounded.FileDownload,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(56.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Nenhum download ainda.\nEscolha um port para começar.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary,
                )
            }
            return
        }

        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            val ordered = items.sortedWith(
                compareByDescending<DownloadItem> { it.isActive }
                    .thenByDescending { it.isPaused }
                    .thenByDescending { it.isFailed }
                    .thenBy { it.isCompleted },
            )
            items(ordered.size, key = { ordered[it].id }) { i ->
                val item = ordered[i]
                DownloadRow(
                    item = item,
                    onOpenGame = onOpenGame,
                    onPause = { vm.pause(item.id) },
                    onResume = { vm.resume(item.id) },
                    onCancel = { vm.cancel(item.id) },
                    onRetry = { vm.retry(item.id) },
                    onInstall = { vm.install(item.destPath) },
                    onDelete = { vm.delete(item.id) },
                )
            }
        }
    }
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    onOpenGame: (String) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onInstall: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenGame(item.gameId) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        GameImage(
            url = item.cover,
            modifier = Modifier.size(width = 56.dp, height = 74.dp),
            shape = RoundedCornerShape(8.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))

            when {
                item.isActive -> {
                    DownloadProgressBar(
                        progress = item.progress,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = buildString {
                            append(
                                when (item.state) {
                                    DlState.CONNECTING -> "Conectando…"
                                    DlState.QUEUED -> "Na fila…"
                                    DlState.VERIFYING -> "Verificando…"
                                    else -> {
                                        val pct = (item.progress * 100).toInt()
                                        listOfNotNull(
                                            "$pct%",
                                            Formatters.formatSpeed(item.speedBps).takeIf { it.isNotBlank() },
                                            Formatters.formatEta(item.etaSec).takeIf { it.isNotBlank() },
                                        ).joinToString(" • ")
                                    }
                                },
                            )
                            append(" • ${Formatters.formatBytes(item.downloaded)} de ${Formatters.formatBytes(item.total)}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
                item.isPaused -> {
                    DownloadProgressBar(
                        progress = item.progress,
                        modifier = Modifier.fillMaxWidth(),
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Pausado • ${Formatters.formatBytes(item.downloaded)} de ${Formatters.formatBytes(item.total)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
                item.isCompleted -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.DownloadDone, contentDescription = null, tint = Lime, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Concluído • pronto para instalar", style = MaterialTheme.typography.bodySmall, color = Lime)
                    }
                }
                item.isFailed -> {
                    Text(
                        text = "Falhou: ${item.error ?: "erro desconhecido"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                else -> {
                    Text(
                        text = "Cancelado",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
            }
        }

        // Ações
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                item.isActive -> {
                    IconButton(onClick = onPause) {
                        Icon(Icons.Rounded.Pause, contentDescription = "Pausar", tint = Lime)
                    }
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Rounded.Close, contentDescription = "Cancelar", tint = TextSecondary)
                    }
                }
                item.isPaused -> {
                    IconButton(onClick = onResume) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Retomar", tint = Lime)
                    }
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Rounded.Close, contentDescription = "Cancelar", tint = TextSecondary)
                    }
                }
                item.isCompleted -> {
                    TextButton(onClick = onInstall) { Text("Instalar APK", color = Lime) }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Apagar arquivo", tint = TextSecondary)
                    }
                }
                item.isFailed -> {
                    IconButton(onClick = onRetry) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Tentar novamente", tint = Lime)
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Remover", tint = TextSecondary)
                    }
                }
                else -> {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Remover", tint = TextSecondary)
                    }
                }
            }
        }
    }
}
