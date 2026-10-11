package com.deivid22srk.portstore.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deivid22srk.portstore.R
import com.deivid22srk.portstore.launcher.input.LauncherInitialFocus
import com.deivid22srk.portstore.ui.theme.TextSecondary

/**
 * Downloads do Game Launcher: mesmos itens/estados do repositório de downloads
 * do Port Store, com barra de progresso e ações (pausar/retomar, cancelar,
 * instalar APK, apagar arquivo) por gamepad e toque.
 */
@Composable
fun LauncherDownloadsScreen(
    viewModel: LauncherViewModel,
    onBack: () -> Unit,
    onOpenStore: () -> Unit,
) {
    val items by viewModel.downloadItems.collectAsStateWithLifecycle()
    val backRequester = remember { FocusRequester() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(20.dp)) {
            Box(Modifier.focusRequester(backRequester)) {
                LauncherIconButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.launcher_hint_details),
                    onClick = onBack,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.launcher_menu_downloads),
                style = MaterialTheme.typography.headlineSmall,
            )
        }

        if (items.isEmpty()) {
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

        Text(
            stringResource(R.string.launcher_hint_touch),
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
            modifier = Modifier
                .fillMaxSize(1f)
                .padding(top = 8.dp, bottom = 10.dp),
        )
    }

    LauncherInitialFocus(backRequester)
}
