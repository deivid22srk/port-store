package com.deivid22srk.portstore.setup

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deivid22srk.portstore.R
import com.deivid22srk.portstore.launcher.input.LauncherInitialFocus
import com.deivid22srk.portstore.launcher.input.launcherFocusable
import com.deivid22srk.portstore.ui.theme.Lime
import com.deivid22srk.portstore.ui.theme.TextSecondary

// =====================================================================
// Componentes compartilhados entre as etapas (celular e gamer).
// =====================================================================

/**
 * Card grande selecionável (uma escolha por vez). No layout gamer é um item
 * focável com borda/brilho amarelo-limão no foco (A confirma); no layout
 * celular é um card com toque. Área de toque >= 48dp.
 */
@Composable
fun SetupSelectableCard(
    title: String,
    description: String,
    icon: ImageVector,
    selected: Boolean,
    gamer: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val selectionBorder = if (selected) {
        Modifier.border(2.dp, Lime, shape)
    } else {
        Modifier
    }

    val focusModifier = when {
        gamer && focusRequester != null -> Modifier.focusRequester(focusRequester)
        else -> Modifier
    }

    Surface(
        shape = shape,
        color = containerColor,
        modifier = modifier
            .fillMaxWidth()
            .then(focusModifier)
            .let { m ->
                if (gamer) {
                    m.launcherFocusable(
                        shape = shape,
                        scaleFocused = 1.03f,
                        onClick = onClick,
                    )
                } else {
                    m.clickable(onClickLabel = title) { onClick() }
                }
            }
            .then(selectionBorder),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (selected) Lime else TextSecondary,
                    modifier = Modifier
                        .padding(10.dp)
                        .size(28.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            if (selected) {
                Spacer(Modifier.width(10.dp))
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = stringResource(R.string.setup_selected),
                    tint = Lime,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/** Nota de apoio em texto menor (ex.: "pode mudar depois"). */
@Composable
fun SetupNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = TextSecondary,
        modifier = modifier.padding(horizontal = 4.dp),
    )
}

/** Título de seção dentro de uma etapa. */
@Composable
fun SetupSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = Lime,
        modifier = modifier,
    )
}

// =====================================================================
// Etapa 1 — Modo de uso
// =====================================================================

/** Conteúdo da etapa "Como você quer usar o Port Store?". */
@Composable
fun ModeStepContent(
    state: SetupUiState,
    gamer: Boolean,
    onChoose: (UiMode) -> Unit,
) {
    val firstCardRequester = remember { FocusRequester() }
    if (gamer) {
        LauncherInitialFocus(firstCardRequester, key = state.currentStep)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (gamer) 32.dp else 24.dp),
    ) {
        Text(
            stringResource(R.string.setup_mode_title),
            style = if (gamer) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.setup_mode_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        Spacer(Modifier.height(if (gamer) 18.dp else 20.dp))

        SetupSelectableCard(
            title = stringResource(R.string.setup_mode_phone),
            description = stringResource(R.string.setup_mode_phone_desc),
            icon = Icons.Rounded.Smartphone,
            selected = state.uiMode == UiMode.PHONE,
            gamer = gamer,
            onClick = { onChoose(UiMode.PHONE) },
            focusRequester = firstCardRequester,
        )
        Spacer(Modifier.height(12.dp))
        SetupSelectableCard(
            title = stringResource(R.string.setup_mode_gamer),
            description = stringResource(R.string.setup_mode_gamer_desc),
            icon = Icons.Rounded.SportsEsports,
            selected = state.uiMode == UiMode.GAMER_LAUNCHER,
            gamer = gamer,
            onClick = { onChoose(UiMode.GAMER_LAUNCHER) },
        )
        Spacer(Modifier.height(14.dp))
        SetupNote(stringResource(R.string.setup_changeable_later))
        Spacer(Modifier.height(24.dp))
    }
}

// =====================================================================
// Etapa 2 — Onde instalar os jogos
// =====================================================================

