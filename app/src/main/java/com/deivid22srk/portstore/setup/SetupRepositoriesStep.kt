package com.deivid22srk.portstore.setup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deivid22srk.portstore.R
import com.deivid22srk.portstore.db.DataRepoEntity
import com.deivid22srk.portstore.launcher.input.LauncherInitialFocus
import com.deivid22srk.portstore.launcher.input.launcherFocusable
import com.deivid22srk.portstore.ui.theme.Lime
import com.deivid22srk.portstore.ui.theme.TextSecondary

// =====================================================================
// Etapa 4 — Repositórios (jogos + dados complementares)
// =====================================================================

/**
 * Conteúdo da etapa "Configure seus repositórios". Etapa NÃO bloqueante:
 * os padrões já vêm ativos e o rodapé sempre permite avançar ("Configurar
 * depois" fica explícito no fim do conteúdo).
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun RepositoriesStepContent(
    state: SetupUiState,
    gamer: Boolean,
    onExportDataRepo: () -> Unit,
    onImportDataRepo: () -> Unit,
    vm: SetupViewModel,
    showConfigureLater: Boolean = true,
) {
    var pendingDelete by remember { mutableStateOf<DataRepoEntity?>(null) }
    val dataBackup = state.dataBackup

    // Foco inicial do layout gamer (navegação 100% por controle): o primeiro
    // repositório da lista — ou o botão "Adicionar", quando a lista está vazia.
    val firstItemRequester = remember { FocusRequester() }
    if (gamer) {
        // Chave estável por instância: o conteúdo ANTIGO (etapa anterior) segue
        // composto durante a transição e NÃO pode disputar o foco inicial.
        LauncherInitialFocus(firstItemRequester, key = remember { Any() })
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (gamer) 32.dp else 24.dp),
        ) {
            Text(
                stringResource(R.string.setup_repos_title),
                style = if (gamer) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                // Cor explícita: fora de um Surface o padrão é preto — sobre
                // o fundo escuro os títulos ficavam ilegíveis.
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.setup_repos_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            Spacer(Modifier.height(if (gamer) 14.dp else 18.dp))

            // --------------------------------------------------------
            // 4.1 Repositório de Jogos (fonte dos ports disponíveis)
            // --------------------------------------------------------
            SetupSectionTitle(stringResource(R.string.setup_repos_games_title))
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.setup_repos_games_desc),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(10.dp))

            state.repos.forEachIndexed { index, repo ->
                GameRepoRow(
                    repo = repo,
                    busy = state.repoBusyId == repo.id,
                    isFirst = state.repos.firstOrNull()?.id == repo.id,
                    isLast = state.repos.lastOrNull()?.id == repo.id,
                    gamer = gamer,
                    vm = vm,
                    onRemove = { pendingDelete = repo },
                    focusRequester = if (gamer && index == 0) firstItemRequester else null,
                )
                Spacer(Modifier.height(8.dp))
            }

            if (gamer) {
                GamerPillButton(
                    label = stringResource(R.string.setup_repos_add),
                    filled = false,
                    enabled = true,
                    onClick = vm::openNewRepoDialog,
                    // Sem repositórios, o foco inicial cai no próprio "Adicionar".
                    focusRequester = if (state.repos.isEmpty()) firstItemRequester else null,
                )
            } else {
                OutlinedButton(
                    onClick = vm::openNewRepoDialog,
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.setup_repos_add))
                }
            }

            Spacer(Modifier.height(if (gamer) 18.dp else 24.dp))

            // --------------------------------------------------------
            // 4.2 Repositório de Dados Complementares (import/export)
            // --------------------------------------------------------
            SetupSectionTitle(stringResource(R.string.setup_repos_data_title))
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.setup_repos_data_desc),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(10.dp))

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        stringResource(R.string.setup_repos_data_local_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.setup_repos_data_local_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(12.dp))

                    if (gamer) {
                        // Larguras ponderadas: os DOIS botões cabem sempre —
                        // sem peso, o "Importar" podia ser empurrado para fora
                        // da largura do cartão e aparecer cortado/invisível.
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            GamerPillButton(
                                label = stringResource(R.string.setup_data_export),
                                filled = false,
                                enabled = !dataBackup.busy,
                                onClick = onExportDataRepo,
                                modifier = Modifier.weight(1f),
                            )
                            GamerPillButton(
                                label = stringResource(R.string.setup_data_import),
                                filled = false,
                                enabled = !dataBackup.busy,
                                onClick = onImportDataRepo,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        // FlowRow: em telas estreitas quebra para a segunda linha
                        // em vez de cortar o botão "Importar".
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = onExportDataRepo,
                                enabled = !dataBackup.busy,
                                shape = RoundedCornerShape(20.dp),
                            ) {
                                Icon(Icons.Rounded.FileUpload, contentDescription = null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.setup_data_export))
                            }
                            OutlinedButton(
                                onClick = onImportDataRepo,
                                enabled = !dataBackup.busy,
                                shape = RoundedCornerShape(20.dp),
                            ) {
                                Icon(Icons.Rounded.FileDownload, contentDescription = null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.setup_data_import))
                            }
                        }
                    }

                    if (dataBackup.busy) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Lime)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.setup_data_working),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                            )
                        }
                    }
                    dataBackup.messageRes?.let { res ->
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(res, dataBackup.messageArg),
                            style = MaterialTheme.typography.bodySmall,
                            color = Lime,
                        )
                    }
                    dataBackup.errorRes?.let { res ->
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(res),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            SetupNote(stringResource(R.string.setup_repos_optional_note))
            if (showConfigureLater) {
                Spacer(Modifier.height(8.dp))
                if (gamer) {
                    GamerPillButton(
                        label = stringResource(R.string.setup_repos_configure_later),
                        filled = false,
                        enabled = true,
                        onClick = vm::goToNextStep,
                    )
                } else {
                    TextButton(onClick = vm::goToNextStep) {
                        Text(stringResource(R.string.setup_repos_configure_later), color = Lime)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.setup_repos_remove_title)) },
            text = { Text(stringResource(R.string.setup_repos_remove_message, pendingDelete?.name.orEmpty())) },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeRepo(pendingDelete!!)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.setup_repos_remove_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    val dialog = state.repoDialog
    if (dialog.visible) {
        RepoDialog(vm = vm, state = dialog)
    }

    val pendingImport = dataBackup.pendingImport
    if (pendingImport != null) {
        ImportConfirmDialog(
            summary = pendingImport,
            busy = dataBackup.busy,
            onMerge = { vm.confirmDataRepoImport(ComplementaryImportMode.MERGE) },
            onReplace = { vm.confirmDataRepoImport(ComplementaryImportMode.REPLACE) },
            onDismiss = vm::dismissDataRepoImport,
        )
    }
}

/** Linha de um repositório de jogos (celular: menu completo; gamer: A edita, Y remove). */
@Composable
private fun GameRepoRow(
    repo: DataRepoEntity,
    busy: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    gamer: Boolean,
    vm: SetupViewModel,
    onRemove: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    // O requester precisa estar no MESMO nó que é focável.
                    if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
                )
                .let { m ->
                    if (gamer) {
                        // Layout gamer: A edita; toque longo / Y remove.
                        m.launcherFocusable(
                            shape = shape,
                            scaleFocused = 1.02f,
                            onClick = { vm.openEditRepoDialog(repo) },
                            onLongClick = onRemove,
                        )
                    } else {
                        m.clickable { vm.openEditRepoDialog(repo) }
                    }
                }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Icon(
                Icons.Rounded.Storage,
                contentDescription = null,
                tint = if (repo.enabled) Lime else TextSecondary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    repo.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val detail = when {
                    repo.status == "ok" && repo.gameCount > 0 ->
                        stringResource(R.string.setup_repos_repo_detail_ok, repo.gameCount)
                    repo.status == "erro" ->
                        stringResource(R.string.setup_repos_repo_detail_error, repo.lastError ?: "")
                    else -> repo.url
                }
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (repo.status == "erro") MaterialTheme.colorScheme.error else TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Lime)
            } else {
                if (!gamer) {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Rounded.MoreVert,
                                contentDescription = stringResource(R.string.setup_repos_more_options),
                                tint = TextSecondary,
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.setup_repos_refresh)) },
                                onClick = { menuOpen = false; vm.refreshSingleRepo(repo) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.setup_repos_edit)) },
                                onClick = { menuOpen = false; vm.openEditRepoDialog(repo) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.setup_repos_move_up)) },
                                enabled = !isFirst,
                                onClick = { menuOpen = false; vm.moveRepo(repo, up = true) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.setup_repos_move_down)) },
                                enabled = !isLast,
                                onClick = { menuOpen = false; vm.moveRepo(repo, up = false) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.setup_repos_remove_confirm)) },
                                onClick = { menuOpen = false; onRemove() },
                            )
                        }
                    }
                }
                Switch(
                    checked = repo.enabled,
                    onCheckedChange = { vm.setRepoEnabled(repo, it) },
                )
            }
        }
    }
}

