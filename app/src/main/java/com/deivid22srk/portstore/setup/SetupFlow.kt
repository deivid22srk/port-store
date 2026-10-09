package com.deivid22srk.portstore.setup

import android.app.Activity
import android.content.Context
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deivid22srk.portstore.db.DataRepoEntity
import com.deivid22srk.portstore.ui.theme.Lime
import com.deivid22srk.portstore.ui.theme.TextSecondary

/** Assistente de configuração em etapas (permissões -> repositórios -> carga). */
@Composable
fun SetupFlow(
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val vm: SetupViewModel = viewModel {
        SetupViewModel(
            catalog = com.deivid22srk.portstore.AppGraph.catalog,
            settings = com.deivid22srk.portstore.AppGraph.settings,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val showLoading by vm.showLoading.collectAsStateWithLifecycle()
    val loadingFinished by vm.loadingFinished.collectAsStateWithLifecycle()
    val settingsRepo = com.deivid22srk.portstore.AppGraph.settings

    // Atualiza estados de permissão ao voltar das configurações do sistema.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshPermissions(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { vm.refreshPermissions(context) }

    if (showLoading || loadingFinished) {
        LoadingScreen(
            viewModel = vm,
            onDone = onFinished,
            onCancel = { vm.cancelLoading() },
        )
        return
    }

    val notificationsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // O diálogo de permissão não pausa a Activity (Android 12+), então
        // ON_RESUME não dispara: reavalia o estado imediatamente, tanto no
        // "permitir" quanto no "negar".
        vm.refreshPermissions(context)
        if (!granted) {
            val activity = SetupViewModel.findActivity(context)
            val canAskAgain = activity == null ||
                activity.shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)
            vm.onNotificationsDeniedAgain(!canAskAgain)
        }
    }
    val intentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { vm.refreshPermissions(context) }

    val animationsEnabled = remember { animationsEnabledOf(context) }

    BackHandler(enabled = true) {
        when {
            state.step > 0 -> vm.previousStep()
            !settingsRepo.settings.value.setupCompleted -> (context as? Activity)?.finishAffinity()
                ?: Unit
            else -> {
                settingsRepo.finishSetupSession()
                onFinished()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        StepHeader(step = state.step)

        AnimatedContent(
            // weight(1f): mede o rodapé PRIMEIRO e reserva o espaço restante
            // para o conteúdo da etapa. Sem weight, o conteúdo fillMaxSize
            // consome toda a altura e o SetupFooter fica com 0dp (invisível).
            modifier = Modifier.weight(1f),
            targetState = state.step,
            transitionSpec = {
                if (animationsEnabled) {
                    (slideInHorizontally { it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it / 3 } + fadeOut())
                } else {
                    fadeIn() togetherWith fadeOut()
                }
            },
            label = "setupStep",
        ) { step ->
            when (step) {
                    0 -> PermissionsStep(
                    state = state,
                    vm = vm,
                    onRequestNotifications = {
                        notificationsLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    },
                    onOpenNotificationSettings = {
                        intentLauncher.launch(SetupViewModel.notificationSettingsIntent(context))
                    },
                    onOpenUnknownSources = {
                        runCatching { intentLauncher.launch(SetupViewModel.unknownSourcesIntent(context)) }
                    },
                    onOpenBattery = {
                        runCatching { intentLauncher.launch(SetupViewModel.batteryIntent(context)) }
                    },
                )
                    else -> RepositoriesStep(state = state, vm = vm)
            }
        }

        SetupFooter(
            step = state.step,
            // Deriva do estado coletado (reativo) em vez do getter do VM:
            // garante que o botão reaja à concessão da permissão na hora.
            canProceed = if (state.step == 0) {
                state.notificationsGranted || state.continueWithoutNotifications
            } else {
                state.anyRepoEnabled
            },
            onBack = {
                if (state.step > 0) vm.previousStep()
                else if (settingsRepo.settings.value.setupCompleted) {
                    settingsRepo.finishSetupSession()
                    onFinished()
                }
            },
            onNext = {
                if (state.step == 0) vm.nextStep() else vm.completeSetup()
            },
        )
    }
}

/** Respeita animações desativadas pelo sistema. */
private fun animationsEnabledOf(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) != 0f
}.getOrDefault(true)

@Composable
private fun StepHeader(step: Int) {
    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
        Text(
            text = "Etapa ${step + 1} de 2",
            style = MaterialTheme.typography.labelLarge,
            color = Lime,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(2) { i ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(5.dp)
                        .background(
                            color = if (i <= step) Lime else MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(3.dp),
                        ),
                )
            }
        }
    }
}