/** Conteúdo da etapa "Onde você quer instalar os jogos?". */
@Composable
fun InstallTargetStepContent(
    state: SetupUiState,
    gamer: Boolean,
    onChoose: (InstallTarget) -> Unit,
) {
    val firstCardRequester = remember { FocusRequester() }
    if (gamer) {
        LauncherInitialFocus(firstCardRequester, key = state.currentStep)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (gamer) 32.dp else 24.dp),
    ) {
        Text(
            stringResource(R.string.setup_target_title),
            style = if (gamer) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.setup_target_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        Spacer(Modifier.height(if (gamer) 18.dp else 20.dp))

        SetupSelectableCard(
            title = stringResource(R.string.setup_target_virtual),
            description = stringResource(R.string.setup_target_virtual_desc),
            icon = Icons.Rounded.VerifiedUser,
            selected = state.installTarget == InstallTarget.VIRTUAL_ENVIRONMENT,
            gamer = gamer,
            onClick = { onChoose(InstallTarget.VIRTUAL_ENVIRONMENT) },
            focusRequester = firstCardRequester,
        )
        Spacer(Modifier.height(12.dp))
        SetupSelectableCard(
            title = stringResource(R.string.setup_target_device),
            description = stringResource(R.string.setup_target_device_desc),
            icon = Icons.Rounded.InstallMobile,
            selected = state.installTarget == InstallTarget.DEVICE,
            gamer = gamer,
            onClick = { onChoose(InstallTarget.DEVICE) },
        )
        Spacer(Modifier.height(14.dp))
        SetupNote(stringResource(R.string.setup_changeable_later))
        Spacer(Modifier.height(24.dp))
    }
}

// =====================================================================
// Etapa 3 — Permissões (condicionais ao destino escolhido)
// =====================================================================

