package com.deivid22srk.portstore.ui.you

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deivid22srk.portstore.AppGraph
import com.deivid22srk.portstore.catalog.Game
import com.deivid22srk.portstore.install.InstallState
import com.deivid22srk.portstore.install.InstalledAppsMonitor
import com.deivid22srk.portstore.ui.components.GameImage
import com.deivid22srk.portstore.ui.components.Pill
import com.deivid22srk.portstore.ui.theme.Lime
import com.deivid22srk.portstore.ui.theme.TextSecondary

/** Jogo presente no aparelho (instalado ou com atualização disponível). */
private data class InstalledEntry(val game: Game, val state: InstallState)

/**
 * "Meus jogos instalados" — lista dos ports instalados, aberta a partir da
 * aba Você. A lista reflete o estado real: reavalia ao entrar na tela e
 * reage a instalar/desinstalar (broadcasts do InstalledAppsMonitor).
 */
@Composable
fun InstalledGamesScreen(
    onBack: () -> Unit,
    onOpenGame: (String) -> Unit,
    onGoToTab: (String) -> Unit,
) {
    val context = LocalContext.current
    val catalog by AppGraph.catalog.catalog.collectAsStateWithLifecycle()
    val installStates by AppGraph.installedApps.states.collectAsStateWithLifecycle()

    // Atualiza ao abrir a tela (ex.: usuário desinstalou fora da loja).
    LaunchedEffect(Unit) { AppGraph.installedApps.refresh() }

    val installed = catalog?.games.orEmpty().mapNotNull { g ->
        if (g.isWeb || g.packageName.isEmpty()) return@mapNotNull null
        val state = g.packageName.mapNotNull { pkg -> installStates[pkg] }
            .firstOrNull { it is InstallState.Installed || it is InstallState.UpdateAvailable }
            ?: return@mapNotNull null
        InstalledEntry(g, state)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        // Barra superior
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Voltar")
            }
            Text(
                "Meus jogos instalados",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (installed.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 32.dp),
                ) {
                    Icon(
                        Icons.Rounded.SportsEsports,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Nenhum jogo instalado ainda",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Baixe um port na aba Jogos — depois de instalado, " +
                            "ele aparece aqui automaticamente.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { onGoToTab("games") },
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Lime,
                            contentColor = Color(0xFF171800),
                        ),
                    ) {
                        Text("Ver jogos", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
            ) {
                items(installed, key = { it.game.id }) { entry ->
                    InstalledGameRow(
                        entry = entry,
                        onOpen = { onOpenGame(entry.game.id) },
                        onPlay = {
                            val pkg = entry.game.primaryPackage
                            val intent = pkg?.let { InstalledAppsMonitor.launchIntent(context, it) }
                            if (intent != null) {
                                context.startActivity(intent)
                            } else {
                                Toast.makeText(
                                    context,
                                    "Não foi possível abrir o jogo.",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun InstalledGameRow(
    entry: InstalledEntry,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
) {
    val g = entry.game
    val installedVersion = when (val s = entry.state) {
        is InstallState.Installed -> s.versionName
        is InstallState.UpdateAvailable -> s.installedVersion
        else -> null
    }
    val subtitle = "Instalado" + (installedVersion?.takeIf { it.isNotBlank() }?.let { " • $it" } ?: "")

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        GameImage(
            url = g.coverUrl,
            modifier = Modifier.size(width = 46.dp, height = 60.dp),
            shape = RoundedCornerShape(8.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                g.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (entry.state is InstallState.UpdateAvailable) {
            Spacer(Modifier.width(8.dp))
            Pill("Atualização", highlight = true)
        }
        IconButton(onClick = onPlay) {
            Icon(
                Icons.Rounded.SportsEsports,
                contentDescription = "Jogar ${g.title}",
                tint = Lime,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