@Composable
private fun SetupFooter(
    step: Int,
    canProceed: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step > 0) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(0.35f),
                    shape = RoundedCornerShape(24.dp),
                ) { Text("Voltar") }
            }
            Button(
                onClick = onNext,
                enabled = canProceed,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Lime,
                    contentColor = Color(0xFF171800),
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    disabledContentColor = TextSecondary,
                ),
            ) {
                Text(
                    if (step == 1) "Concluir" else "Próximo",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
    }
}

// ----------------------------------------------------------------------
// Etapa 1 — Permissões
// ----------------------------------------------------------------------

@Composable
private fun PermissionsStep(
    state: SetupUiState,
    vm: SetupViewModel,
    onRequestNotifications: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenUnknownSources: () -> Unit,
    onOpenBattery: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Text("Permissões", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "O Port Store precisa de algumas permissões para baixar e instalar os ports. " +
                "Você pode conceder agora ou depois — nada é enviado para fora do seu aparelho.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        Spacer(Modifier.height(20.dp))

        PermissionCard(
            icon = Icons.Rounded.Notifications,
            title = "Notificações",
            description = "Mostra o progresso dos downloads na barra de status e avisa quando terminar.",
            required = true,
            granted = state.notificationsGranted,
            permanentlyDenied = state.notificationsPermanentlyDenied,
            onAllow = onRequestNotifications,
            onOpenSettings = onOpenNotificationSettings,
        )
        Spacer(Modifier.height(12.dp))

        if (state.notificationsGranted) {
            LaunchedEffect(state.notificationsGranted) {
                vm.setContinueWithoutNotifications(false)
            }
        }

        if (!state.notificationsGranted) {
            TextButton(onClick = { vm.setContinueWithoutNotifications(!state.continueWithoutNotifications) }) {
                Text(
                    if (state.continueWithoutNotifications) "✓ Continuar sem notificações"
                    else "Continuar sem notificações",
                )
            }
            if (state.continueWithoutNotifications) {
                Text(
                    "Sem notificações, o progresso dos downloads não aparece na barra de status. " +
                        "Os downloads continuam funcionando normalmente.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        PermissionCard(
            icon = Icons.Rounded.InstallMobile,
            title = "Instalar apps desconhecidos",
            description = "Permite instalar o APK do port depois de baixado. Obrigatório para jogar, mas você pode configurar na hora da instalação.",
            required = false,
            granted = state.installAllowed,
            permanentlyDenied = false,
            onAllow = onOpenUnknownSources,
            onOpenSettings = onOpenUnknownSources,
        )
        Spacer(Modifier.height(12.dp))

        PermissionCard(
            icon = Icons.Rounded.BatteryFull,
            title = "Ignorar otimização de bateria",
            description = "Mantém downloads longos rodando em segundo plano sem serem interrompidos.",
            required = false,
            granted = state.batteryIgnored,
            permanentlyDenied = false,
            onAllow = onOpenBattery,
            onOpenSettings = onOpenBattery,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    description: String,
    required: Boolean,
    granted: Boolean,
    permanentlyDenied: Boolean,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (granted) Lime else TextSecondary,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (required) {
                        Spacer(Modifier.width(6.dp))
                        Text("Obrigatória", style = MaterialTheme.typography.labelSmall, color = Lime)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(description, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            Spacer(Modifier.width(8.dp))
            if (granted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = Lime, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Concedida", style = MaterialTheme.typography.labelMedium, color = Lime)
                }
            } else if (permanentlyDenied) {
                TextButton(onClick = onOpenSettings) { Text("Abrir configurações") }
            } else {
                Button(
                    onClick = onAllow,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Color(0xFF171800)),
                ) { Text("Permitir") }
            }
        }
    }
}

// ----------------------------------------------------------------------
// Etapa 2 — Repositórios
// ----------------------------------------------------------------------

@Composable
private fun RepositoriesStep(state: SetupUiState, vm: SetupViewModel) {
    var pendingDelete by remember { mutableStateOf<DataRepoEntity?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                Text("Repositórios", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Os repositórios fornecem os dados dos jogos (título, capas, screenshots e links). " +
                        "O Port DB é a fonte oficial e já vem ativado.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(14.dp))
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.repos, key = { it.id }) { repo ->
                    RepoCard(
                        repo = repo,
                        busy = state.repoBusyId == repo.id,
                        isFirst = state.repos.firstOrNull()?.id == repo.id,
                        isLast = state.repos.lastOrNull()?.id == repo.id,
                        vm = vm,
                        onRemove = { pendingDelete = repo },
                    )
                }
            }
        }

        androidx.compose.material3.ExtendedFloatingActionButton(
            onClick = vm::openNewRepoDialog,
            containerColor = Lime,
            contentColor = Color(0xFF171800),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
            icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
            text = { Text("Adicionar repositório") },
        )
    }

    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remover repositório") },
            text = { Text("Remover \"${pendingDelete?.name}\" também apaga do cache os jogos vindos dele.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeRepo(pendingDelete!!)
                    pendingDelete = null
                }) { Text("Remover", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancelar") }
            },
        )
    }

    val dialog = state.repoDialog
    if (dialog.visible) {
        RepoDialog(vm = vm, state = dialog)
    }
}

