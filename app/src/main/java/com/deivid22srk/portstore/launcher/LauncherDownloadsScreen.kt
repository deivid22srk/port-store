package com.deivid22srk.portstore.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deivid22srk.portstore.R
import com.deivid22srk.portstore.backup.BackupDirection
import com.deivid22srk.portstore.backup.BackupJobUi
import com.deivid22srk.portstore.backup.BackupPhase
import com.deivid22srk.portstore.launcher.input.LauncherInitialFocus
import com.deivid22srk.portstore.ui.components.DownloadProgressBar
import com.deivid22srk.portstore.ui.theme.Lime
import com.deivid22srk.portstore.ui.theme.TextPrimary
import com.deivid22srk.portstore.ui.theme.TextSecondary
import com.deivid22srk.portstore.util.Formatters

/**
 * Downloads do Game Launcher: itens do repositório de downloads + a seção
 * "Operações de dados" do Modo Desenvolvedor (exportação/importação), com
 * barra de progresso, velocidade, tempo restante e cancelar — por gamepad
 * e toque.
 */
@Composable
fun LauncherDownloadsScreen(
    viewModel: LauncherViewModel,
    onBack: () -> Unit,
    onOpenStore: () -> Unit,
) {
    val items by viewModel.downloadItems.collectAsStateWithLifecycle()
    val backupJobs by viewModel.backupJobs.collectAsStateWithLifecycle()
    val backRequester = remember { FocusRequester() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(20.dp)) {
            LauncherIconButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.launcher_hint_details),
                onClick = onBack,
                focusRequester = backRequester,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.launcher_menu_downloads),
                style = MaterialTheme.typography.headlineSmall,
            )
        }

        if (items.isEmpty() && backupJobs.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                LauncherEmptyState(
                    message = stringResource(R.string.launcher_downloads_empty),
                    actionLabel = stringResource(R.string.launcher_empty_action),
                    onAction = onOpenStore,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 20.dp,
                    vertical = 4.dp,
                ),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            ) {
                if (backupJobs.isNotEmpty()) {
                    item(key = "bk_header") {
                        Text(
                            stringResource(R.string.backup_ops_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp, start = 4.dp),
                        )
                    }
                    items(backupJobs, key = { "bk_${it.jobId}" }) { job ->
                        BackupJobRow(
                            job = job,
                            onCancel = { viewModel.cancelBackupJob(job.jobId) },
                            onRetry = { viewModel.retryBackupJob(job.jobId) },
                            onDismiss = { viewModel.dismissBackupJob(job.jobId) },
                        )
                    }
                }
                if (items.isNotEmpty()) {
                    if (backupJobs.isNotEmpty()) {
                        item(key = "dl_header") {
                            Text(
                                stringResource(R.string.launcher_menu_downloads),
                                style = MaterialTheme.typography.titleSmall,
                                color = TextSecondary,
                                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp, start = 4.dp),
                            )
                        }
                    }
                    items(items, key = { it.id }) { item ->
                        LauncherDownloadRow(
                            item = item,
                            onPause = { viewModel.pauseDownload(item.id) },
                            onResume = { viewModel.resumeDownload(item.id) },
                            onCancel = { viewModel.cancelDownload(item.id) },
                            onRetry = { viewModel.retryDownload(item.id) },
                            onDelete = { viewModel.deleteDownload(item.id) },
                            onInstall = { viewModel.installDownloadedApk(item.destPath) },
                        )
                    }
                }
            }
        }

        Text(
            stringResource(R.string.launcher_hint_touch),
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 10.dp),
        )
    }

    LauncherInitialFocus(backRequester)
}

/**
 * Card de um job de exportação/importação: barra + % + velocidade + tempo
 * restante + cancelar/retomar/fechar (gamepad e toque).
 */
@Composable
private fun BackupJobRow(
    job: BackupJobUi,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Icon(
            imageVector = if (job.direction == BackupDirection.EXPORT) Icons.Rounded.FileUpload else Icons.Rounded.FileDownload,
            contentDescription = null,
            tint = if (job.phase == BackupPhase.DONE) Lime else TextSecondary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                job.gameTitle,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            DownloadProgressBar(
                progress = when (job.phase) {
                    BackupPhase.DONE -> 1f
                    else -> job.progress
                },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = backupJobStatusText(job),
                style = MaterialTheme.typography.bodySmall,
                color = if (job.phase == BackupPhase.ERROR) MaterialTheme.colorScheme.error else TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        when (job.phase) {
            BackupPhase.QUEUED, BackupPhase.PREPARING, BackupPhase.RUNNING -> {
                LauncherIconButton(
                    icon = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.cancel),
                    onClick = onCancel,
                )
            }
            BackupPhase.ERROR -> {
                LauncherIconButton(
                    icon = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.retry),
                    onClick = onRetry,
                )
                LauncherIconButton(
                    icon = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.delete_file),
                    onClick = onDismiss,
                )
            }
            BackupPhase.DONE, BackupPhase.CANCELED -> {
                LauncherIconButton(
                    icon = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.delete_file),
                    onClick = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun backupJobStatusText(job: BackupJobUi): String = when (job.phase) {
    BackupPhase.QUEUED -> stringResource(R.string.backup_state_queued)
    BackupPhase.PREPARING -> stringResource(R.string.backup_state_preparing)
    BackupPhase.RUNNING -> buildString {
        append(stringResource(R.string.backup_state_running_pct, (job.progress * 100).toInt()))
        val speed = Formatters.formatSpeed(job.speedBps)
        if (speed.isNotBlank()) append(" · ").append(speed)
        val eta = Formatters.formatEta(job.etaSec)
        if (eta.isNotBlank()) append(" · ").append(eta)
    }
    BackupPhase.DONE -> stringResource(R.string.backup_state_done)
    BackupPhase.ERROR -> job.message ?: stringResource(R.string.backup_state_error)
    BackupPhase.CANCELED -> stringResource(R.string.backup_state_canceled)
}
