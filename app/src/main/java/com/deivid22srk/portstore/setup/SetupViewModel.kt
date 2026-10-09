package com.deivid22srk.portstore.setup

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deivid22srk.portstore.catalog.CatalogRepository
import com.deivid22srk.portstore.catalog.LoadProgress
import com.deivid22srk.portstore.catalog.RefreshResult
import com.deivid22srk.portstore.db.DataRepoEntity
import com.deivid22srk.portstore.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Estado da caixa de diálogo de adicionar/editar repositório. */
data class RepoDialogState(
    val visible: Boolean = false,
    val editingId: String? = null,
    val name: String = "",
    val url: String = "",
    val verifying: Boolean = false,
    val verifiedCount: Int? = null,
    val error: String? = null,
)

/** Estado geral do assistente. */
data class SetupUiState(
    val step: Int = 0,
    val notificationsGranted: Boolean = false,
    val notificationsPermanentlyDenied: Boolean = false,
    val continueWithoutNotifications: Boolean = false,
    val installAllowed: Boolean = false,
    val batteryIgnored: Boolean = false,
    val repos: List<DataRepoEntity> = emptyList(),
    val anyRepoEnabled: Boolean = false,
    val repoDialog: RepoDialogState = RepoDialogState(),
    val repoBusyId: String? = null,
)

class SetupViewModel(
    private val catalog: CatalogRepository,
    private val settings: SettingsRepository,
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

    /** Tela de carregamento visível (após "Concluir"). */
    private val _showLoading = MutableStateFlow(false)
    val showLoading: StateFlow<Boolean> = _showLoading.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(step = settings.settings.value.setupStep)
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
     * SetupFlow cair direto no LoadingScreen -> onDone -> aba Início (o clique
     * em "Gerenciar repositórios"/"Refazer setup" parecia só redirecionar p/ Home).
     */
    fun resetSessionState() {
        if (_showLoading.value) return // carregamento ativo: não mexer nos flags
        _loadingFinished.value = false
        _loadingTotalError.value = false
        _state.value = _state.value.copy(
            step = settings.settings.value.setupStep,
            repoDialog = RepoDialogState(),
            repoBusyId = null,
        )
    }

    // ------------------------------------------------------------------
    // Etapas
    // ------------------------------------------------------------------

    fun setStep(step: Int) {
        _state.value = _state.value.copy(step = step.coerceIn(0, 1))
        viewModelScope.launch { settings.setSetupStep(_state.value.step) }
    }

    fun nextStep() = setStep(_state.value.step + 1)
    fun previousStep() = setStep(_state.value.step - 1)

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

    fun canLeavePermissionsStep(): Boolean =
        _state.value.notificationsGranted || _state.value.continueWithoutNotifications

    // ------------------------------------------------------------------
    // Repositórios
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
            // Atualiza só este: desabilita temporariamente os outros? Não —
            // refreshAll com ETag é barato; usamos ele e restauramos o flag.
            catalog.refreshAll(force = true)
            _state.value = _state.value.copy(repoBusyId = null)
        }
    }

    // ------------------------------------------------------------------
    // Concluir -> tela de carregamento
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
        setStep(1)
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
