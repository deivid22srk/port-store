package com.deivid22srk.portstore.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes da lógica pura do fluxo condicional do setup (modo de uso × destino
 * de instalação): exatamente o comportamento que o SetupViewModel expõe.
 */
class SetupFlowLogicTest {

    // ------------------------------------------------------------------
    // Layout adaptativo (gamer a partir da etapa 2)
    // ------------------------------------------------------------------

    @Test
    fun `modo celular nunca usa layout gamer`() {
        for (step in 0..4) {
            assertEquals(false, SetupFlowLogic.isGamerLayout(UiMode.PHONE, step))
        }
    }

    @Test
    fun `modo gamer usa layout gamer a partir da etapa 2`() {
        assertEquals(false, SetupFlowLogic.isGamerLayout(UiMode.GAMER_LAUNCHER, 0))
        for (step in 1..4) {
            assertEquals(true, SetupFlowLogic.isGamerLayout(UiMode.GAMER_LAUNCHER, step))
        }
    }

    @Test
    fun `modo nao escolhido nunca usa layout gamer`() {
        for (step in 0..4) {
            assertEquals(false, SetupFlowLogic.isGamerLayout(null, step))
        }
    }

    // ------------------------------------------------------------------
    // Permissões visíveis por destino
    // ------------------------------------------------------------------

    @Test
    fun `destino DEVICE exige instalar apps desconhecidos`() {
        val rows = SetupFlowLogic.visiblePermissions(InstallTarget.DEVICE)
        assertEquals(
            listOf(SetupPermissionId.NOTIFICATIONS, SetupPermissionId.UNKNOWN_SOURCES, SetupPermissionId.BATTERY),
            rows.map { it.id },
        )
        assertTrue(rows.first { it.id == SetupPermissionId.UNKNOWN_SOURCES }.required)
    }

    @Test
    fun `destino VIRTUAL oculta instalar apps desconhecidos`() {
        val rows = SetupFlowLogic.visiblePermissions(InstallTarget.VIRTUAL_ENVIRONMENT)
        assertEquals(
            listOf(SetupPermissionId.NOTIFICATIONS, SetupPermissionId.BATTERY),
            rows.map { it.id },
        )
    }

    @Test
    fun `destino nao escolhido mostra apenas notificacoes e bateria`() {
        val rows = SetupFlowLogic.visiblePermissions(null)
        assertEquals(
            listOf(SetupPermissionId.NOTIFICATIONS, SetupPermissionId.BATTERY),
            rows.map { it.id },
        )
    }

    // ------------------------------------------------------------------
    // Saída da etapa de permissões
    // ------------------------------------------------------------------

    @Test
    fun `DEVICE sem instalar apps bloqueia o proximo mesmo com notificacoes`() {
        assertEquals(
            false,
            SetupFlowLogic.canLeavePermissions(
                notificationsGranted = true,
                continueWithoutNotifications = false,
                installAllowed = false,
                installTarget = InstallTarget.DEVICE,
            ),
        )
    }

    @Test
    fun `DEVICE com instalar apps e notificacoes libera o proximo`() {
        assertEquals(
            true,
            SetupFlowLogic.canLeavePermissions(
                notificationsGranted = true,
                continueWithoutNotifications = false,
                installAllowed = true,
                installTarget = InstallTarget.DEVICE,
            ),
        )
    }

    @Test
    fun `continuar sem notificacoes substitui a permissao`() {
        assertEquals(
            true,
            SetupFlowLogic.canLeavePermissions(
                notificationsGranted = false,
                continueWithoutNotifications = true,
                installAllowed = true,
                installTarget = InstallTarget.DEVICE,
            ),
        )
        assertEquals(
            false,
            SetupFlowLogic.canLeavePermissions(
                notificationsGranted = false,
                continueWithoutNotifications = false,
                installAllowed = true,
                installTarget = InstallTarget.DEVICE,
            ),
        )
    }

    @Test
    fun `VIRTUAL nao exige instalar apps desconhecidos`() {
        assertEquals(
            true,
            SetupFlowLogic.canLeavePermissions(
                notificationsGranted = true,
                continueWithoutNotifications = false,
                installAllowed = false,
                installTarget = InstallTarget.VIRTUAL_ENVIRONMENT,
            ),
        )
    }

    // ------------------------------------------------------------------
    // canProceed por etapa
    // ------------------------------------------------------------------

    @Test
    fun `etapa 1 bloqueia sem escolher modo`() {
        val state = SetupUiState(currentStep = 0, uiMode = null)
        assertEquals(false, state.canProceed)
        assertEquals(true, state.copy(uiMode = UiMode.PHONE).canProceed)
        assertEquals(true, state.copy(uiMode = UiMode.GAMER_LAUNCHER).canProceed)
    }

    @Test
    fun `etapa 2 bloqueia sem escolher destino`() {
        val state = SetupUiState(currentStep = 1, uiMode = UiMode.PHONE, installTarget = null)
        assertEquals(false, state.canProceed)
        assertEquals(true, state.copy(installTarget = InstallTarget.DEVICE).canProceed)
    }

    @Test
    fun `etapa 3 bloqueia enquanto obrigatórias pendentes`() {
        val base = SetupUiState(
            currentStep = 2,
            uiMode = UiMode.PHONE,
            installTarget = InstallTarget.DEVICE,
        )
        assertEquals(false, base.canProceed)
        assertEquals(true, base.copy(installAllowed = true).canProceed)
        assertEquals(true, base.copy(continueWithoutNotifications = true, installAllowed = true).canProceed)
    }

    @Test
    fun `etapas 4 e 5 nunca bloqueiam`() {
        val state = SetupUiState(currentStep = 3, uiMode = UiMode.PHONE, installTarget = InstallTarget.DEVICE)
        assertEquals(true, state.canProceed)
        assertEquals(true, state.copy(currentStep = 4).canProceed)
    }

    // ------------------------------------------------------------------
    // Destino do "Começar"
    // ------------------------------------------------------------------

    @Test
    fun `modo celular abre a loja e gamer abre o launcher`() {
        assertEquals(SetupDestination.STORE, SetupFlowLogic.destination(UiMode.PHONE))
        assertEquals(SetupDestination.LAUNCHER, SetupFlowLogic.destination(UiMode.GAMER_LAUNCHER))
    }

    @Test
    fun `estado sem modo nao tem destino`() {
        assertEquals(null, SetupUiState().destination)
        assertEquals(SetupDestination.LAUNCHER, SetupUiState(uiMode = UiMode.GAMER_LAUNCHER).destination)
    }

    @Test
    fun `total de etapas e cinco`() {
        assertEquals(5, SetupFlowLogic.TOTAL_STEPS)
    }
}
