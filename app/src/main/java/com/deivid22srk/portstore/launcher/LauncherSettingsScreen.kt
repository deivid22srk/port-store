package com.deivid22srk.portstore.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deivid22srk.portstore.AppGraph
import com.deivid22srk.portstore.BuildConfig
import com.deivid22srk.portstore.R
import com.deivid22srk.portstore.launcher.input.LauncherInitialFocus
import kotlinx.coroutines.launch

/**
 * Configurações do Game Launcher: desativar o modo (voltando à interface
 * normal do Port Store), vibração e som de navegação. Tudo navegável por
 * gamepad (A alterna o switch da linha focada) e toque.
 */
@Composable
fun LauncherSettingsScreen(
    viewModel: LauncherViewModel,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val backRequester = remember { FocusRequester() }
    var confirmDevMode by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(20.dp)) {
            LauncherIconButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.launcher_hint_details),
                onClick = onBack,
                focusRequester = backRequester,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.launcher_menu_settings),
                style = MaterialTheme.typography.headlineSmall,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            LauncherSwitchRow(
                title = stringResource(R.string.launcher_mode_title),
                subtitle = stringResource(R.string.launcher_menu_exit),
                checked = settings.gameLauncherEnabled,
                onToggle = {
                    // Desligar aqui volta imediatamente à interface normal
                    // (o MainActivity recompõe o AppRoot). Downloads, jogos e
                    // preferências permanecem intactos.
                    scope.launch { AppGraph.settings.setGameLauncherEnabled(false) }
                },
            )
            LauncherSwitchRow(
                title = stringResource(R.string.launcher_haptics),
                subtitle = stringResource(R.string.launcher_haptics_subtitle),
                checked = settings.launcherHaptics,
                onToggle = {
                    scope.launch { AppGraph.settings.setLauncherHaptics(!settings.launcherHaptics) }
                },
            )
            LauncherSwitchRow(
                title = stringResource(R.string.launcher_sounds),
                subtitle = stringResource(R.string.launcher_sounds_subtitle),
                checked = settings.launcherSounds,
                onToggle = {
                    scope.launch { AppGraph.settings.setLauncherSounds(!settings.launcherSounds) }
                },
            )
            LauncherSwitchRow(
                title = stringResource(R.string.settings_quick_menu_title),
                subtitle = stringResource(R.string.settings_quick_menu_subtitle),
                checked = settings.quickMenuGestureEnabled,
                onToggle = {
                    scope.launch {
                        AppGraph.settings.setQuickMenuGestureEnabled(!settings.quickMenuGestureEnabled)
                    }
                },
            )
            LauncherSwitchRow(
                title = stringResource(R.string.settings_launch_feedback_title),
                subtitle = stringResource(R.string.settings_launch_feedback_subtitle),
                checked = settings.launchFeedbackEnabled,
                onToggle = {
                    scope.launch {
                        AppGraph.settings.setLaunchFeedbackEnabled(!settings.launchFeedbackEnabled)
                    }
                },
            )
            LauncherSwitchRow(
                title = stringResource(R.string.dev_mode_title),
                subtitle = stringResource(R.string.dev_mode_subtitle),
                checked = settings.developerMode,
                onToggle = {
                    if (settings.developerMode) {
                        scope.launch { AppGraph.settings.setDeveloperMode(false) }
                    } else {
                        confirmDevMode = true
                    }
                },
            )
            Text(
                stringResource(R.string.launcher_version_subtitle, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        }

        Text(
            stringResource(R.string.launcher_hint_touch),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(20.dp),
        )
    }

    // Confirmação ao ATIVAR o Modo Desenvolvedor (aviso de recursos avançados).
    if (confirmDevMode) {
        LauncherConfirmDialog(
            title = stringResource(R.string.dev_mode_confirm_title),
            message = stringResource(R.string.dev_mode_confirm_message),
            confirmLabel = stringResource(R.string.dev_mode_confirm_ok),
            destructive = false,
            onConfirm = {
                confirmDevMode = false
                scope.launch { AppGraph.settings.setDeveloperMode(true) }
            },
            onDismiss = { confirmDevMode = false },
        )
    }

    LauncherInitialFocus(backRequester)
}
