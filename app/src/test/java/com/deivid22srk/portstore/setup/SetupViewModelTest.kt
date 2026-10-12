package com.deivid22srk.portstore.setup

import com.deivid22srk.portstore.catalog.Catalog
import com.deivid22srk.portstore.catalog.LoadProgress
import com.deivid22srk.portstore.catalog.RefreshResult
import com.deivid22srk.portstore.catalog.RepoVerification
import com.deivid22srk.portstore.catalog.SetupCatalogGateway
import com.deivid22srk.portstore.db.DataRepoEntity
import com.deivid22srk.portstore.settings.AppSettings
import com.deivid22srk.portstore.settings.SetupSettingsGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private lateinit var settings: FakeSettingsGateway
    private lateinit var catalog: FakeCatalogGateway
    private lateinit var backup: FakeDataBackup
    private lateinit var vm: SetupViewModel

    @Before
    fun setUp() {
        settings = FakeSettingsGateway()
        catalog = FakeCatalogGateway()
        backup = FakeDataBackup()
        vm = SetupViewModel(catalog = catalog, settings = settings, dataBackup = backup)
    }

    @After
    fun tearDown() {
        // MainDispatcherRule cuida do reset do Main.
    }

    // ------------------------------------------------------------------
    // Etapa 1 — modo de uso
    // ------------------------------------------------------------------

    @Test
    fun `escolher modo persiste no gateway e define o destino final`() {
        vm.chooseMode(UiMode.GAMER_LAUNCHER)

        assertEquals(listOf(UiMode.GAMER_LAUNCHER), settings.modes)
        assertEquals(UiMode.GAMER_LAUNCHER, vm.state.value.uiMode)
        assertEquals(SetupDestination.LAUNCHER, vm.state.value.destination)
    }

    @Test
    fun `modo celular abre a loja ao concluir`() {
        vm.chooseMode(UiMode.PHONE)
        assertEquals(SetupDestination.STORE, vm.state.value.destination)
    }

    @Test
    fun `nenhum modo vem pré-selecionado`() {
        assertEquals(null, vm.state.value.uiMode)
        assertFalse(vm.state.value.canProceed)
    }

    @Test
    fun `layout gamer só a partir da etapa 2`() {
        vm.chooseMode(UiMode.GAMER_LAUNCHER)
        assertFalse(vm.state.value.gamerLayout) // etapa 1 continua retrato

        vm.goToNextStep()
        assertTrue(vm.state.value.gamerLayout) // etapa 2 em diante: launcher
    }

    @Test
    fun `layout celular nunca muda`() {
        vm.chooseMode(UiMode.PHONE)
        repeat(4) {
            assertFalse(vm.state.value.gamerLayout)
            vm.goToNextStep()
        }
        assertEquals(4, vm.state.value.currentStep)
    }

    // ------------------------------------------------------------------
    // Etapa 2 — destino de instalação condiciona a etapa 3
    // ------------------------------------------------------------------

    @Test
    fun `destino DEVICE inclui instalar apps desconhecidos nas permissões`() {
        vm.chooseMode(UiMode.PHONE)
        vm.chooseInstallTarget(InstallTarget.DEVICE)
        vm.goToNextStep()
        vm.goToNextStep()

        val rows = vm.state.value.permissionRows
        assertTrue(rows.any { it.id == SetupPermissionId.UNKNOWN_SOURCES && it.required })
        assertFalse(vm.state.value.canProceed) // instalar apps ainda não concedido
    }

    @Test
    fun `destino VIRTUAL omite instalar apps e libera o proximo`() {
        vm.chooseMode(UiMode.PHONE)
        vm.chooseInstallTarget(InstallTarget.VIRTUAL_ENVIRONMENT)
        vm.goToNextStep()
        vm.goToNextStep()

        val rows = vm.state.value.permissionRows
        assertFalse(rows.any { it.id == SetupPermissionId.UNKNOWN_SOURCES })
        // Notificações pendentes, mas a escolha explícita libera:
        assertFalse(vm.state.value.canProceed)
        vm.setContinueWithoutNotifications(true)
        assertTrue(vm.state.value.canProceed)
    }

    @Test
    fun `escolher destino persiste no gateway`() {
        vm.chooseInstallTarget(InstallTarget.VIRTUAL_ENVIRONMENT)
        assertEquals(listOf(InstallTarget.VIRTUAL_ENVIRONMENT), settings.targets)
    }

    // ------------------------------------------------------------------
    // Navegação — voltar preserva escolhas, etapa persiste
    // ------------------------------------------------------------------

    @Test
    fun `voltar entre etapas não perde escolhas`() {
        vm.chooseMode(UiMode.GAMER_LAUNCHER)
        vm.chooseInstallTarget(InstallTarget.DEVICE)
        vm.goToNextStep()
        vm.goToNextStep()
        vm.goToNextStep()

        vm.goToPreviousStep()
        vm.goToPreviousStep()

        assertEquals(UiMode.GAMER_LAUNCHER, vm.state.value.uiMode)
        assertEquals(InstallTarget.DEVICE, vm.state.value.installTarget)
        assertEquals(1, vm.state.value.currentStep)
    }

    @Test
    fun `setStep persiste a etapa no gateway`() {
        vm.setStep(3)
        assertEquals(3, settings.steps.last())
    }

    @Test
    fun `setStep limita ao intervalo valido`() {
        vm.setStep(9)
        assertEquals(4, vm.state.value.currentStep)
        vm.setStep(-1)
        assertEquals(0, vm.state.value.currentStep)
    }

    @Test
    fun `estado inicial restaura etapa e escolhas persistidas`() {
        settings.settings.value = settings.settings.value.copy(
            bootReady = true,
            setupStep = 2,
            uiMode = UiMode.GAMER_LAUNCHER,
            installTarget = InstallTarget.VIRTUAL_ENVIRONMENT,
        )
        val restored = SetupViewModel(catalog = catalog, settings = settings, dataBackup = backup)

        assertEquals(2, restored.state.value.currentStep)
        assertEquals(UiMode.GAMER_LAUNCHER, restored.state.value.uiMode)
        assertEquals(InstallTarget.VIRTUAL_ENVIRONMENT, restored.state.value.installTarget)
        assertTrue(restored.state.value.gamerLayout)
    }

    // ------------------------------------------------------------------
    // Etapa 5 — conclusão
    // ------------------------------------------------------------------

    @Test
    fun `começar marca setupCompleted e carrega o catálogo`() {
        vm.chooseMode(UiMode.PHONE)
        vm.chooseInstallTarget(InstallTarget.DEVICE)
        vm.setStep(SetupFlowLogic.STEP_SUMMARY)

        vm.completeSetup()

        assertTrue(settings.setupCompleted)
        assertEquals(1, catalog.refreshCalls)
        assertTrue(vm.loadingFinished.value)
        assertFalse(vm.showLoading.value)
    }

    @Test
    fun `cancelLoading volta para a conclusão`() {
        vm.setStep(SetupFlowLogic.STEP_SUMMARY)
        vm.completeSetup()
        vm.cancelLoading()

        assertEquals(SetupFlowLogic.STEP_SUMMARY, vm.state.value.currentStep)
        assertFalse(vm.showLoading.value)
    }

    @Test
    fun `resetSessionState restaura escolhas persistidas`() {
        settings.settings.value = settings.settings.value.copy(
            bootReady = true,
            setupStep = 4,
            uiMode = UiMode.PHONE,
            installTarget = InstallTarget.DEVICE,
        )
        vm.resetSessionState()

        assertEquals(4, vm.state.value.currentStep)
        assertEquals(UiMode.PHONE, vm.state.value.uiMode)
        assertEquals(InstallTarget.DEVICE, vm.state.value.installTarget)
    }
}

