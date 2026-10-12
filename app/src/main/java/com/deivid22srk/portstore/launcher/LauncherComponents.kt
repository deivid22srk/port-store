package com.deivid22srk.portstore.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deivid22srk.portstore.AppGraph
import com.deivid22srk.portstore.R
import com.deivid22srk.portstore.catalog.Game
import com.deivid22srk.portstore.core.DownloadItem
import com.deivid22srk.portstore.gamedata.GAMEDATA_JOB_PREFIX
import com.deivid22srk.portstore.install.InstallState
import com.deivid22srk.portstore.launcher.input.launcherFocusable
import com.deivid22srk.portstore.ui.components.DownloadProgressBar
import com.deivid22srk.portstore.ui.components.GameImage
import com.deivid22srk.portstore.ui.components.Pill
import com.deivid22srk.portstore.ui.components.bottomScrim
import com.deivid22srk.portstore.ui.theme.Lime
import com.deivid22srk.portstore.ui.theme.TextPrimary
import com.deivid22srk.portstore.ui.theme.TextSecondary
import com.deivid22srk.portstore.util.Formatters
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val OnLime = Color(0xFF171800)

// =====================================================================
// Barra superior estilo Switch: hora, conexão, bateria e perfil.
// =====================================================================

@Composable
fun LauncherTopBar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val connectedLabel = stringResource(R.string.launcher_status_connected)
    val offlineLabel = stringResource(R.string.launcher_status_offline)
    val chargingLabel = stringResource(R.string.launcher_status_charging)

    var time by remember { mutableStateOf("--:--") }
    LaunchedEffect(Unit) {
        val formatter = DateTimeFormatter.ofPattern("HH:mm")
        while (true) {
            time = LocalTime.now().format(formatter)
            delay(15_000)
        }
    }

    var batteryPct by remember { mutableIntStateOf(100) }
    var charging by remember { mutableStateOf(false) }
    val batteryDesc = stringResource(R.string.launcher_status_battery, batteryPct)
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                parseBattery(intent)?.let { (pct, isCharging) ->
                    batteryPct = pct
                    charging = isCharging
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        parseBattery(
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)),
        )?.let { (pct, isCharging) ->
            batteryPct = pct
            charging = isCharging
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    var online by remember { mutableStateOf(true) }
    DisposableEffect(context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                online = true
            }

            override fun onLost(network: Network) {
                online = false
            }
        }
        runCatching { cm?.registerDefaultNetworkCallback(callback) }
        online = cm?.getNetworkCapabilities(cm.activeNetwork) != null
        onDispose { runCatching { cm?.unregisterNetworkCallback(callback) } }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 10.dp),
    ) {
        Icon(
            Icons.Rounded.Schedule,
            contentDescription = null,
            tint = TextPrimary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(time, style = MaterialTheme.typography.labelLarge, color = TextPrimary)
        Spacer(Modifier.weight(1f))
        Icon(
            if (online) Icons.Rounded.Wifi else Icons.Rounded.WifiOff,
            contentDescription = if (online) connectedLabel else offlineLabel,
            tint = TextPrimary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Icon(
            if (charging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryFull,
            contentDescription = if (charging) chargingLabel else batteryDesc,
            tint = TextPrimary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text("$batteryPct%", style = MaterialTheme.typography.labelMedium, color = TextPrimary)
        Spacer(Modifier.width(14.dp))
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Lime.copy(alpha = 0.9f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.SportsEsports,
                contentDescription = stringResource(R.string.launcher_profile),
                tint = OnLime,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun parseBattery(intent: Intent?): Pair<Int, Boolean>? {
    intent ?: return null
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) return null
    val pct = (level * 100 / scale).coerceIn(0, 100)
    val isCharging = intent.getIntExtra(
        BatteryManager.EXTRA_STATUS,
        -1,
    ) == BatteryManager.BATTERY_STATUS_CHARGING
    return pct to isCharging
}

// =====================================================================
// Barra inferior de ícones circulares (Loja, Downloads, Configurações, Sair).
// =====================================================================

data class LauncherBottomItem(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val focusRequester: FocusRequester? = null,
)

@Composable
fun LauncherBottomBar(
    items: List<LauncherBottomItem>,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxWidth(),
    ) {
        items.forEach { item ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 10.dp),
            ) {
                var focused by remember { mutableStateOf(false) }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(58.dp)
                        .let { m ->
                            item.focusRequester?.let { m.focusRequester(it) } ?: m
                        }
                        .launcherFocusable(
                            shape = CircleShape,
                            scaleFocused = 1.14f,
                            onClick = item.onClick,
                            onFocusChange = { focused = it },
                        ),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(
                                if (focused) {
                                    Lime
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                            ),
                    )
                    Icon(
                        item.icon,
                        contentDescription = item.label,
                        tint = if (focused) OnLime else TextPrimary,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    item.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (focused) Lime else TextSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Dicas de botões do rodapé (muda conforme o último tipo de entrada). */
@Composable
fun LauncherHints(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = TextSecondary,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
    )
}

// =====================================================================
// Cards e linhas
// =====================================================================

/** Card quadrado de jogo instalado (home estilo Switch). */
@Composable
fun LauncherGameCard(
    game: Game,
    size: Dp,
    onClick: () -> Unit,
    onOptions: () -> Unit,
    modifier: Modifier = Modifier,
    onFocusChange: ((Boolean) -> Unit)? = null,
    focusRequester: FocusRequester? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .width(size)
            .height(size)
            .let { m -> focusRequester?.let { m.focusRequester(it) } ?: m }
            .launcherFocusable(
                shape = shape,
                scaleFocused = 1.14f,
                onClick = onClick,
                onLongClick = onOptions,
                onFocusChange = onFocusChange,
            ),
    ) {
        GameImage(url = game.coverUrl, modifier = Modifier.fillMaxSize(), shape = shape)
        if (game.packageName.isNotEmpty()) {
            LauncherInstallPill(
                game = game,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(8.dp),
            )
        }
    }
}

/** Selo "Em execução" / "Virtual" / "Instalado" / "Atualização" conforme o estado real. */
@Composable
fun LauncherInstallPill(game: Game, modifier: Modifier = Modifier) {
    if (game.isWeb || game.packageName.isEmpty()) return
    val states by AppGraph.installedApps.states.collectAsStateWithLifecycle()
    val vapps by AppGraph.virtual.apps.collectAsStateWithLifecycle()
    val running by AppGraph.virtual.running.collectAsStateWithLifecycle()
    val update = game.packageName.any { states[it] is InstallState.UpdateAvailable }
    val installed = game.packageName.any { states[it] is InstallState.Installed }
    val vpkg = AppGraph.virtual.packageNameFor(game.id)
    val virtual = vpkg?.let { pkg -> vapps.any { it.packageName == pkg } } == true
    val runningNow = vpkg != null && running.contains(vpkg)
    when {
        update -> Pill(
            stringResource(R.string.launcher_update_available),
            modifier = modifier,
            highlight = true,
        )
        // Jogo com processo vivo (inclui suspenso após "Voltar ao início").
        runningNow -> Pill(
            stringResource(R.string.game_running_badge),
            modifier = modifier,
            highlight = true,
        )
        virtual -> Pill(stringResource(R.string.virtual_badge), modifier = modifier)
        installed -> Pill(stringResource(R.string.launcher_installed), modifier = modifier)
        else -> Unit
    }
}

/** Card vertical da loja estilo eShop (capa 3:4 + nome + selo). */
@Composable
fun LauncherStoreCard(game: Game, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .width(124.dp)
            .launcherFocusable(
                shape = RoundedCornerShape(16.dp),
                scaleFocused = 1.06f,
                onClick = onClick,
            )
            .padding(6.dp),
    ) {
        Box {
            GameImage(
                url = game.coverUrl,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f),
                shape = shape,
            )
            LauncherInstallPill(
                game = game,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            game.title,
            style = MaterialTheme.typography.bodySmall,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Linha horizontal de jogos (seção da loja). */
@Composable
fun LauncherSection(
    title: String,
    games: List<Game>,
    onOpenGame: (String) -> Unit,
) {
    if (games.isEmpty()) return
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(games, key = { it.id }) { game ->
                LauncherStoreCard(game) { onOpenGame(game.id) }
            }
        }
    }
}

/** Banner grande do topo da loja (estilo eShop). */
@Composable
fun LauncherBanner(
    game: Game,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            .padding(horizontal = 24.dp)
            .fillMaxWidth()
            .height(170.dp)
            .launcherFocusable(shape = shape, scaleFocused = 1.02f, onClick = onClick),
    ) {
        GameImage(url = game.bannerUrl ?: game.coverUrl, modifier = Modifier.fillMaxSize(), shape = shape)
        Box(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .bottomScrim(),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
        ) {
            Text(
                game.title,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Pill(stringResource(R.string.launcher_view_details), highlight = true)
        }
    }
}

// =====================================================================
// Botões e linhas de ajuste
// =====================================================================

/** Botão "pílula" do Launcher, navegável por gamepad e toque (≥48dp). */
@Composable
fun LauncherButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = TextPrimary,
    icon: ImageVector? = null,
    focusRequester: FocusRequester? = null,
) {
    val shape = RoundedCornerShape(24.dp)
    val effectiveContainer = if (enabled) container else MaterialTheme.colorScheme.surfaceContainer
    val effectiveContent = if (enabled) contentColor else TextSecondary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .let { m -> focusRequester?.let { m.focusRequester(it) } ?: m }
            .background(effectiveContainer, shape)
            .launcherFocusable(
                shape = shape,
                enabled = enabled,
                scaleFocused = 1.05f,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = effectiveContent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = effectiveContent)
    }
}

/** Botão circular de ícone (ações de download, voltar etc.). */
@Composable
fun LauncherIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    tint: Color = TextPrimary,
    focusRequester: FocusRequester? = null,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .let { m -> focusRequester?.let { m.focusRequester(it) } ?: m }
            .size(46.dp)
            .background(container, CircleShape)
            .launcherFocusable(shape = CircleShape, scaleFocused = 1.1f, onClick = onClick),
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** Linha de ajuste com switch (configurações do Launcher). */
@Composable
fun LauncherSwitchRow(
    title: String,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .launcherFocusable(shape = shape, scaleFocused = 1.015f, onClick = onToggle)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Estado vazio (home sem jogos / downloads vazios). */
@Composable
fun LauncherEmptyState(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
    ) {
        Icon(
            Icons.Rounded.SportsEsports,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        LauncherButton(label = actionLabel, onClick = onAction, icon = Icons.Rounded.Search, container = Lime, contentColor = OnLime)
    }
}

/** Botão "X" de pesquisa no cabeçalho da loja. */
@Composable
fun LauncherSearchPill(onClick: () -> Unit) {
    LauncherButton(
        label = stringResource(R.string.tab_search),
        onClick = onClick,
        icon = Icons.Rounded.Search,
    )
}

// =====================================================================
// Linha de download (tela Downloads do Launcher)
// =====================================================================

@Composable
fun LauncherDownloadRow(
    item: DownloadItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onInstall: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .launcherFocusable(shape = shape, scaleFocused = 1.01f)
            .padding(12.dp),
    ) {
        GameImage(
            url = item.cover,
            modifier = Modifier.size(width = 56.dp, height = 72.dp),
            shape = RoundedCornerShape(10.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            DownloadProgressBar(progress = item.progress)
            Spacer(Modifier.height(6.dp))
            Text(
                downloadStatusText(item),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                item.isActive -> {
                    if (item.isPaused) {
                        LauncherIconButton(Icons.Rounded.PlayArrow, stringResource(R.string.resume), onResume)
                    } else {
                        LauncherIconButton(Icons.Rounded.Pause, stringResource(R.string.launcher_action_pause), onPause)
                    }
                    LauncherIconButton(Icons.Rounded.Close, stringResource(R.string.cancel), onCancel)
                }
                item.isPaused -> {
                    LauncherIconButton(Icons.Rounded.PlayArrow, stringResource(R.string.resume), onResume)
                    LauncherIconButton(Icons.Rounded.Close, stringResource(R.string.cancel), onCancel)
                }
                item.isFailed -> {
                    LauncherIconButton(Icons.Rounded.Refresh, stringResource(R.string.retry), onRetry)
                    LauncherIconButton(Icons.Rounded.Close, stringResource(R.string.cancel), onCancel)
                }
                item.isCompleted -> {
                    // Linha de DADOS complementares (gamedata:*) não tem APK
                    // para instalar — o pacote foi extraído pelo instalador;
                    // só apagar faz sentido.
                    if (!item.id.startsWith(GAMEDATA_JOB_PREFIX)) {
                        LauncherIconButton(Icons.Rounded.Download, stringResource(R.string.install_apk), onInstall)
                    }
                    LauncherIconButton(Icons.Rounded.Delete, stringResource(R.string.delete_file), onDelete)
                }
            }
        }
    }
}

@Composable
private fun downloadStatusText(item: DownloadItem): String = when (item.state) {
    com.deivid22srk.portstore.core.DlState.QUEUED -> stringResource(R.string.launcher_dl_queued)
    com.deivid22srk.portstore.core.DlState.CONNECTING -> stringResource(R.string.launcher_state_connecting)
    com.deivid22srk.portstore.core.DlState.VERIFYING -> stringResource(R.string.launcher_state_verifying)
    com.deivid22srk.portstore.core.DlState.DOWNLOADING -> {
        val pct = stringResource(R.string.launcher_state_downloading_pct, (item.progress * 100).toInt())
        val speed = Formatters.formatSpeed(item.speedBps)
        val eta = Formatters.formatEta(item.etaSec)
        listOfNotNull(pct, speed.takeIf { it.isNotBlank() }, eta.takeIf { it.isNotBlank() }).joinToString(" · ")
    }
    com.deivid22srk.portstore.core.DlState.PAUSED -> stringResource(R.string.launcher_dl_paused) + " " + (item.progress * 100).toInt() + "%"
    com.deivid22srk.portstore.core.DlState.COMPLETED -> stringResource(R.string.launcher_dl_completed)
    com.deivid22srk.portstore.core.DlState.FAILED ->
        stringResource(R.string.launcher_dl_failed) + (item.error?.let { " · $it" } ?: "")
    else -> item.state
}

/** Encontra a Activity dona do contexto (para janela/imersão). */
tailrec fun Context.findLauncherActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findLauncherActivity()
    else -> null
}
