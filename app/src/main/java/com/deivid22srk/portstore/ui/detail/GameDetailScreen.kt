@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.deivid22srk.portstore.ui.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deivid22srk.portstore.catalog.CatalogUrls
import com.deivid22srk.portstore.catalog.Game
import com.deivid22srk.portstore.core.DlState
import com.deivid22srk.portstore.core.DownloadItem
import com.deivid22srk.portstore.github.ApkAsset
import com.deivid22srk.portstore.ui.components.Badge
import com.deivid22srk.portstore.ui.components.DownloadProgressBar
import com.deivid22srk.portstore.ui.components.GameImage
import com.deivid22srk.portstore.ui.components.Pill
import com.deivid22srk.portstore.ui.components.SectionHeader
import com.deivid22srk.portstore.ui.theme.Lime
import com.deivid22srk.portstore.ui.theme.TextSecondary
import com.deivid22srk.portstore.util.Formatters
import com.deivid22srk.portstore.util.LinkOpener

@Composable
fun GameDetailScreen(
    viewModel: DetailViewModel,
    onBack: () -> Unit,
) {
    val game by viewModel.game.collectAsStateWithLifecycle()
    val item by viewModel.downloadItem.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val g = game
    if (g == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Lime)
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Voltar")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    LinkOpener.share(context, "${g.title} — Port Store / Hail Games\n${g.links.github ?: CatalogUrls.GITHUB_PORT_DB}")
                }) {
                    Icon(Icons.Rounded.Share, contentDescription = "Compartilhar")
                }
                OverflowMenu(g)
            }
        }

        // Créditos do autor (link) + título grande
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                val authorUrl = g.credit?.portUrl
                Text(
                    text = g.credit?.port ?: g.port ?: "Port da comunidade",
                    style = MaterialTheme.typography.labelLarge,
                    color = Lime,
                    modifier = Modifier.clickable(enabled = authorUrl != null) {
                        LinkOpener.open(context, authorUrl)
                    },
                )
                Spacer(Modifier.height(4.dp))
                Text(text = g.title, style = MaterialTheme.typography.headlineMedium)
            }
        }

        // Linha de informações com capa
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GameImage(
                    url = g.coverUrl,
                    modifier = Modifier.size(width = 86.dp, height = 114.dp),
                    shape = RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    g.performanceLabel?.let { Pill(it, highlight = true) }
                    val v = viewModel.version.collectAsStateWithLifecycle().value
                    val assetSize = v.release?.assets?.maxOfOrNull { it.size }?.takeIf { it > 0 }
                    InfoLine(
                        "Tamanho",
                        assetSize?.let { Formatters.formatBytes(it) }
                            ?: g.apkSize?.takeIf { it.isNotBlank() }
                            ?: "—",
                    )
                    val versionText = when {
                        v.loading -> "Verificando versão…"
                        v.release != null -> v.release.version + if (v.fromCache) " (cache)" else ""
                        else -> "Versão indisponível"
                    }
                    InfoLine("Versão", versionText)
                    when {
                        v.updateAvailable -> Pill("Atualização disponível", highlight = true)
                        v.install is com.deivid22srk.portstore.install.InstallState.Installed ->
                            Pill("Instalado" + (v.installedVersion?.let { " • $it" } ?: ""))
                        else -> Unit
                    }
                    InfoLine("Downloads", Formatters.formatCount(g.downloads))
                    InfoLine("Tipo", if (g.isWeb) "Web (navegador)" else "Android (APK)")
                }
            }
        }

        // Botão principal Instalar/Baixando/Abrir + dropdown
        item {
            InstallActionArea(g = g, item = item, ui = ui, viewModel = viewModel)
        }

        // Aviso de divergência de pacote após instalar o APK
        ui.installWarning?.let { warn ->
            item {
                Text(
                    text = warn,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        // Galeria de screenshots + vídeo
        val shots = g.screenshotUrls
        if (shots.isNotEmpty() || g.videoThumbUrl != null) {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (g.videoThumbUrl != null) {
                        item {
                            VideoThumb(url = g.videoThumbUrl!!) {
                                LinkOpener.open(context, g.videoUrl)
                            }
                        }
                    }
                    items(shots.size) { i ->
                        GameImage(
                            url = shots[i],
                            modifier = Modifier
                                .width(240.dp)
                                .aspectRatio(16f / 9f),
                            shape = RoundedCornerShape(12.dp),
                        )
                    }
                }
            }
        }

        // Sobre este jogo
        item {
            ExpandableSection(title = "Sobre este jogo") { expanded ->
                Column {
                    Text(
                        text = g.shortDescription ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                    if (expanded) {
                        Spacer(Modifier.height(8.dp))
                        g.description.forEach { paragraph ->
                            Text(
                                text = paragraph,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 3.dp),
                            )
                        }
                    }
                }
            }
        }

        // Requisitos
        g.requirements?.let { req ->
            if (req.minimo.isNotEmpty() || req.recomendado.isNotEmpty()) {
                item {
                    ExpandableSection(title = "Requisitos") { expanded ->
                        if (expanded) {
                            if (req.minimo.isNotEmpty()) {
                                Text("Mínimo", style = MaterialTheme.typography.titleSmall, color = Lime)
                                KeyValueTable(req.minimo)
                                Spacer(Modifier.height(10.dp))
                            }
                            if (req.recomendado.isNotEmpty()) {
                                Text("Recomendado", style = MaterialTheme.typography.titleSmall, color = Lime)
                                KeyValueTable(req.recomendado)
                            }
                        }
                    }
                }
            }
        }

        // Como instalar
        if (g.install.isNotEmpty()) {
            item {
                ExpandableSection(title = "Como instalar") { expanded ->
                    if (expanded) {
                        g.install.forEachIndexed { i, step ->
                            Row(modifier = Modifier.padding(vertical = 3.dp)) {
                                Text(
                                    text = "${i + 1}.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Lime,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(text = step, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }

        // Controles
        if (g.controls.isNotEmpty()) {
            item {
                ExpandableSection(title = "Controles") { expanded ->
                    if (expanded) {
                        g.controls.forEach { control ->
                            Row(modifier = Modifier.padding(vertical = 3.dp)) {
                                Icon(
                                    Icons.Rounded.Gamepad,
                                    contentDescription = null,
                                    tint = TextSecondary,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(text = control, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }

        // Observações
        g.notes?.takeIf { it.isNotBlank() }?.let { notes ->
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(modifier = Modifier.padding(12.dp)) {
                        Icon(Icons.Rounded.Info, contentDescription = null, tint = Lime, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(text = notes, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // Créditos
        g.credit?.let { credit ->
            item {
                SectionHeader("Créditos")
                Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    credit.game?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TextSecondary) }
                    credit.port?.let {
                        CreditLine("Port", it, credit.portUrl) { LinkOpener.open(context, credit.portUrl) }
                    }
                    credit.original?.let {
                        CreditLine("Original", it, credit.originalUrl) { LinkOpener.open(context, credit.originalUrl) }
                    }
                    g.license?.let { Text("Licença: $it", style = MaterialTheme.typography.bodySmall, color = TextSecondary) }
                }
            }
        }

        // Links
        item {
            SectionHeader("Links")
            Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (g.isWeb && g.playableUrl != null) {
                    LinkButton("Jogar no navegador", Icons.Rounded.SportsEsports) { LinkOpener.open(context, g.playableUrl) }
                }
                g.links.github?.let { LinkButton("Repositório do port", Icons.Rounded.OpenInNew) { LinkOpener.open(context, it) } }
                g.links.tutorial?.let { LinkButton("Tutorial no YouTube", Icons.Rounded.OpenInNew) { LinkOpener.open(context, it) } }
                g.links.site?.let { LinkButton("Site do projeto", Icons.Rounded.OpenInNew) { LinkOpener.open(context, it) } }
                LinkButton("Canal Hail Games", Icons.Rounded.OpenInNew) { LinkOpener.open(context, CatalogUrls.YOUTUBE_CHANNEL) }
            }
        }

        item {
            Text(
                text = "Este app não hospeda nenhum arquivo: os downloads apontam para os repositórios oficiais dos autores de cada port. Portas que exigem os arquivos originais do jogo deixam isso indicado na página.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    // Bottom sheet de variantes de APK
    ui.showVariants?.let { variants ->
        VariantBottomSheet(
            variants = variants,
            onPick = { viewModel.startDownload(g, it) },
            onDismiss = viewModel::dismissVariants,
        )
    }
}

@Composable
private fun OverflowMenu(game: Game) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Rounded.ExpandMore, contentDescription = "Mais opções")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Compartilhar") },
                onClick = {
                    expanded = false
                    LinkOpener.share(context, game.title)
                },
            )
            game.links.github?.let { url ->
                DropdownMenuItem(
                    text = { Text("Ver repositório do autor") },
                    onClick = {
                        expanded = false
                        LinkOpener.open(context, url)
                    },
                )
            }
            game.links.tutorial?.let { url ->
                DropdownMenuItem(
                    text = { Text("Tutorial no YouTube") },
                    onClick = {
                        expanded = false
                        LinkOpener.open(context, url)
                    },
                )
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.width(84.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CreditLine(label: String, value: String, url: String?, onClick: () -> Unit) {
    Row(modifier = Modifier.clickable(enabled = url != null, onClick = onClick)) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = if (url != null) Lime else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun LinkButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = Lime)
        Spacer(Modifier.width(8.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ExpandableSection(
    title: String,
    content: @Composable (expanded: Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { expanded = !expanded }
            .padding(14.dp)
            .animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "Recolher $title" else "Expandir $title",
                tint = Lime,
            )
        }
        Spacer(Modifier.height(6.dp))
        content(expanded)
    }
}

@Composable
private fun KeyValueTable(data: Map<String, String>) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        data.forEach { (k, v) ->
            Row {
                Text(
                    text = k,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(120.dp),
                )
                Text(text = v, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun VideoThumb(url: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(240.dp)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        GameImage(url = url, modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(0.dp))
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(46.dp)
                .clip(RoundedCornerShape(23.dp))
                .background(Color.Black.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.PlayArrow,
                contentDescription = "Assistir vídeo",
                tint = Lime,
                modifier = Modifier.size(30.dp),
            )
        }
        Badge(text = "Vídeo", modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
    }
}

/** Área do botão Instalar com máquina de estados igual à Play Store. */
@Composable
private fun InstallActionArea(
    g: Game,
    item: DownloadItem?,
    ui: DetailUiState,
    viewModel: DetailViewModel,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Coluna (não Box!) para o botão, a barra e os detalhes fluírem
            // verticalmente sem se sobrepor, ocupando a mesma largura.
            Column(modifier = Modifier.weight(1f)) {
                MainInstallButton(g = g, item = item, ui = ui, viewModel = viewModel)
                if (item != null && item.state == DlState.DOWNLOADING) {
                    Spacer(Modifier.height(8.dp))
                    DownloadProgressBar(
                        progress = item.progress,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = listOfNotNull(
                                "${Formatters.formatBytes(item.downloaded)} de ${Formatters.formatBytes(item.total)}",
                                Formatters.formatSpeed(item.speedBps).takeIf { it.isNotBlank() },
                                Formatters.formatEta(item.etaSec).takeIf { it.isNotBlank() },
                            ).joinToString(" • "),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = viewModel::pause) {
                            Icon(Icons.Rounded.Pause, contentDescription = "Pausar", tint = Lime)
                        }
                        IconButton(onClick = viewModel::cancel) {
                            Icon(Icons.Rounded.Cancel, contentDescription = "Cancelar", tint = TextSecondary)
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            MoreInstallMenu(g = g, viewModel = viewModel)
        }
        ui.resolveError?.let { err ->
            Spacer(Modifier.height(6.dp))
            Text(text = err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = buildString {
                append("Instalar no smartphone • Port da comunidade")
                if (!g.isWeb) append(" • Pode exigir os arquivos originais do jogo")
            },
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun MainInstallButton(
    g: Game,
    item: DownloadItem?,
    ui: DetailUiState,
    viewModel: DetailViewModel,
) {
    val state = item?.state
    val context = LocalContext.current
    val version by viewModel.version.collectAsStateWithLifecycle()
    var confirmUninstall by remember { mutableStateOf(false) }

    when {
        g.isRemoved -> OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) { Text("Removido") }
        g.isSoon -> OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) { Text("Em breve") }
        g.isWeb -> Button(
            onClick = { LinkOpener.open(context, g.playableUrl) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = installButtonColors(),
        ) {
            Icon(Icons.Rounded.SportsEsports, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Jogar no navegador", fontWeight = FontWeight.SemiBold)
        }

        ui.resolving -> OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Lime)
            Spacer(Modifier.width(8.dp))
            Text("Procurando APK…")
        }

        state == DlState.DOWNLOADING || state == DlState.VERIFYING || state == DlState.CONNECTING || state == DlState.QUEUED -> {
            // Apenas o botão: a barra de progresso e os detalhes ficam em
            // InstallActionArea, em linhas separadas (título em cima, detalhes embaixo).
            Button(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    disabledContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                when (state) {
                    DlState.VERIFYING -> Text(
                        text = "Verificando…",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    DlState.CONNECTING, DlState.QUEUED -> Text(
                        text = "Conectando…",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    else -> {
                        val pct = (item!!.progress * 100).toInt()
                        Text(
                            text = "Baixando $pct%",
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        state == DlState.PAUSED -> Button(
            onClick = viewModel::resume,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = installButtonColors(),
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Retomar", fontWeight = FontWeight.SemiBold)
        }

        state == DlState.COMPLETED -> Button(
            onClick = { viewModel.installApk(item!!.destPath) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = installButtonColors(),
        ) {
            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Instalar APK", fontWeight = FontWeight.SemiBold)
        }

        state == DlState.FAILED -> OutlinedButton(
            onClick = viewModel::retry,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
        ) {
            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(20.dp), tint = Lime)
            Spacer(Modifier.width(8.dp))
            Text("Tentar novamente", color = MaterialTheme.colorScheme.error)
        }

        version.updateAvailable -> Button(
            onClick = { viewModel.onInstallClicked(g) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = installButtonColors(),
        ) {
            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Atualizar", fontWeight = FontWeight.SemiBold)
        }

        version.install is com.deivid22srk.portstore.install.InstallState.Installed -> {
            // Instalado, sem atualização: Jogar (principal) + Desinstalar (secundário), lado a lado.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = viewModel::openInstalled,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    colors = installButtonColors(),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Jogar",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(
                    onClick = { confirmUninstall = true },
                    modifier = Modifier.weight(0.62f),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Desinstalar", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        else -> Button(
            onClick = { viewModel.onInstallClicked(g) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = installButtonColors(),
        ) {
            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Instalar", fontWeight = FontWeight.SemiBold)
        }
    }

    // Confirmação antes de remover o port instalado (o sistema confirma de novo).
    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { confirmUninstall = false },
            title = { Text("Desinstalar este jogo?") },
            text = {
                Text(
                    "Remover \"${g.title}\" do aparelho. Arquivos salvos pelo jogo " +
                        "(progresso, mundos e configurações) podem ser apagados junto.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmUninstall = false
                    viewModel.uninstallInstalled()
                }) {
                    Text(
                        "Desinstalar",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmUninstall = false }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun installButtonColors() = ButtonDefaults.buttonColors(
    containerColor = Lime,
    contentColor = Color(0xFF171800),
)

@Composable
private fun MoreInstallMenu(g: Game, viewModel: DetailViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Column {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Rounded.ExpandMore, contentDescription = "Mais opções de instalação", tint = Lime)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (!g.isWeb) {
                DropdownMenuItem(
                    text = { Text("Escolher variante do APK") },
                    onClick = {
                        expanded = false
                        viewModel.onInstallClicked(g)
                    },
                )
            }
            gameLinksMenu(g) {
                DropdownMenuItem(
                    text = { Text("Ver repositório do autor") },
                    onClick = {
                        expanded = false
                        LinkOpener.open(context, g.links.github ?: g.credit?.portUrl)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Compartilhar") },
                    onClick = {
                        expanded = false
                        LinkOpener.share(context, g.title)
                    },
                )
            }
        }
    }
}

private inline fun gameLinksMenu(g: Game, content: () -> Unit) { content() }

@Composable
private fun VariantBottomSheet(
    variants: List<ApkAsset>,
    onPick: (ApkAsset) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = "Escolher variante do APK",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        variants.forEach { asset ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(asset) }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null, tint = Lime, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = asset.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (asset.size > 0) {
                        Text(
                            text = Formatters.formatBytes(asset.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                }
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = TextSecondary)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