/** Conteúdo da etapa de permissões. */
@Composable
fun PermissionsStepContent(
    state: SetupUiState,
    gamer: Boolean,
    onAllowNotifications: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenUnknownSources: () -> Unit,
    onOpenBattery: () -> Unit,
    onContinueWithoutNotifications: (Boolean) -> Unit,
) {
    val firstCardRequester = remember { FocusRequester() }
    if (gamer) {
        LauncherInitialFocus(firstCardRequester, key = state.currentStep)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (gamer) 32.dp else 24.dp),
    ) {
        Text(
            stringResource(R.string.setup_permissions_title),
            style = if (gamer) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.setup_permissions_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        Spacer(Modifier.height(if (gamer) 16.dp else 20.dp))

        state.permissionRows.forEachIndexed { index, spec ->
            val requester = if (index == 0) firstCardRequester else null
            when (spec.id) {
                SetupPermissionId.NOTIFICATIONS -> PermissionSetupCard(
                    icon = Icons.Rounded.Notifications,
                    title = stringResource(R.string.setup_perm_notifications),
                    description = stringResource(R.string.setup_perm_notifications_desc),
                    badge = stringResource(R.string.setup_required),
                    granted = state.notificationsGranted,
                    permanentlyDenied = state.notificationsPermanentlyDenied,
                    gamer = gamer,
                    onAllow = onAllowNotifications,
                    onOpenSettings = onOpenNotificationSettings,
                    focusRequester = requester,
                )

                SetupPermissionId.UNKNOWN_SOURCES -> PermissionSetupCard(
                    icon = Icons.Rounded.InstallMobile,
                    title = stringResource(R.string.setup_perm_unknown_sources),
                    description = stringResource(R.string.setup_perm_unknown_sources_desc),
                    badge = stringResource(R.string.setup_required),
                    granted = state.installAllowed,
                    permanentlyDenied = false,
                    gamer = gamer,
                    onAllow = onOpenUnknownSources,
                    onOpenSettings = onOpenUnknownSources,
                    focusRequester = requester,
                )

                SetupPermissionId.BATTERY -> PermissionSetupCard(
                    icon = Icons.Rounded.BatteryFull,
                    title = stringResource(R.string.setup_perm_battery),
                    description = stringResource(R.string.setup_perm_battery_desc),
                    badge = stringResource(R.string.setup_recommended),
                    granted = state.batteryIgnored,
                    permanentlyDenied = false,
                    gamer = gamer,
                    onAllow = onOpenBattery,
                    onOpenSettings = onOpenBattery,
                    focusRequester = requester,
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        // Notificações: opção explícita de seguir sem conceder.
        if (!state.notificationsGranted) {
            ContinueWithoutNotificationsRow(
                state = state,
                gamer = gamer,
                onToggle = onContinueWithoutNotifications,
            )
            Spacer(Modifier.height(8.dp))
        }

        // Ambiente virtual: a permissão de instalar apps desconhecidos não é
        // necessária — explicar por que ela não aparece.
        if (state.installTarget == InstallTarget.VIRTUAL_ENVIRONMENT) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.setup_virtual_no_unknown_sources),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ContinueWithoutNotificationsRow(
    state: SetupUiState,
    gamer: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    if (gamer) {
        val shape = RoundedCornerShape(12.dp)
        Surface(
            shape = shape,
            color = if (state.continueWithoutNotifications) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
            modifier = Modifier
                .fillMaxWidth()
                .launcherFocusable(
                    shape = shape,
                    scaleFocused = 1.02f,
                    onClick = { onToggle(!state.continueWithoutNotifications) },
                ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Icon(
                    if (state.continueWithoutNotifications) Icons.Rounded.Check else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (state.continueWithoutNotifications) Lime else TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.setup_continue_without_notifications),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    } else {
        TextButton(onClick = { onToggle(!state.continueWithoutNotifications) }) {
            Text(
                stringResource(
                    if (state.continueWithoutNotifications) {
                        R.string.setup_continue_without_notifications_done
                    } else {
                        R.string.setup_continue_without_notifications
                    },
                ),
            )
        }
        if (state.continueWithoutNotifications) {
            Text(
                stringResource(R.string.setup_continue_without_notifications_hint),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

/** Card de permissão com status em tempo real (Permitir → ✓ Concedida). */
@Composable
private fun PermissionSetupCard(
    icon: ImageVector,
    title: String,
    description: String,
    badge: String,
    granted: Boolean,
    permanentlyDenied: Boolean,
    gamer: Boolean,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
    focusRequester: FocusRequester?,
) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .then(
                    // O FocusRequester precisa estar no MESMO nó que é focável
                    // (o Row do layout gamer), senão o foco inicial falha.
                    if (gamer && focusRequester != null) {
                        Modifier.focusRequester(focusRequester)
                    } else {
                        Modifier
                    },
                )
                .let { m ->
                    if (gamer) {
                        m.launcherFocusable(
                            shape = shape,
                            scaleFocused = 1.02f,
                            onClick = {
                                if (!granted) {
                                    if (permanentlyDenied) onOpenSettings() else onAllow()
                                }
                            },
                        )
                    } else {
                        m
                    }
                }
                .padding(14.dp),
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
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(badge, style = MaterialTheme.typography.labelSmall, color = Lime)
                }
                Spacer(Modifier.height(2.dp))
                Text(description, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            Spacer(Modifier.width(8.dp))
            PermissionAction(
                granted = granted,
                permanentlyDenied = permanentlyDenied,
                gamer = gamer,
                onAllow = onAllow,
                onOpenSettings = onOpenSettings,
            )
        }
    }
}

@Composable
private fun PermissionAction(
    granted: Boolean,
    permanentlyDenied: Boolean,
    gamer: Boolean,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    when {
        granted -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = Lime, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.setup_perm_granted),
                style = MaterialTheme.typography.labelMedium,
                color = Lime,
            )
        }

        permanentlyDenied -> {
            if (gamer) {
                GamerPillButton(
                    label = stringResource(R.string.setup_open_settings),
                    filled = false,
                    enabled = true,
                    onClick = onOpenSettings,
                )
            } else {
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.setup_open_settings))
                }
            }
        }

        gamer -> GamerPillButton(
            label = stringResource(R.string.setup_perm_allow),
            filled = true,
            enabled = true,
            onClick = onAllow,
        )

        else -> Button(
            onClick = onAllow,
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Color(0xFF171800)),
        ) {
            Text(stringResource(R.string.setup_perm_allow))
        }
    }
}
