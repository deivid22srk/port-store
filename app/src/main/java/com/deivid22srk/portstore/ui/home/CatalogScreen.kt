@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.deivid22srk.portstore.ui.home

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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deivid22srk.portstore.catalog.Catalog
import com.deivid22srk.portstore.catalog.CatalogUrls
import com.deivid22srk.portstore.catalog.Game
import com.deivid22srk.portstore.catalog.categoryLabel
import com.deivid22srk.portstore.ui.components.AppLogo
import com.deivid22srk.portstore.ui.components.Badge
import com.deivid22srk.portstore.ui.components.CoverCard
import com.deivid22srk.portstore.ui.components.GameImage
import com.deivid22srk.portstore.ui.components.SectionHeader
import com.deivid22srk.portstore.ui.components.SuggestionCard
import com.deivid22srk.portstore.ui.components.bottomScrim
import com.deivid22srk.portstore.ui.components.shimmer
import com.deivid22srk.portstore.ui.theme.Lime
import com.deivid22srk.portstore.ui.theme.TextSecondary
import com.deivid22srk.portstore.util.LinkOpener
import kotlinx.coroutines.delay

@Composable
fun CatalogScreen(
    viewModel: CatalogViewModel,
    mode: CatalogViewModel.Mode,
    onOpenGame: (String) -> Unit,
    onOpenDownloads: () -> Unit,
    onGoToTab: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectedChip by viewModel.selectedChip.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(mode = mode, onOpenDownloads = onOpenDownloads, onGoToTab = onGoToTab)
        ChipsRow(
            catalog = state.catalog,
            selected = selectedChip,
            mode = mode,
            onSelect = viewModel::selectChip,
        )
        when {
            state.loading -> SkeletonContent()
            state.catalog == null -> ErrorContent(message = state.error ?: "Erro") {
                viewModel.refresh(pull = false)
            }
            else -> PullRefreshContent(
                state = state,
                viewModel = viewModel,
                selectedChip = selectedChip,
                mode = mode,
                onOpenGame = onOpenGame,
                onGoToTab = onGoToTab,
            )
        }
    }
}

@Composable
private fun TopBar(
    mode: CatalogViewModel.Mode,
    onOpenDownloads: () -> Unit,
    onGoToTab: (String) -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.deivid22srk.portstore.ui.components.AppLogo()
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (mode == CatalogViewModel.Mode.HOME) "Port Store" else "Jogos",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.weight(1f))
        IconButton48(
            icon = Icons.Rounded.Notifications,
            description = "Notificações",
        ) {
            LinkOpener.open(context, CatalogUrls.YOUTUBE_CHANNEL)
        }
        IconButton48(
            icon = Icons.Rounded.Settings,
            description = "Configurações",
        ) {
            onGoToTab("you")
        }
    }
}

@Composable
private fun IconButton48(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = description, tint = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ChipsRow(
    catalog: Catalog?,
    selected: String,
    mode: CatalogViewModel.Mode,
    onSelect: (String) -> Unit,
) {
    val chips = remember(catalog, mode) {
        buildList {
            if (mode == CatalogViewModel.Mode.HOME) {
                add(Chips.EM_ALTA to "Em alta")
                add(Chips.NOVOS to "Novos")
            } else {
                add("todos" to "Todos")
            }
            catalog?.games
                ?.flatMap { it.categories }
                ?.distinct()
                ?.take(10)
                ?.forEach { add(it to categoryLabel(it)) }
            if (mode == CatalogViewModel.Mode.GAMES) {
                add("web" to "Web")
            }
        }
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        items(chips.size) { i ->
            val (id, label) = chips[i]
            FilterChip(
                selected = selected == id,
                onClick = { onSelect(id) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Lime,
                    selectedLabelColor = Color(0xFF171800),
                ),
            )
        }
    }
}

@Composable
private fun PullRefreshContent(
    state: CatalogUiState,
    viewModel: CatalogViewModel,
    selectedChip: String,
    mode: CatalogViewModel.Mode,
    onOpenGame: (String) -> Unit,
    onGoToTab: (String) -> Unit,
) {
    val catalog = state.catalog ?: return
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = { viewModel.refresh(pull = true) },
    ) {
        val games = catalog.games.filter { !it.isRemoved }
        val filtered = when {
            mode == CatalogViewModel.Mode.GAMES -> when (selectedChip) {
                "todos" -> games
                "web" -> games.filter { it.isWeb }
                else -> games.filter { selectedChip in it.categories }
            }
            selectedChip == Chips.EM_ALTA -> games.sortedByDescending { it.downloads }
            selectedChip == Chips.NOVOS -> games.sortedByDescending { it.dateAdded ?: "" }
            else -> games.filter { selectedChip in it.categories }
        }

        if (mode == CatalogViewModel.Mode.GAMES || selectedChip != Chips.EM_ALTA) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(filtered.size, key = { filtered[it].id }) { i ->
                    val game = filtered[i]
                    SuggestionCard(
                        game = game,
                        onClick = { onOpenGame(game.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 5.dp),
                    )
                }
            }
        } else {
            HomeContent(catalog = catalog, games = games, onOpenGame = onOpenGame, onGoToTab = onGoToTab)
        }
    }
}

