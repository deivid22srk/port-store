package com.deivid22srk.portstore.setup

/**
 * Modo de uso escolhido na etapa 1 do setup.
 *
 * - [PHONE]: interface tradicional (retrato, abas, listas/cards).
 * - [GAMER_LAUNCHER]: interface estilo console (paisagem, foco navegável por
 *   controle, barras superiores/inferiores do launcher).
 *
 * A escolha é persistida no DataStore e espelhada na flag `game_launcher_enabled`
 * (ver [com.deivid22srk.portstore.settings.SettingsRepository.setUiMode]), que é
 * o que o MainActivity usa para decidir entre AppRoot e LauncherRoot.
 */
enum class UiMode {
    PHONE,
    GAMER_LAUNCHER,
}

/**
 * Destino de instalação dos jogos escolhido na etapa 2.
 *
 * - [VIRTUAL_ENVIRONMENT]: jogos rodam isolados dentro do Port Store (motor
 *   virtual), sem aparecer na gaveta de apps do Android.
 * - [DEVICE]: instalação direta de APK no Android, como qualquer app — exige a
 *   permissão "Instalar apps desconhecidos" (etapa 3 fica condicional a isso).
 *
 * Espelhada na flag `virtual_install_enabled` (ver
 * [com.deivid22srk.portstore.settings.SettingsRepository.setInstallTarget]).
 */
enum class InstallTarget {
    VIRTUAL_ENVIRONMENT,
    DEVICE,
}

/** Identificadores das permissões mostradas na etapa 3. */
enum class SetupPermissionId {
    NOTIFICATIONS,
    UNKNOWN_SOURCES,
    BATTERY,
}

/** Destino após "Começar" na etapa 5. */
enum class SetupDestination { STORE, LAUNCHER }

/** Descrição declarativa de uma linha de permissão da etapa 3. */
data class PermissionRowSpec(
    val id: SetupPermissionId,
    val required: Boolean,
    val recommended: Boolean,
)

/**
 * Lógica pura do fluxo condicional do setup (sem dependência de Android):
 * usada pelo [SetupViewModel] e testável em JVM puro.
 */
object SetupFlowLogic {

    /** Total de etapas exibido no indicador ("Etapa X de N"). */
    const val TOTAL_STEPS = 5

    /** Índices das etapas (0-based). */
    const val STEP_MODE = 0
    const val STEP_INSTALL_TARGET = 1
    const val STEP_PERMISSIONS = 2
    const val STEP_REPOSITORIES = 3
    const val STEP_SUMMARY = 4

    /**
     * Verdadeiro quando o layout do launcher (paisagem, foco por controle)
     * deve ser usado. Regra: modo Gamer Launcher a partir da etapa 2 — a etapa
     * 1 (escolha do modo) é sempre retrato, para não presumir o modo antes da
     * escolha.
     */
    fun isGamerLayout(uiMode: UiMode?, step: Int): Boolean =
        uiMode == UiMode.GAMER_LAUNCHER && step >= STEP_INSTALL_TARGET

    /**
     * Permissões visíveis na etapa 3, conforme o destino de instalação:
     * - Notificações: sempre (obrigatória, com opção de continuar sem).
     * - Instalar apps desconhecidos: somente destino DEVICE (obrigatória);
     *   no ambiente virtual ela é desnecessária e não é exibida.
     * - Ignorar otimização de bateria: sempre (recomendada).
     * - Armazenamento: a etapa 4 usa SAF (ACTION_OPEN_DOCUMENT / CREATE_DOCUMENT),
     *   que não exige permissão em tempo de execução — não aparece como card.
     */
    fun visiblePermissions(installTarget: InstallTarget?): List<PermissionRowSpec> = buildList {
        add(PermissionRowSpec(SetupPermissionId.NOTIFICATIONS, required = true, recommended = false))
        if (installTarget == InstallTarget.DEVICE) {
            add(PermissionRowSpec(SetupPermissionId.UNKNOWN_SOURCES, required = true, recommended = false))
        }
        add(PermissionRowSpec(SetupPermissionId.BATTERY, required = false, recommended = true))
    }

    /** Regras da etapa de permissões (todos os requisitos obrigatórios resolvidos). */
    fun canLeavePermissions(
        notificationsGranted: Boolean,
        continueWithoutNotifications: Boolean,
        installAllowed: Boolean,
        installTarget: InstallTarget?,
    ): Boolean {
        val notificationsOk = notificationsGranted || continueWithoutNotifications
        val unknownSourcesOk = installTarget != InstallTarget.DEVICE || installAllowed
        return notificationsOk && unknownSourcesOk
    }

    /**
     * O botão principal do rodapé pode avançar? Etapas 4 (repositórios) e 5
     * (conclusão) são não bloqueantes — os padrões já funcionam.
     */
    fun canProceed(state: SetupUiState): Boolean = when (state.currentStep) {
        STEP_MODE -> state.uiMode != null
        STEP_INSTALL_TARGET -> state.installTarget != null
        STEP_PERMISSIONS -> canLeavePermissions(
            notificationsGranted = state.notificationsGranted,
            continueWithoutNotifications = state.continueWithoutNotifications,
            installAllowed = state.installAllowed,
            installTarget = state.installTarget,
        )
        else -> true
    }

    /** Para onde o botão "Começar" leva, conforme o modo escolhido. */
    fun destination(uiMode: UiMode?): SetupDestination =
        if (uiMode == UiMode.GAMER_LAUNCHER) SetupDestination.LAUNCHER else SetupDestination.STORE
}
