package com.deivid22srk.portstore.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.deivid22srk.portstore.ui.components.DownloadProgressBar
import com.deivid22srk.portstore.ui.theme.Lime
import com.deivid22srk.portstore.ui.theme.TextSecondary
import com.deivid22srk.portstore.util.Formatters

/**
 * Diálogo de atualização do próprio app:
 *  - [SelfUpdateState.Available]  -> nova versão + notas + "Atualizar"/"Depois"
 *  - [SelfUpdateState.Downloading]-> progresso + "Cancelar"
 * Outros estados não exibem diálogo (feedback fica na linha de "Você").
 */
@Composable
fun SelfUpdateDialog(
    state: SelfUpdateState,
    onUpdate: (AppUpdateInfo) -> Unit,
    onCancelDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (val s = state) {
        is SelfUpdateState.Available -> AvailableDialog(s.update, onUpdate, onDismiss)
        is SelfUpdateState.Downloading -> DownloadingDialog(s.update, s.progress, onCancelDownload)
        else -> Unit
    }
}

@Composable
private fun AvailableDialog(
    update: AppUpdateInfo,
    onUpdate: (AppUpdateInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentVersion = com.deivid22srk.portstore.BuildConfig.VERSION_NAME
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Atualização disponível") },
        text = {
            Column {
                Text(
                    "Port Store ${update.version} está disponível " +
                        "(você está usando $currentVersion).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!update.notes.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Novidades",
                        style = MaterialTheme.typography.titleSmall,
                        color = Lime,
                    )
                    Spacer(Modifier.height(4.dp))
                    Column(
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            update.notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onUpdate(update) }) {
                Text("Atualizar", color = Lime)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Depois", color = TextSecondary) }
        },
    )
}

@Composable
private fun DownloadingDialog(
    update: AppUpdateInfo,
    pct: Int,
    onCancel: () -> Unit,
) {
    val sizeText = if (update.apkSize > 0) {
        val downloaded = update.apkSize * pct / 100
        "${Formatters.formatBytes(downloaded)} de ${Formatters.formatBytes(update.apkSize)}"
    } else {
        ""
    }
    AlertDialog(
        onDismissRequest = { /* download em andamento: cancelar explicitamente */ },
        title = { Text("Baixando atualização") },
        text = {
            Column {
                Text(
                    "${update.version} • $pct%",
                    style = MaterialTheme.typography.titleSmall,
                    color = Lime,
                )
                if (sizeText.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        sizeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
                Spacer(Modifier.height(10.dp))
                DownloadProgressBar(progress = pct / 100f)
                Spacer(Modifier.height(8.dp))
                Text(
                    "O instalador do Android será aberto ao concluir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) { Text("Cancelar", color = Color(0xFFFF5252)) }
        },
        dismissButton = {},
    )
}
