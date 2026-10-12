package com.deivid22srk.portstore.setup

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Teste de UI do fluxo principal do setup (etapas 1 e 2, layout celular):
 * os composables das etapas são puros (estado + callbacks), então o teste
 * não depende do AppGraph nem do DataStore.
 */
@RunWith(AndroidJUnit4::class)
class SetupFlowUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun etapa1_semPreSelecao_eSelecionaModoCelular() {
        var chosen: UiMode? = null
        composeRule.setContent {
            ModeStepContent(state = SetupUiState(), gamer = false, onChoose = { chosen = it })
        }

        // Nenhuma opção vem pré-selecionada: cada card aparece uma única vez.
        composeRule.onAllNodesWithText("Modo Celular").assertCountEquals(1)
        composeRule.onAllNodesWithText("Modo Gamer Launcher").assertCountEquals(1)
        assertEquals(null, chosen)

        composeRule.onNodeWithText("Modo Celular").performClick()
        assertEquals(UiMode.PHONE, chosen)
        composeRule.onNodeWithContentDescription("Selecionado").assertIsDisplayed()
    }

    @Test
    fun etapa1_selecionaModoGamerLauncher() {
        var chosen: UiMode? = null
        composeRule.setContent {
            ModeStepContent(state = SetupUiState(), gamer = false, onChoose = { chosen = it })
        }

        composeRule.onNodeWithText("Modo Gamer Launcher").performClick()
        assertEquals(UiMode.GAMER_LAUNCHER, chosen)
    }

    @Test
    fun etapa2_selecionaDestinoAmbienteVirtual() {
        var chosen: InstallTarget? = null
        composeRule.setContent {
            InstallTargetStepContent(
                state = SetupUiState(),
                gamer = false,
                onChoose = { chosen = it },
            )
        }

        composeRule.onNodeWithText("Ambiente virtual").performClick()
        assertEquals(InstallTarget.VIRTUAL_ENVIRONMENT, chosen)
    }

    @Test
    fun etapa3_destinoVirtual_explicaQuePermissaoNaoENecessaria() {
        composeRule.setContent {
            PermissionsStepContent(
                state = SetupUiState(installTarget = InstallTarget.VIRTUAL_ENVIRONMENT),
                gamer = false,
                onAllowNotifications = {},
                onOpenNotificationSettings = {},
                onOpenUnknownSources = {},
                onOpenBattery = {},
                onContinueWithoutNotifications = {},
            )
        }

        // Sem o destino DEVICE, o card "Instalar apps desconhecidos" não aparece;
        // a explicação de que ele não é necessário aparece.
        composeRule.onAllNodesWithText("Instalar apps desconhecidos").assertCountEquals(0)
        composeRule.onNodeWithText("Como você escolheu o ambiente virtual, a permissão de instalar apps desconhecidos não é necessária: os jogos rodam isolados dentro do Port Store.").assertIsDisplayed()
    }

    @Test
    fun etapa3_destinoDevice_mostraPermissaoObrigatoria() {
        composeRule.setContent {
            PermissionsStepContent(
                state = SetupUiState(installTarget = InstallTarget.DEVICE),
                gamer = false,
                onAllowNotifications = {},
                onOpenNotificationSettings = {},
                onOpenUnknownSources = {},
                onOpenBattery = {},
                onContinueWithoutNotifications = {},
            )
        }

        composeRule.onAllNodesWithText("Instalar apps desconhecidos").assertCountEquals(1)
        composeRule.onAllNodesWithText("Obrigatória").assertCountEquals(2)
    }
}
