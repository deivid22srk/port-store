package com.deivid22srk.portstore.launcher

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.deivid22srk.portstore.R
import com.deivid22srk.portstore.launcher.input.LocalGamepadMonitor
import com.deivid22srk.portstore.launcher.input.launcherFocusable
import com.deivid22srk.portstore.launcher.input.launcherGamepadNav
import com.deivid22srk.portstore.ui.theme.Lime
import com.deivid22srk.portstore.ui.theme.TextPrimary
import com.deivid22srk.portstore.ui.theme.TextSecondary
import kotlinx.coroutines.delay

/** Item de menu do Launcher (Abrir / Informações / Desinstalar etc.). */
data class LauncherMenuItem(
    val icon: ImageVector,
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Menu do Launcher (opções do jogo, escolha de variante etc.), navegável por
 * gamepad (D-pad move, A seleciona, B fecha) e por toque (toque seleciona,
 * toque fora fecha). Usando [Dialog] com janela própria, o foco fica contido:
 * nunca se perde para o conteúdo atrás do menu.
 */
@Composable
fun LauncherMenuDialog(
    title: String,
    items: List<LauncherMenuItem>,
    onDismiss: () -> Unit,
) {
    val monitor = LocalGamepadMonitor.current
    val firstRequester = remember { FocusRequester() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 72.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 8.dp,
                modifier = Modifier.widthIn(max = 420.dp),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .let { m -> if (monitor != null) m.launcherGamepadNav(monitor, onBack = onDismiss) else m }
                        .padding(vertical = 10.dp),
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                    items.forEachIndexed { index, item ->
                        val color = if (item.destructive) {
                            MaterialTheme.colorScheme.error
                        } else {
                            TextPrimary
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .let { m -> if (index == 0) m.focusRequester(firstRequester) else m }
                                .launcherFocusable(
                                    shape = RoundedCornerShape(14.dp),
                                    scaleFocused = 1.02f,
                                    focusedBorderWidth = 2.dp,
                                    onClick = item.onClick,
                                )
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                        ) {
                            androidx.compose.material3.Icon(
                                item.icon,
                                contentDescription = null,
                                tint = color,
                                modifier = Modifier.width(24.dp),
                            )
                            Spacer(Modifier.width(14.dp))
                            Text(item.label, style = MaterialTheme.typography.bodyLarge, color = color)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.launcher_hint_menu),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp, bottom = 4.dp),
                    )
                }
            }
        }
        LaunchedEffect(Unit) {
            delay(120)
            runCatching { firstRequester.requestFocus() }
        }
    }
}

/**
 * Diálogo de confirmação genérico do Launcher (sair do modo, desinstalar
 * jogo). Gamepad: D-pad entre os botões, A confirma, B volta. Toque: botões.
 */
@Composable
fun LauncherConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
) {
    val monitor = LocalGamepadMonitor.current
    val confirmRequester = remember { FocusRequester() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 72.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 8.dp,
                modifier = Modifier.widthIn(max = 460.dp),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .let { m -> if (monitor != null) m.launcherGamepadNav(monitor, onBack = onDismiss) else m }
                        .padding(24.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(20.dp))
                    Row {
                        val confirmContainer = if (destructive) {
                            MaterialTheme.colorScheme.error
                        } else {
                            Lime
                        }
                        val confirmContent = if (destructive) {
                            Color.White
                        } else {
                            Color(0xFF171800)
                        }
                        LauncherButton(
                            label = confirmLabel,
                            onClick = onConfirm,
                            container = confirmContainer,
                            contentColor = confirmContent,
                            modifier = Modifier.focusRequester(confirmRequester),
                        )
                        Spacer(Modifier.width(12.dp))
                        LauncherButton(
                            label = stringResource(R.string.cancel),
                            onClick = onDismiss,
                        )
                    }
                }
            }
        }
        LaunchedEffect(Unit) {
            delay(120)
            runCatching { confirmRequester.requestFocus() }
        }
    }
}
