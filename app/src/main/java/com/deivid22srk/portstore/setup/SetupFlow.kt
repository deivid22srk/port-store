package com.deivid22srk.portstore.setup

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deivid22srk.portstore.AppGraph
import com.deivid22srk.portstore.launcher.input.GamepadMonitor
import com.deivid22srk.portstore.setup.SetupFlowLogic.STEP_INSTALL_TARGET
import com.deivid22srk.portstore.setup.SetupFlowLogic.STEP_MODE
import com.deivid22srk.portstore.setup.SetupFlowLogic.STEP_PERMISSIONS
import com.deivid22srk.portstore.setup.SetupFlowLogic.STEP_REPOSITORIES
import com.deivid22srk.portstore.setup.SetupFlowLogic.STEP_SUMMARY

/**
 * Assistente de configuração ADAPTATIVO em 5 etapas:
 *
 * 1. Modo de uso (Celular / Gamer Launcher) — sempre no layout retrato;
 * 2. Destino de instalação dos jogos (ambiente virtual / aparelho);
 * 3. Permissões (condicionais ao destino);
 * 4. Repositórios (jogos + dados complementares com exportar/importar);
 * 5. Conclusão (resumo + "Começar").
 *
 * A partir da etapa 2, no modo Gamer Launcher, o conteúdo é renderizado pelo
 * [GamerSetupScaffold] (paisagem, foco navegável por controle) com o MESMO
 * conteúdo das etapas — o que muda é só o chrome, via [SetupScaffold].
 */
@Composable
fun SetupFlow(
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val vm: SetupViewModel = viewModel {
        SetupViewModel(
            catalog = AppGraph.catalog,
            settings = AppGraph.settings,
            dataBackup = ComplementaryDataBackupManager(
                context = context.applicationContext,
                db = AppGraph.db,
            ),
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val showLoading by vm.showLoading.collectAsStateWithLifecycle()
    val settingsRepo = AppGraph.settings

    // Modo console: paisagem + tela cheia imersiva a partir da etapa 2 (e na
    // tela de carregamento pós-"Começar"). Ao sair do layout, tudo volta.
    val immersive = state.uiMode == UiMode.GAMER_LAUNCHER &&
        (state.currentStep >= STEP_INSTALL_TARGET || showLoading)
    val latestImmersive by rememberUpdatedState(immersive)

    /** Esconde as barras do sistema e trava a orientação em paisagem. */
    fun applyImmersive() {
        val activity = SetupViewModel.findActivity(context)
        val controller = activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView)
        }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        // Paisagem nos dois lados (segue o sensor, como um portátil).
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    // Atualiza estados de permissão ao voltar das telas de sistema (onResume).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.refreshPermissions(context)
                if (latestImmersive) {
                    // REAPLICA o imersivo: quando outra janela cobre o app
                    // (diálogos de permissão, seletor SAF), o Android restaura
                    // as barras do sistema — sem isto, a status bar e a
                    // navegação do sistema voltavam a sobrepor a UI gamer.
                    applyImmersive()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { vm.refreshPermissions(context) }
    // VM de escopo da Activity compartilhado com "Refazer setup"/repositórios:
    // limpa resíduos de sessões anteriores (diálogo aberto, flags de loading).
    LaunchedEffect(Unit) { vm.resetSessionState() }

    val gamer = state.gamerLayout

    // Monitor de gamepad só no layout gamer (dicas de botões contexto A/B/Start).
    val monitor = if (gamer) {
        val m = remember { GamepadMonitor(context) }
        DisposableEffect(Unit) {
            m.start()
            onDispose { m.stop() }
        }
        m
    } else {
        null
    }

    DisposableEffect(immersive) {
        if (immersive) applyImmersive()
        onDispose {
            if (immersive) {
                val activity = SetupViewModel.findActivity(context)
                val controller = activity?.window?.let { window ->
                    WindowCompat.getInsetsController(window, window.decorView)
                }
                controller?.show(WindowInsetsCompat.Type.systemBars())
                // O manifest não define orientation: UNSPECIFIED restaura a rotação livre.
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    // Tela de carregamento (após "Começar") — rota final igual ao assistente
    // original; ao terminar, o MainActivity abre a interface do modo escolhido.
    if (showLoading) {
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
        // O diálogo não pausa a Activity (Android 12+): reavalia imediatamente.
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

    // SAF: exportação cria arquivo novo; importação abre arquivo existente.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ComplementaryDataFormat.MIME_TYPE),
    ) { uri -> uri?.let(vm::exportDataRepoTo) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::prepareDataRepoImport) }

    BackHandler(enabled = true) {
        when {
            state.currentStep > 0 -> vm.goToPreviousStep()
            !settingsRepo.settings.value.setupCompleted -> (context as? Activity)?.finishAffinity()
                ?: Unit
            else -> {
                settingsRepo.finishSetupSession()
                onFinished()
            }
        }
    }

    SetupScaffold(
        gamer = gamer,
        step = state.currentStep,
        totalSteps = state.totalSteps,
        canProceed = state.canProceed,
        nextLabel = if (state.currentStep == STEP_SUMMARY) {
            context.getString(com.deivid22srk.portstore.R.string.setup_start)
        } else {
            context.getString(com.deivid22srk.portstore.R.string.setup_next)
        },
        backVisible = state.currentStep > 0,
        onBack = { vm.goToPreviousStep() },
        onNext = {
            if (state.currentStep == STEP_SUMMARY) vm.completeSetup() else vm.goToNextStep()
        },
        monitor = monitor,
        stepContent = { step ->
            when (step) {
                STEP_MODE -> ModeStepContent(
                    state = state,
                    gamer = false, // etapa 1 é sempre no layout retrato
                    onChoose = vm::chooseMode,
                )

                STEP_INSTALL_TARGET -> InstallTargetStepContent(
                    state = state,
                    gamer = gamer,
                    onChoose = vm::chooseInstallTarget,
                )

                STEP_PERMISSIONS -> PermissionsStepContent(
                    state = state,
                    gamer = gamer,
                    onAllowNotifications = {
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
                    onContinueWithoutNotifications = vm::setContinueWithoutNotifications,
                )

                STEP_REPOSITORIES -> RepositoriesStepContent(
                    state = state,
                    gamer = gamer,
                    onExportDataRepo = {
                        runCatching {
                            exportLauncher.launch(ComplementaryDataFormat.suggestedFileName())
                        }
                    },
                    onImportDataRepo = {
                        runCatching {
                            importLauncher.launch(arrayOf(ComplementaryDataFormat.MIME_TYPE))
                        }
                    },
                    vm = vm,
                )

                else -> SummaryStepContent(state = state, gamer = gamer)
            }
        },
    )
}