// =====================================================================
// Fakes (JVM puro) das portas do ViewModel
// =====================================================================

class MainDispatcherRule(
    val dispatcher: kotlinx.coroutines.test.TestDispatcher = UnconfinedTestDispatcher(),
) : org.junit.rules.TestWatcher() {
    override fun starting(description: org.junit.runner.Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: org.junit.runner.Description) {
        Dispatchers.resetMain()
    }
}

private class FakeSettingsGateway : SetupSettingsGateway {
    override val settings = MutableStateFlow(AppSettings(bootReady = true))
    val steps = mutableListOf<Int>()
    val modes = mutableListOf<UiMode>()
    val targets = mutableListOf<InstallTarget>()
    var setupCompleted = false
    var sessionActive = false

    override suspend fun setSetupStep(step: Int) {
        steps.add(step)
        settings.value = settings.value.copy(setupStep = step)
    }

    override suspend fun setUiMode(mode: UiMode) {
        modes.add(mode)
        settings.value = settings.value.copy(
            uiMode = mode,
            gameLauncherEnabled = mode == UiMode.GAMER_LAUNCHER,
        )
    }

    override suspend fun setInstallTarget(target: InstallTarget) {
        targets.add(target)
        settings.value = settings.value.copy(
            installTarget = target,
            virtualInstallEnabled = target == InstallTarget.VIRTUAL_ENVIRONMENT,
        )
    }

