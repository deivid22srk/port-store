package com.deivid22srk.portstore.ui.games

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deivid22srk.portstore.AppGraph
import com.deivid22srk.portstore.ui.home.CatalogScreen
import com.deivid22srk.portstore.ui.home.CatalogViewModel

/** Aba Jogos: mesma engine da Home, em modo catálogo completo com filtros. */
@Composable
fun GamesScreen(
    onOpenGame: (String) -> Unit,
    onGoToTab: (String) -> Unit,
) {
    val vm: CatalogViewModel = viewModel(key = "games") {
        CatalogViewModel(AppGraph.catalog, CatalogViewModel.Mode.GAMES)
    }
    CatalogScreen(
        viewModel = vm,
        mode = CatalogViewModel.Mode.GAMES,
        onOpenGame = onOpenGame,
        onOpenDownloads = {},
        onGoToTab = onGoToTab,
    )
}
