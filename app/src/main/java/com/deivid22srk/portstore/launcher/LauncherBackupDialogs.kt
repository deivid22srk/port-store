package com.deivid22srk.portstore.launcher

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.deivid22srk.portstore.R
import com.deivid22srk.portstore.backup.BackupCategory
import com.deivid22srk.portstore.backup.ExportRequest
import com.deivid22srk.portstore.backup.ImportRequest
import com.deivid22srk.portstore.backup.LEVEL_BALANCED
import com.deivid22srk.portstore.backup.LEVEL_FAST
import com.deivid22srk.portstore.backup.LEVEL_MAX
import com.deivid22srk.portstore.backup.PART_SIZE_DEFAULT
import com.deivid22srk.portstore.backup.PART_SIZE_LARGE
import com.deivid22srk.portstore.backup.PART_SIZE_SMALL
import com.deivid22srk.portstore.catalog.Game
import com.deivid22srk.portstore.launcher.input.LocalGamepadMonitor
import com.deivid22srk.portstore.launcher.input.launcherFocusable
import com.deivid22srk.portstore.launcher.input.launcherGamepadNav
import com.deivid22srk.portstore.ui.theme.Lime
import com.deivid22srk.portstore.ui.theme.TextPrimary
import com.deivid22srk.portstore.ui.theme.TextSecondary
import com.deivid22srk.portstore.util.Formatters
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val OnLime = Color(0xFF171800)

// =====================================================================
// Diálogo de EXPORTAÇÃO (categorias + compressão + tamanho das partes)
// =====================================================================

/**
 * Exportar dados do jogo: checkboxes por categoria, nível de compressão
 * Zstandard, tamanho das partes e destino via SAF (sem permissão de
 * armazenamento total). Navegável por gamepad e toque.
 */
