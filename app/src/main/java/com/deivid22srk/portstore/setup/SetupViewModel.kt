package com.deivid22srk.portstore.setup

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deivid22srk.portstore.R
import com.deivid22srk.portstore.catalog.LoadProgress
import com.deivid22srk.portstore.catalog.RefreshResult
import com.deivid22srk.portstore.catalog.SetupCatalogGateway
import com.deivid22srk.portstore.db.DataRepoEntity
import com.deivid22srk.portstore.settings.SetupSettingsGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Estado da caixa de diálogo de adicionar/editar repositório de jogos. */
data class RepoDialogState(
    val visible: Boolean = false,
    val editingId: String? = null,
    val name: String = "",
    val url: String = "",
    val verifying: Boolean = false,
    val verifiedCount: Int? = null,
    val error: String? = null,
)

/**
 * Estado da seção "Repositório de Dados Complementares" (etapa 4):
 * mensagens/erros são recursos de string (sem texto hardcoded na UI).
 */
data class ComplementaryBackupUiState(
    val busy: Boolean = false,
    /** Resumo validado aguardando confirmação (mesclar/substituir). */
    val pendingImport: ComplementarySummary? = null,
    /** URI SAF do arquivo pendente de confirmação (memória de sessão). */
    val pendingImportUri: Uri? = null,
    @StringRes val messageRes: Int? = null,
    val messageArg: Int = 0,
    @StringRes val errorRes: Int? = null,
)

/** Estado geral do assistente adaptativo (5 etapas). */
data class SetupUiState(
    /** Etapa atual (0..4). Persistida — restaura após rotação/processo morto. */
    val currentStep: Int = 0,
    val totalSteps: Int = SetupFlowLogic.TOTAL_STEPS,
    /** Modo de uso escolhido (etapa 1); nulo até escolher. */
    val uiMode: UiMode? = null,
    /** Destino de instalação escolhido (etapa 2); nulo até escolher. */
    val installTarget: InstallTarget? = null,
    val notificationsGranted: Boolean = false,
    val notificationsPermanentlyDenied: Boolean = false,
    val continueWithoutNotifications: Boolean = false,
    val installAllowed: Boolean = false,
    val batteryIgnored: Boolean = false,
    val repos: List<DataRepoEntity> = emptyList(),
    val anyRepoEnabled: Boolean = false,
    val repoDialog: RepoDialogState = RepoDialogState(),
    val repoBusyId: String? = null,
    val dataBackup: ComplementaryBackupUiState = ComplementaryBackupUiState(),
) {
    /** Layout do launcher (paisagem, foco por controle) a partir da etapa 2. */
    val gamerLayout: Boolean get() = SetupFlowLogic.isGamerLayout(uiMode, currentStep)

    /** O botão principal do rodapé pode avançar? */
    val canProceed: Boolean get() = SetupFlowLogic.canProceed(this)

    /** Para onde "Começar" leva (etapa 5). */
    val destination: SetupDestination?
        get() = uiMode?.let(SetupFlowLogic::destination)

    /** Permissões visíveis na etapa 3, conforme o destino escolhido. */
    val permissionRows: List<PermissionRowSpec>
        get() = SetupFlowLogic.visiblePermissions(installTarget)
}

/**
 * ViewModel do assistente de configuração adaptativo.
 *
 * Um único [SetupUiState] carrega modo de uso, destino de instalação,
 * permissões, repositórios e etapa corrente; as escolhas são persistidas
 * no DataStore via [SetupSettingsGateway] (que espelha as flags usadas pelo
 * roteamento do app), então voltar entre etapas, girar o aparelho ou o
 * processo ser morto não perde o progresso — a persistência cobre o papel
 * do SavedStateHandle com um alcance maior (sobrevive a reabrir o app).
 */