@Composable
private fun RepoCard(
    repo: DataRepoEntity,
    busy: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    vm: SetupViewModel,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { vm.openEditRepoDialog(repo) }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Storage,
                contentDescription = null,
                tint = if (repo.enabled) Lime else TextSecondary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(repo.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val detail = when {
                    repo.status == "ok" && repo.gameCount > 0 -> "${repo.gameCount} jogos • atualizado"
                    repo.status == "erro" -> "Erro: ${repo.lastError ?: "falha ao carregar"}"
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
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Mais opções", tint = TextSecondary)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Atualizar / testar") },
                            onClick = { menuOpen = false; vm.refreshSingleRepo(repo) },
                            leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Editar") },
                            onClick = { menuOpen = false; vm.openEditRepoDialog(repo) },
                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Mover para cima") },
                            enabled = !isFirst,
                            onClick = { menuOpen = false; vm.moveRepo(repo, up = true) },
                            leadingIcon = { Icon(Icons.Rounded.ArrowUpward, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Mover para baixo") },
                            enabled = !isLast,
                            onClick = { menuOpen = false; vm.moveRepo(repo, up = false) },
                            leadingIcon = { Icon(Icons.Rounded.ArrowDownward, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Remover", color = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; onRemove() },
                            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
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

@Composable
private fun RepoDialog(vm: SetupViewModel, state: RepoDialogState) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = vm::dismissRepoDialog,
        title = { Text(if (state.editingId == null) "Adicionar repositório" else "Editar repositório") },
        text = {
            Column(modifier = Modifier.imePadding()) {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = { name -> vm.updateDialog(name, state.url) },
                    label = { Text("Nome (opcional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.url,
                    onValueChange = { url -> vm.updateDialog(state.name, url) },
                    label = { Text("URL do repositório ou do games.json") },
                    placeholder = { Text("https://github.com/dono/repo") },
                    singleLine = true,
                    trailingIcon = {
                        TextButton(onClick = {
                            val text = clipboard.getText()?.toString()?.trim()
                            if (!text.isNullOrBlank()) vm.updateDialog(state.name, text)
                        }) { Text("Colar") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = vm::verifyDialogRepo, enabled = !state.verifying && state.url.isNotBlank()) {
                        Text("Verificar", color = Lime)
                    }
                    when {
                        state.verifying -> {
                            Spacer(Modifier.width(8.dp))
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Verificando…", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                        state.verifiedCount != null -> {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Válido • ${state.verifiedCount} jogos encontrados",
                                style = MaterialTheme.typography.bodySmall,
                                color = Lime,
                            )
                        }
                    }
                }
                state.error?.let { err ->
                    Spacer(Modifier.height(4.dp))
                    Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = vm::saveDialogRepo,
                enabled = state.url.isNotBlank() && (state.verifiedCount != null || state.editingId != null || state.error == null),
                colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Color(0xFF171800)),
            ) { Text("Salvar") }
        },
        dismissButton = {
            TextButton(onClick = vm::dismissRepoDialog) { Text("Cancelar") }
        },
    )
}