@Composable
private fun HomeContent(
    catalog: Catalog,
    games: List<Game>,
    onOpenGame: (String) -> Unit,
    onGoToTab: (String) -> Unit,
) {
    val newest = games.sortedByDescending { it.dateAdded ?: "" }
    val mostDownloaded = games.sortedByDescending { it.downloads }
    val light = games.filter { it.performance == "leve" }
    val web = games.filter { it.isWeb }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { HeroPager(games.filter { it.featured }.ifEmpty { newest.take(5) }, onOpenGame) }
        item {
            SectionHeader("Novos ports", onMore = { onGoToTab("games") })
            Carousel(newest.take(12), onOpenGame)
        }
        item {
            SectionHeader("Mais baixados")
            Carousel(mostDownloaded.take(12), onOpenGame)
        }
        if (light.isNotEmpty()) {
            item {
                SectionHeader("Leves para seu aparelho")
                Carousel(light.take(12), onOpenGame)
            }
        }
        if (web.isNotEmpty()) {
            item {
                SectionHeader("Ports Web")
                Carousel(web.take(12), onOpenGame)
            }
        }
        item {
            SectionHeader("Sugestões para você")
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                newest.take(4).forEach { game ->
                    SuggestionCard(
                        game = game,
                        onClick = { onOpenGame(game.id) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun Carousel(items: List<Game>, onOpenGame: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items.size) { i ->
            CoverCard(game = items[i], onClick = { onOpenGame(items[i].id) })
        }
    }
}

/** Hero/carrossel grande com banner 16:9, selo, título e botão Instalar. */
@Composable
private fun HeroPager(games: List<Game>, onOpenGame: (String) -> Unit) {
    if (games.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { games.size })
    LaunchedEffect(pagerState.pageCount) {
        while (pagerState.pageCount > 1) {
            delay(5000)
            val next = (pagerState.currentPage + 1) % pagerState.pageCount
            runCatching { pagerState.animateScrollToPage(next) }
        }
    }
    Column {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            pageSpacing = 12.dp,
        ) { page ->
            val game = games[page]
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(18.dp))
                    .clickable { onOpenGame(game.id) },
            ) {
                GameImage(
                    url = game.bannerUrl ?: game.coverUrl,
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(0.dp),
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .bottomScrim(),
                )
                Badge(
                    text = when {
                        game.isSoon -> "Em breve"
                        game.isWeb -> "Web"
                        else -> "Novo"
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(14.dp),
                ) {
                    Text(
                        text = game.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = game.shortDescription ?: game.port ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { onOpenGame(game.id) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Lime,
                                contentColor = Color(0xFF171800),
                            ),
                            shape = RoundedCornerShape(22.dp),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Ver jogo", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            repeat(games.size) { i ->
                val active = pagerState.currentPage == i
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (active) 8.dp else 6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (active) Lime else TextSecondary.copy(alpha = 0.4f)),
                )
            }
        }
    }
}

@Composable
private fun SkeletonContent() {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(18.dp))
                .shimmer(),
        )
        Spacer(Modifier.height(20.dp))
        repeat(3) {
            Box(
                modifier = Modifier
                    .width(140.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .shimmer(),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .width(128.dp)
                            .height(170.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .shimmer(),
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Rounded.CloudOff,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Color(0xFF171800))) {
            Text("Tentar novamente")
        }
    }
}