/** Diálogo de adicionar/editar repositório de jogos (validação por "Verificar"). */
@Composable
private fun RepoDialog(vm: SetupViewModel, state: RepoDialogState) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = vm::dismissRepoDialog,
        title = {
            Text(
                stringResource(
                    if (state.editingId == null) R.string.setup_repos_add_title else R.string.setup_repos_edit_title,
                ),
            )
        },
        text = {
            Column(modifier = Modifier.imePadding()) {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = { name -> vm.updateDialog(name, state.url) },
                    label = { Text(stringResource(R.string.setup_repos_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.url,
                    onValueChange = { url -> vm.updateDialog(state.name, url) },
                    label = { Text(stringResource(R.string.setup_repos_url_label)) },
                    placeholder = { Text(stringResource(R.string.setup_repos_url_placeholder)) },
                    singleLine = true,
                    trailingIcon = {
                        TextButton(onClick = {
                            val text = clipboard.getText()?.toString()?.trim()
                            if (!text.isNullOrBlank()) vm.updateDialog(state.name, text)
                        }) { Text(stringResource(R.string.setup_repos_paste)) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = vm::verifyDialogRepo,
                        enabled = !state.verifying && state.url.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.setup_repos_verify), color = Lime)
                    }
                    when {
                        state.verifying -> {
                            Spacer(Modifier.width(8.dp))
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.setup_repos_verifying),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                            )
                        }

                        state.verifiedCount != null -> {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.setup_repos_verified, state.verifiedCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = Lime,
                            )
                        }
                    }
                }
                state.error?.let { err ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = vm::saveDialogRepo,
                enabled = state.url.isNotBlank() &&
                    (state.verifiedCount != null || state.editingId != null || state.error == null),
                colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Color(0xFF171800)),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = vm::dismissRepoDialog) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Confirmação da importação com resumo validado e escolha mesclar/substituir. */