@Composable
fun LauncherExportDialog(
    game: Game,
    viewModel: LauncherViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val monitor = LocalGamepadMonitor.current
    val firstRequester = remember { FocusRequester() }

    var selected by remember {
        mutableStateOf(setOf(BackupCategory.INSTALL, BackupCategory.SAVES, BackupCategory.SETTINGS))
    }
    var level by remember { mutableStateOf(LEVEL_FAST) }
    var partSize by remember { mutableStateOf(PART_SIZE_DEFAULT) }
    var preview by remember { mutableStateOf<com.deivid22srk.portstore.backup.ExportPlan?>(null) }
    var busy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }

    val queuedToast = stringResource(R.string.backup_queued_toast)
    val genericError = stringResource(R.string.backup_error_generic)

    LaunchedEffect(game.id) {
        preview = viewModel.exportPreview(game.id)
        loading = false
    }

    fun toggle(category: BackupCategory) {
        selected = if (category in selected) selected - category else selected + category
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val plan = preview
        busy = true
        scope.launch {
            val result = viewModel.queueExport(
                ExportRequest(
                    gameId = game.id,
                    gameTitle = game.title,
                    categories = selected,
                    compressionLevel = level,
                    partSizeBytes = partSize,
                    treeUri = uri,
                ),
            )
            busy = false
            result.fold(
                onSuccess = {
                    Toast.makeText(context, queuedToast, Toast.LENGTH_LONG).show()
                    onDismiss()
                },
                onFailure = { error ->
                    Toast.makeText(
                        context,
                        error.message ?: genericError,
                        Toast.LENGTH_LONG,
                    ).show()
                },
            )
        }
    }

    DialogShell(
        title = stringResource(R.string.backup_export_title),
        subtitle = game.title,
        onDismiss = onDismiss,
        maxWidth = 480.dp,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (loading) {
                Text(
                    stringResource(R.string.backup_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
            } else {
                val plan = preview
                if (plan == null) {
                    Text(
                        stringResource(R.string.backup_error_no_engine),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 20.dp),
                    )
                } else {
                    BackupCheckRow(
                        title = stringResource(R.string.backup_cat_install),
                        subtitle = stringResource(R.string.backup_cat_install_subtitle),
                        checked = BackupCategory.INSTALL in selected,
                        onClick = { toggle(BackupCategory.INSTALL) },
                        modifier = Modifier.focusRequester(firstRequester),
                        sizeLabel = Formatters.formatBytes(plan.sizeByCategory[BackupCategory.INSTALL] ?: 0L),
                    )
                    BackupCheckRow(
                        title = stringResource(R.string.backup_cat_saves),
                        subtitle = stringResource(R.string.backup_cat_saves_subtitle),
                        checked = BackupCategory.SAVES in selected,
                        onClick = { toggle(BackupCategory.SAVES) },
                        sizeLabel = Formatters.formatBytes(plan.sizeByCategory[BackupCategory.SAVES] ?: 0L),
                    )
                    BackupCheckRow(
                        title = stringResource(R.string.backup_cat_settings),
                        subtitle = stringResource(R.string.backup_cat_settings_subtitle),
                        checked = BackupCategory.SETTINGS in selected,
                        onClick = { toggle(BackupCategory.SETTINGS) },
                        sizeLabel = Formatters.formatBytes(plan.sizeByCategory[BackupCategory.SETTINGS] ?: 0L),
                    )
                    BackupCheckRow(
                        title = stringResource(R.string.backup_cat_launcher),
                        subtitle = stringResource(R.string.backup_cat_launcher_subtitle),
                        checked = BackupCategory.LAUNCHER in selected,
                        onClick = { toggle(BackupCategory.LAUNCHER) },
                        sizeLabel = null,
                    )

                    Spacer(Modifier.height(14.dp))
                    Text(
                        stringResource(R.string.backup_level_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BackupChip(stringResource(R.string.backup_level_fast), level == LEVEL_FAST) { level = LEVEL_FAST }
                        BackupChip(stringResource(R.string.backup_level_balanced), level == LEVEL_BALANCED) { level = LEVEL_BALANCED }
                        BackupChip(stringResource(R.string.backup_level_max), level == LEVEL_MAX) { level = LEVEL_MAX }
                    }

                    Spacer(Modifier.height(14.dp))
                    Text(
                        stringResource(R.string.backup_parts_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BackupChip("500 MB", partSize == PART_SIZE_SMALL) { partSize = PART_SIZE_SMALL }
                        BackupChip("1 GB", partSize == PART_SIZE_DEFAULT) { partSize = PART_SIZE_DEFAULT }
                        BackupChip("2 GB", partSize == PART_SIZE_LARGE) { partSize = PART_SIZE_LARGE }
                    }

                    Spacer(Modifier.height(14.dp))
                    Text(
                        stringResource(
                            R.string.backup_estimated,
                            Formatters.formatBytes(plan.totalBytes),
                            maxOf(1, (plan.totalBytes / partSize).toInt() + 1),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                    if (plan.versionName != null) {
                        Text(
                            stringResource(R.string.backup_game_version, plan.versionName),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row {
                LauncherButton(
                    label = stringResource(R.string.backup_choose_folder),
                    onClick = {
                        if (!busy && !loading) folderPicker.launch(null)
                    },
                    enabled = !busy && !loading && preview != null && selected.isNotEmpty(),
                    container = Lime,
                    contentColor = OnLime,
                )
                Spacer(Modifier.width(12.dp))
                LauncherButton(
                    label = stringResource(R.string.cancel),
                    onClick = onDismiss,
                    enabled = !busy,
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }

    LaunchedEffect(Unit) {
        delay(120)
        runCatching { firstRequester.requestFocus() }
    }
}

// =====================================================================
// Diálogo de IMPORTAÇÃO (resumo do manifest + confirmação)
// =====================================================================

/**
 * Importar dados do jogo: escolhe a pasta do pacote (manifest.json + partes),
 * mostra o resumo (o que inclui, tamanho, partes presentes) e confirma com
 * opções de backup dos saves e de apagar cada parte após extrair.
 */
@Composable
fun LauncherImportDialog(
    game: Game,
    viewModel: LauncherViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val monitor = LocalGamepadMonitor.current
    val pickRequester = remember { FocusRequester() }
    val confirmRequester = remember { FocusRequester() }

    var treeUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var inspect by remember { mutableStateOf<com.deivid22srk.portstore.backup.ImportInspect?>(null) }
    var backupSaves by remember { mutableStateOf(true) }
    var deleteParts by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    val queuedToast = stringResource(R.string.backup_queued_toast)
    val genericError = stringResource(R.string.backup_error_generic)
    val invalidManifest = stringResource(R.string.backup_error_manifest)

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        treeUri = uri
        busy = true
        scope.launch {
            inspect = viewModel.importInspect(uri)
            busy = false
            if (inspect == null) problem = invalidManifest
        }
    }

    DialogShell(
        title = stringResource(R.string.backup_import_title),
        subtitle = game.title,
        onDismiss = onDismiss,
        maxWidth = 480.dp,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            val inspectState = inspect
            if (treeUri == null) {
                Text(
                    stringResource(R.string.backup_import_pick_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else if (busy) {
                Text(
                    stringResource(R.string.backup_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
            } else if (inspectState == null) {
                Text(
                    problem ?: invalidManifest,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                val manifest = inspectState.manifest
                BackupInfoLine(stringResource(R.string.backup_game_label), manifest.gameName)
                if (manifest.appVersion.isNotBlank() && manifest.appVersion != "unknown") {
                    BackupInfoLine(stringResource(R.string.backup_game_version_label), manifest.appVersion)
                }
                // map (inline) permite resolver os strings; joinToString só junta.
                val includesText = manifest.includes
                    .mapNotNull(BackupCategory::fromId)
                    .map { categoryName(it) }
                    .joinToString(", ")
                BackupInfoLine(stringResource(R.string.backup_includes_label), includesText)
                BackupInfoLine(
                    stringResource(R.string.backup_parts_label),
                    stringResource(
                        R.string.backup_parts_value,
                        inspectState.presentParts,
                        manifest.parts.size,
                    ),
                )
                BackupInfoLine(
                    stringResource(R.string.backup_size_label),
                    "${Formatters.formatBytes(inspectState.totalSizeCompressed)} → " +
                        "${Formatters.formatBytes(inspectState.totalSizeExtracted)}",
                )
                inspectState.freeBytes?.let { free ->
                    BackupInfoLine(
                        stringResource(R.string.backup_free_label),
                        Formatters.formatBytes(free),
                    )
                }

                if (inspectState.missingParts.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(
                            R.string.backup_missing_parts,
                            inspectState.missingParts.joinToString(limit = 3),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.backup_import_replace_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                Spacer(Modifier.height(12.dp))
                BackupCheckRow(
                    title = stringResource(R.string.backup_backup_saves),
                    subtitle = null,
                    checked = backupSaves,
                    onClick = { backupSaves = !backupSaves },
                    sizeLabel = null,
                )
                BackupCheckRow(
                    title = stringResource(R.string.backup_delete_parts),
                    subtitle = stringResource(R.string.backup_delete_parts_subtitle),
                    checked = deleteParts,
                    onClick = { deleteParts = !deleteParts },
                    sizeLabel = null,
                )
            }

            Spacer(Modifier.height(16.dp))
            Row {
                if (treeUri == null || inspect == null) {
                    LauncherButton(
                        label = stringResource(R.string.backup_choose_package_folder),
                        onClick = { if (!busy) folderPicker.launch(null) },
                        enabled = !busy,
                        container = Lime,
                        contentColor = OnLime,
                        modifier = if (inspect == null) Modifier.focusRequester(pickRequester) else Modifier,
                    )
                } else {
                    LauncherButton(
                        label = stringResource(R.string.backup_import_ok),
                        onClick = {
                            val uri = treeUri ?: return@LauncherButton
                            busy = true
                            scope.launch {
                                val result = viewModel.queueImport(
                                    ImportRequest(
                                        treeUri = uri,
                                        deletePartsAfterExtract = deleteParts,
                                        backupSavesFirst = backupSaves,
                                    ),
                                )
                                busy = false
                                result.fold(
                                    onSuccess = {
                                        Toast.makeText(context, queuedToast, Toast.LENGTH_LONG).show()
                                        onDismiss()
                                    },
                                    onFailure = { error ->
                                        Toast.makeText(context, error.message ?: genericError, Toast.LENGTH_LONG).show()
                                    },
                                )
                            }
                        },
                        enabled = !busy && inspect?.valid == true,
                        container = Lime,
                        contentColor = OnLime,
                        modifier = Modifier.focusRequester(confirmRequester),
                    )
                }
                Spacer(Modifier.width(12.dp))
                LauncherButton(
                    label = stringResource(R.string.cancel),
                    onClick = onDismiss,
                    enabled = !busy,
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }

    LaunchedEffect(inspect) {
        delay(120)
        runCatching {
            if (inspect != null) confirmRequester.requestFocus() else pickRequester.requestFocus()
        }
    }
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { if (inspect == null) pickRequester.requestFocus() }
    }
}

// =====================================================================
// Blocos compartilhados
// =====================================================================

@Composable
private fun DialogShell(
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
    maxWidth: androidx.compose.ui.unit.Dp,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val monitor = LocalGamepadMonitor.current
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 48.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 8.dp,
                modifier = Modifier.widthIn(max = maxWidth),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .let { m -> if (monitor != null) m.launcherGamepadNav(monitor, onBack = onDismiss) else m }
                        .padding(24.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun BackupCheckRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sizeLabel: String? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .launcherFocusable(shape = shape, scaleFocused = 1.015f, focusedBorderWidth = 2.dp, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
        if (sizeLabel != null) {
            Text(sizeLabel, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}

@Composable
private fun BackupChip(label: String, selected: Boolean, onClick: () -> Unit) {
    LauncherButton(
        label = label,
        onClick = onClick,
        container = if (selected) Lime else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) OnLime else TextPrimary,
    )
}

@Composable
private fun BackupInfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.width(118.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
    }
}

@Composable
private fun categoryName(category: BackupCategory): String = stringResource(
    when (category) {
        BackupCategory.INSTALL -> R.string.backup_cat_install
        BackupCategory.SAVES -> R.string.backup_cat_saves
        BackupCategory.SETTINGS -> R.string.backup_cat_settings
        BackupCategory.LAUNCHER -> R.string.backup_cat_launcher
    },
)