class SetupViewModel(
    private val catalog: SetupCatalogGateway,
    private val settings: SetupSettingsGateway,
    private val dataBackup: ComplementaryDataBackupOps,
) : ViewModel() {

    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    /** Progresso da tela de carregamento (null = não está carregando). */
    val loadProgress: StateFlow<LoadProgress?> = catalog.loadProgress

    private val _loadingFinished = MutableStateFlow(false)
    val loadingFinished: StateFlow<Boolean> = _loadingFinished.asStateFlow()

    private val _loadingTotalError = MutableStateFlow(false)
    val loadingTotalError: StateFlow<Boolean> = _loadingTotalError.asStateFlow()

    val lastRefreshResult = MutableStateFlow<RefreshResult?>(null)

    /** Tela de carregamento visível (após "Começar"). */
    private val _showLoading = MutableStateFlow(false)
    val showLoading: StateFlow<Boolean> = _showLoading.asStateFlow()

    init {
        viewModelScope.launch {
            // Aguarda o DataStore carregar (MainActivity só abre o setup após
            // bootReady, mas o first() protege reentradas).
            val s = settings.settings.first { it.bootReady }
            _state.value = _state.value.copy(
                currentStep = s.setupStep.coerceIn(0, SetupFlowLogic.TOTAL_STEPS - 1),
                uiMode = s.uiMode,
                installTarget = s.installTarget,
                continueWithoutNotifications = s.continueWithoutNotifications,
            )
        }
        viewModelScope.launch {
            catalog.observeRepos().collect { repos ->
                _state.value = _state.value.copy(
                    repos = repos,
                    anyRepoEnabled = repos.any { it.enabled },
                )
            }
        }
    }

    /**
     * Limpa resíduos de uma sessão anterior ao (re)abrir o assistente ou a tela
     * de repositórios. O ViewModel tem escopo da Activity e é compartilhado:
     * sem isto, o loadingFinished=true deixado pelo primeiro setup fazia o
     * SetupFlow cair direto no LoadingScreen -> onDone -> aba Início.
     */
    fun resetSessionState() {
        if (_showLoading.value) return // carregamento ativo: não mexer nos flags
        _loadingFinished.value = false
        _loadingTotalError.value = false
        _state.value = _state.value.copy(
            currentStep = settings.settings.value.setupStep.coerceIn(0, SetupFlowLogic.TOTAL_STEPS - 1),
            uiMode = settings.settings.value.uiMode,
            installTarget = settings.settings.value.installTarget,
            repoDialog = RepoDialogState(),
            repoBusyId = null,
            dataBackup = ComplementaryBackupUiState(),
        )
    }

    // ------------------------------------------------------------------
    // Navegação entre etapas
    // ------------------------------------------------------------------

    fun setStep(step: Int) {
        val clamped = step.coerceIn(0, SetupFlowLogic.TOTAL_STEPS - 1)
        _state.value = _state.value.copy(currentStep = clamped)
        viewModelScope.launch { settings.setSetupStep(clamped) }
    }

    fun goToNextStep() = setStep(_state.value.currentStep + 1)
    fun goToPreviousStep() = setStep(_state.value.currentStep - 1)

    // ------------------------------------------------------------------
    // Etapa 1 — modo de uso
    // ------------------------------------------------------------------

    fun chooseMode(mode: UiMode) {
        _state.value = _state.value.copy(uiMode = mode)
        viewModelScope.launch { settings.setUiMode(mode) }
    }

    // ------------------------------------------------------------------
    // Etapa 2 — destino de instalação
    // ------------------------------------------------------------------

    fun chooseInstallTarget(target: InstallTarget) {
        _state.value = _state.value.copy(installTarget = target)
        viewModelScope.launch { settings.setInstallTarget(target) }
    }

    // ------------------------------------------------------------------
    // Etapa 3 — permissões
    // ------------------------------------------------------------------

    fun refreshPermissions(context: Context) {
        val grantedNotifications = com.deivid22srk.portstore.service.Notifications.canPost(context)
        val installAllowed = runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
        val battery = runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)
        _state.value = _state.value.copy(
            notificationsGranted = grantedNotifications,
            installAllowed = installAllowed,
            batteryIgnored = battery,
        )
    }

    fun onNotificationsDeniedAgain(permanently: Boolean) {
        _state.value = _state.value.copy(notificationsPermanentlyDenied = permanently)
    }

    fun setContinueWithoutNotifications(value: Boolean) {
        _state.value = _state.value.copy(continueWithoutNotifications = value)
        viewModelScope.launch { settings.setContinueWithoutNotifications(value) }
    }

    fun canLeavePermissionsStep(): Boolean = SetupFlowLogic.canLeavePermissions(
        notificationsGranted = _state.value.notificationsGranted,
        continueWithoutNotifications = _state.value.continueWithoutNotifications,
        installAllowed = _state.value.installAllowed,
        installTarget = _state.value.installTarget,
    )

    // ------------------------------------------------------------------
    // Etapa 4 — repositório de jogos
    // ------------------------------------------------------------------

    fun openNewRepoDialog() {
        _state.value = _state.value.copy(
            repoDialog = RepoDialogState(visible = true),
        )
    }

    fun openEditRepoDialog(repo: DataRepoEntity) {
        _state.value = _state.value.copy(
            repoDialog = RepoDialogState(visible = true, editingId = repo.id, name = repo.name, url = rawInputFor(repo)),
        )
    }

    /** Reconstrói a "URL amigável" para edição (link do GitHub quando possível). */
    private fun rawInputFor(repo: DataRepoEntity): String {
        val m = Regex("raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/").find(repo.url)
        return if (m != null) "https://github.com/${m.groupValues[1]}/${m.groupValues[2]}" else repo.url
    }

    fun dismissRepoDialog() {
        _state.value = _state.value.copy(repoDialog = RepoDialogState())
    }

    fun updateDialog(name: String, url: String) {
        _state.value = _state.value.copy(
            repoDialog = _state.value.repoDialog.copy(name = name, url = url),
        )
    }

    fun verifyDialogRepo() {
        val dialog = _state.value.repoDialog
        if (dialog.verifying) return
        _state.value = _state.value.copy(
            repoDialog = dialog.copy(verifying = true, verifiedCount = null, error = null),
        )
        viewModelScope.launch {
            catalog.verifyRepo(dialog.url)
                .onSuccess { v ->
                    _state.value = _state.value.copy(
                        repoDialog = _state.value.repoDialog.copy(
                            verifying = false,
                            verifiedCount = v.gameCount,
                            name = _state.value.repoDialog.name.ifBlank { v.suggestedName },
                        ),
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        repoDialog = _state.value.repoDialog.copy(verifying = false, error = e.message),
                    )
                }
        }
    }

    fun saveDialogRepo() {
        val dialog = _state.value.repoDialog
        viewModelScope.launch {
            val result = if (dialog.editingId != null) {
                catalog.updateRepo(dialog.editingId, dialog.name, dialog.url)
            } else {
                catalog.addRepo(dialog.name, dialog.url)
            }
            result
                .onSuccess { dismissRepoDialog() }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        repoDialog = _state.value.repoDialog.copy(error = e.message, verifying = false),
                    )
                }
        }
    }

    fun setRepoEnabled(repo: DataRepoEntity, enabled: Boolean) {
        viewModelScope.launch { catalog.setRepoEnabled(repo.id, enabled) }
    }

    fun removeRepo(repo: DataRepoEntity) {
        viewModelScope.launch { catalog.removeRepo(repo.id) }
    }

    fun moveRepo(repo: DataRepoEntity, up: Boolean) {
        viewModelScope.launch { catalog.reorderRepo(repo.id, up) }
    }

    fun refreshSingleRepo(repo: DataRepoEntity) {
        if (_state.value.repoBusyId != null) return
        _state.value = _state.value.copy(repoBusyId = repo.id)
        viewModelScope.launch {
            // refreshAll com ETag é barato; usamos ele e restauramos o flag.
            catalog.refreshAll(force = true)
            _state.value = _state.value.copy(repoBusyId = null)
        }
    }

    // ------------------------------------------------------------------
    // Etapa 4 — repositório de dados complementares (exportar/importar)
    // ------------------------------------------------------------------

    /** Exporta para o arquivo escolhido via SAF (ACTION_CREATE_DOCUMENT). */
    fun exportDataRepoTo(uri: Uri) {
        if (_state.value.dataBackup.busy) return
        _state.value = _state.value.copy(
            dataBackup = ComplementaryBackupUiState(busy = true),
        )
        viewModelScope.launch {
            dataBackup.exportTo(uri)
                .onSuccess { count ->
                    _state.value = _state.value.copy(
                        dataBackup = ComplementaryBackupUiState(
                            messageRes = R.string.setup_data_export_done,
                            messageArg = count,
                        ),
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        dataBackup = backupErrorState(e),
                    )
                }
        }
    }

    /** Lê e valida o arquivo escolhido via SAF, mostrando o resumo. */
    fun prepareDataRepoImport(uri: Uri) {
        if (_state.value.dataBackup.busy) return
        _state.value = _state.value.copy(
            dataBackup = ComplementaryBackupUiState(busy = true),
        )
        viewModelScope.launch {
            dataBackup.readSummary(uri)
                .onSuccess { summary ->
                    _state.value = _state.value.copy(
                        dataBackup = ComplementaryBackupUiState(
                            pendingImport = summary,
                            pendingImportUri = uri,
                        ),
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(dataBackup = backupErrorState(e))
                }
        }
    }

    /** Aplica a importação confirmada (mesclar ou substituir). */
    fun confirmDataRepoImport(mode: ComplementaryImportMode) {
        val pending = _state.value.dataBackup
        val uri = pending.pendingImportUri ?: return
        if (pending.busy) return
        _state.value = _state.value.copy(
            dataBackup = ComplementaryBackupUiState(busy = true),
        )
        viewModelScope.launch {
            dataBackup.importFrom(uri, mode)
                .onSuccess { count ->
                    // Reflete os dados importados imediatamente no catálogo.
                    catalog.loadFromCache()
                    _state.value = _state.value.copy(
                        dataBackup = ComplementaryBackupUiState(
                            messageRes = R.string.setup_data_import_done,
                            messageArg = count,
                        ),
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(dataBackup = backupErrorState(e))
                }
        }
    }

    fun dismissDataRepoImport() {
        _state.value = _state.value.copy(dataBackup = ComplementaryBackupUiState())
    }

    fun clearDataBackupFeedback() {
        if (_state.value.dataBackup.busy) return
        _state.value = _state.value.copy(dataBackup = ComplementaryBackupUiState())
    }

    private fun backupErrorState(e: Throwable): ComplementaryBackupUiState {
        val error = (e as? ComplementaryDataException)?.error ?: ComplementaryDataError.IO_ERROR
        return ComplementaryBackupUiState(errorRes = error.messageRes)
    }

    // ------------------------------------------------------------------
    // Etapa 5 — conclusão
    // ------------------------------------------------------------------

    fun completeSetup() {
        // Mantém a sessão de setup visível enquanto a tela de carregamento roda.
        settings.beginSetupSession()
        _showLoading.value = true
        _loadingFinished.value = false
        _loadingTotalError.value = false
        viewModelScope.launch {
            settings.setSetupCompleted(true)
        }
        runLoading()
    }

    fun retryLoading() {
        _loadingTotalError.value = false
        runLoading()
    }

    fun continueOffline() {
        viewModelScope.launch {
            catalog.loadFromCache()
            catalog.markDone()
            finishLoading()
        }
    }

    fun cancelLoading() {
        catalog.clearProgress()
        _showLoading.value = false
        _loadingFinished.value = false
        setStep(SetupFlowLogic.STEP_SUMMARY)
    }

    private fun runLoading() {
        viewModelScope.launch {
            val result = catalog.refreshAll(force = false)
            lastRefreshResult.value = result
            if (result.allFailed && !result.hasCache) {
                _loadingTotalError.value = true
            } else {
                catalog.markImagesPhase()
                catalog.markDone()
                finishLoading()
            }
        }
    }

    private fun finishLoading() {
        _loadingFinished.value = true
        _showLoading.value = false
        catalog.clearProgress()
        settings.finishSetupSession()
    }

    companion object {
        fun findActivity(context: Context): Activity? {
            var ctx = context
            while (ctx is ContextWrapper) {
                if (ctx is Activity) return ctx
                ctx = ctx.baseContext
            }
            return null
        }

        fun notificationSettingsIntent(context: Context): Intent {
            return if (Build.VERSION.SDK_INT >= 26) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
            }
        }

        fun unknownSourcesIntent(context: Context): Intent =
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

        fun batteryIntent(context: Context): Intent =
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    }
}