@Composable
private fun ImportConfirmDialog(
    summary: ComplementarySummary,
    busy: Boolean,
    onMerge: () -> Unit,
    onReplace: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.setup_data_import_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.setup_data_import_summary, summary.itemCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(
                        R.string.setup_data_import_detail,
                        summary.version,
                        summary.repoNames.joinToString(", "),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.setup_data_import_question),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (busy) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Lime)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.setup_data_working),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onMerge, enabled = !busy) {
                    Text(stringResource(R.string.setup_data_import_merge), color = Lime)
                }
                TextButton(onClick = onReplace, enabled = !busy) {
                    Text(stringResource(R.string.setup_data_import_replace))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

// =====================================================================
// Etapa 5 — Conclusão (resumo das escolhas)
// =====================================================================

/** Conteúdo da etapa final: resumo das escolhas + botão "Começar" no rodapé. */
@Composable
fun SummaryStepContent(state: SetupUiState, gamer: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (gamer) 32.dp else 24.dp),
    ) {
        Text(
            stringResource(R.string.setup_summary_title),
            style = if (gamer) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.setup_summary_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        Spacer(Modifier.height(if (gamer) 10.dp else 18.dp))

        SummaryRow(
            label = stringResource(R.string.setup_summary_mode),
            gamer = gamer,
            value = stringResource(
                when (state.uiMode) {
                    UiMode.GAMER_LAUNCHER -> R.string.setup_mode_gamer
                    UiMode.PHONE -> R.string.setup_mode_phone
                    null -> R.string.setup_summary_unset
                },
            ),
        )
        SummaryRow(
            label = stringResource(R.string.setup_summary_target),
            gamer = gamer,
            value = stringResource(
                when (state.installTarget) {
                    InstallTarget.VIRTUAL_ENVIRONMENT -> R.string.setup_target_virtual
                    InstallTarget.DEVICE -> R.string.setup_target_device
                    null -> R.string.setup_summary_unset
                },
            ),
        )
        SummaryRow(
            label = stringResource(R.string.setup_summary_permissions),
            gamer = gamer,
            value = buildString {
                append(
                    stringResource(
                        if (state.notificationsGranted) {
                            R.string.setup_summary_perm_notifications_ok
                        } else {
                            R.string.setup_summary_perm_notifications_off
                        },
                    ),
                )
                if (state.installTarget == InstallTarget.DEVICE) {
                    append(" · ")
                    append(
                        stringResource(
                            if (state.installAllowed) {
                                R.string.setup_summary_perm_install_ok
                            } else {
                                R.string.setup_summary_perm_install_off
                            },
                        ),
                    )
                }
                append(" · ")
                append(
                    stringResource(
                        if (state.batteryIgnored) {
                            R.string.setup_summary_perm_battery_ok
                        } else {
                            R.string.setup_summary_perm_battery_off
                        },
                    ),
                )
            },
        )
        SummaryRow(
            label = stringResource(R.string.setup_summary_repos),
            gamer = gamer,
            value = stringResource(
                if (state.anyRepoEnabled) R.string.setup_summary_repos_ok else R.string.setup_summary_repos_off,
                state.repos.count { it.enabled },
            ),
        )

        Spacer(Modifier.height(if (gamer) 10.dp else 14.dp))
        SetupNote(stringResource(R.string.setup_changeable_later))
        Spacer(Modifier.height(if (gamer) 16.dp else 24.dp))
    }
}

/**
 * Linha do resumo. No layout gamer (paisagem, altura escassa) rótulo e valor
 * ficam na MESMA linha — sem isso, o resumo era cortado pelo rodapé fixo
 * (Voltar/Próximo) quando as barras do sistema tomavam o espaço vertical.
 */
@Composable
private fun SummaryRow(label: String, value: String, gamer: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (gamer) 3.dp else 4.dp),
    ) {
        if (gamer) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