    override suspend fun setSetupCompleted(value: Boolean) {
        setupCompleted = value
        settings.value = settings.value.copy(setupCompleted = value)
    }

    override suspend fun setContinueWithoutNotifications(value: Boolean) {
        settings.value = settings.value.copy(continueWithoutNotifications = value)
    }

    override fun beginSetupSession() {
        sessionActive = true
    }

    override fun finishSetupSession() {
        sessionActive = false
    }
}

private class FakeCatalogGateway : SetupCatalogGateway {
    val reposFlow = MutableStateFlow(
        listOf(
            DataRepoEntity(
                id = "port-db",
                name = "Port DB",
                url = "https://example.invalid/games.json",
                baseUrl = "https://example.invalid/",
                siteBase = "",
                enabled = true,
                sortIndex = 0,
                lastUpdated = 0L,
                etag = null,
                gameCount = 0,
                status = "pendente",
                lastError = null,
            ),
        ),
    )
    var refreshCalls = 0

    override val loadProgress: StateFlow<LoadProgress?> = MutableStateFlow(null)

    override fun observeRepos(): Flow<List<DataRepoEntity>> = reposFlow

    override suspend fun verifyRepo(input: String): Result<RepoVerification> =
        Result.success(RepoVerification(gameCount = 3, suggestedName = "X", catalogUrl = input, baseUrl = ""))

    override suspend fun addRepo(name: String, input: String): Result<DataRepoEntity> =
        Result.success(reposFlow.value.first())

    override suspend fun updateRepo(id: String, name: String, input: String): Result<DataRepoEntity> =
        Result.success(reposFlow.value.first())

    override suspend fun setRepoEnabled(id: String, enabled: Boolean) = Unit

    override suspend fun removeRepo(id: String) = Unit

    override suspend fun reorderRepo(id: String, up: Boolean) = Unit

    override suspend fun refreshAll(force: Boolean): RefreshResult {
        refreshCalls++
        return RefreshResult(okRepos = 1, failedRepos = 0, totalGames = 10, hasCache = true)
    }

    override suspend fun loadFromCache(): Catalog? = null

    override suspend fun markImagesPhase() = Unit

    override suspend fun markDone() = Unit

    override fun clearProgress() = Unit
}

private class FakeDataBackup : ComplementaryDataBackupOps {
    var exportCalls = 0
    var importMode: ComplementaryImportMode? = null

    override suspend fun exportTo(uri: android.net.Uri): Result<Int> {
        exportCalls++
        return Result.success(3)
    }

    override suspend fun readSummary(uri: android.net.Uri): Result<ComplementarySummary> =
        Result.success(ComplementarySummary(version = 1, itemCount = 3, exportedAt = 1L, repoNames = listOf("A")))

    override suspend fun importFrom(uri: android.net.Uri, mode: ComplementaryImportMode): Result<Int> {
        importMode = mode
        return Result.success(3)
    }
}
