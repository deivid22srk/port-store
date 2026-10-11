package com.deivid22srk.portstore.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deivid22srk.portstore.R
import com.deivid22srk.portstore.launcher.input.LauncherInitialFocus
import com.deivid22srk.portstore.ui.theme.Lime
import com.deivid22srk.portstore.ui.theme.TextSecondary

/**
 * Loja do Game Launcher (estilo Nintendo eShop): banner em destaque no topo e
 * seções em linhas horizontais (Em alta, Novos ports, Mais baixados e por
 * categoria), com os mesmos jogos do catálogo do Port Store e selo "Instalado"
 * quando aplicável. 100% navegável por gamepad (D-pad entre linhas e cards,
 * L1/R1 troca de seção raiz, X abre a pesquisa).
 */
@Composable
fun LauncherStoreScreen(
    viewModel: LauncherViewModel,
    onOpenGame: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onBack: () -> Unit,
) {
    val sections by viewModel.storeSections.collectAsStateWithLifecycle()
    val catalog by viewModel.catalogState.collectAsStateWithLifecycle()
    val monitor = com.deivid22srk.portstore.launcher.input.LocalGamepadMonitor.current
    val gamepadInput = if (monitor != null) {
        monitor.lastInputGamepad.collectAsStateWithLifecycle().value
    } else {
        false
    }
    val bannerRequester = remember { FocusRequester() }
    val empty = sections.banner == null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // Cabeçalho: voltar + título + pesquisa.
        androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            LauncherIconButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.launcher_hint_menu),
                onClick = onBack,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.launcher_store_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            LauncherSearchPill(onClick = onOpenSearch)
        }

        when {
            catalog == null && empty -> {
                // Carregando ou sem conexão: botão de tentar novamente sempre disponível.
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Lime)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.launcher_catalog_error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                        Spacer(Modifier.height(16.dp))
                        LauncherButton(
                            label = stringResource(R.string.retry),
                            onClick = viewModel::refreshCatalog,
                        )
                    }
                }
            }

            else -> {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    item {
                        sections.banner?.let { banner ->
                            Box(Modifier.padding(top = 4.dp)) {
                                LauncherBanner(
                                    game = banner,
                                    onClick = { onOpenGame(banner.id) },
                                    modifier = Modifier.focusRequester(bannerRequester),
                                )
                            }
                        }
                    }
                    item {
                        LauncherSection(
                            title = stringResource(R.string.launcher_section_trending),
                            games = sections.trending,
                            onOpenGame = onOpenGame,
                        )
                    }
                    item {
                        LauncherSection(
                            title = stringResource(R.string.launcher_section_new),
                            games = sections.fresh,
                            onOpenGame = onOpenGame,
                        )
                    }
                    item {
                        LauncherSection(
                            title = stringResource(R.string.launcher_section_downloaded),
                            games = sections.mostDownloaded,
                            onOpenGame = onOpenGame,
                        )
                    }
                    sections.byCategory.forEach { (label, games) ->
                        item(key = "cat-$label") {
                            LauncherSection(
                                title = label,
                                games = games,
                                onOpenGame = onOpenGame,
                            )
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }

        LauncherHints(
            stringResource(
                if (gamepadInput) R.string.launcher_hint_store else R.string.launcher_hint_touch,
            ),
        )
    }

    LauncherInitialFocus(bannerRequester, key = sections.banner?.id)
}
