package com.deivid22srk.portstore.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deivid22srk.portstore.AppGraph
import com.deivid22srk.portstore.R
import com.deivid22srk.portstore.catalog.Game
import com.deivid22srk.portstore.catalog.categoryLabel
import com.deivid22srk.portstore.launcher.input.LauncherInitialFocus
import com.deivid22srk.portstore.launcher.input.launcherFocusable
import com.deivid22srk.portstore.ui.components.GameImage
import com.deivid22srk.portstore.ui.search.SearchViewModel
import com.deivid22srk.portstore.ui.theme.Lime
import com.deivid22srk.portstore.ui.theme.TextPrimary
import com.deivid22srk.portstore.ui.theme.TextSecondary

/**
 * Pesquisa do Game Launcher com teclado virtual navegável por gamepad
 * (D-pad entre as teclas, A digita, X apaga, B volta) e toque direto nas
 * teclas. Sem IME: o campo de consulta é alimentado pelo teclado virtual,
 * funcionando igual em touch e gamepad. Teclado físico também digita.
 */
@Composable
fun LauncherSearchScreen(
    onOpenGame: (String) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: SearchViewModel = viewModel {
        SearchViewModel(AppGraph.catalog, AppGraph.db.searchHistory())
    }
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()

    val backRequester = remember { FocusRequester() }
    val hintBack = stringResource(R.string.launcher_hint_details)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            // Teclado físico (emulador/caixas com teclado): digita direto.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || event.nativeKeyEvent.repeatCount > 0) {
                    return@onPreviewKeyEvent false
                }
                val native = event.nativeKeyEvent
                val unicode = native.unicodeChar
                when {
                    unicode in ' '..'~' -> {
                        viewModel.onQueryChange(query + unicode.toChar())
                        true
                    }
                    native.keyCode == android.view.KeyEvent.KEYCODE_DEL ||
                        native.keyCode == android.view.KeyEvent.KEYCODE_FORWARD_DEL -> {
                        viewModel.onQueryChange(query.dropLast(1))
                        true
                    }
                    event.key == Key.Enter -> {
                        viewModel.submit()
                        true
                    }
                    else -> false
                }
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(20.dp)) {
            Box(Modifier.focusRequester(backRequester)) {
                LauncherIconButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = hintBack,
                    onClick = onBack,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.tab_search), style = MaterialTheme.typography.headlineSmall)
        }

        // Campo de consulta (somente leitura: alimentado pelo teclado virtual).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainer,
                    RoundedCornerShape(24.dp),
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                tint = if (query.isEmpty()) TextSecondary else Lime,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                query.ifEmpty { stringResource(R.string.search_hint) },
                style = MaterialTheme.typography.bodyLarge,
                color = if (query.isEmpty()) TextSecondary else TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Buscas recentes (quando o campo está vazio).
        if (query.isEmpty() && recent.isNotEmpty()) {
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 10.dp),
            ) {
                items(recent) { term ->
                    LauncherButton(label = term) { viewModel.applyTerm(term) }
                }
            }
        }

        VirtualKeyPad(
            onKey = { ch -> viewModel.onQueryChange(query + ch) },
            onBackspace = { viewModel.onQueryChange(query.dropLast(1)) },
            onClear = { viewModel.onQueryChange("") },
        )

        // Resultados
        if (query.isNotEmpty()) {
            if (results.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.launcher_no_results, query),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            } else {
                Text(
                    stringResource(R.string.launcher_results_count, results.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
                )
                LazyColumn(Modifier.weight(1f)) {
                    items(results, key = { it.id }) { game ->
                        LauncherSearchRow(game) { onOpenGame(game.id) }
                    }
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }

        LauncherHints(stringResource(R.string.launcher_hint_search))
    }

    LauncherInitialFocus(backRequester)
}

/** Linha de resultado da pesquisa. */
@Composable
private fun LauncherSearchRow(game: Game, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .launcherFocusable(
                shape = RoundedCornerShape(14.dp),
                scaleFocused = 1.02f,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        GameImage(
            url = game.coverUrl,
            modifier = Modifier.size(width = 52.dp, height = 68.dp),
            shape = RoundedCornerShape(10.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                game.title,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(game.categories.take(2).joinToString(" · ") { categoryLabel(it) })
                    game.original?.let { append(" · ").append(it) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LauncherInstallPill(game)
    }
}

/** Teclado virtual: 4 linhas de letras/dígitos + ESPAÇO / APAGAR / LIMPAR. */
@Composable
private fun VirtualKeyPad(
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
) {
    val rows = remember {
        listOf(
            listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '0'),
            listOf('Q', 'W', 'E', 'R', 'T', 'Y', 'U', 'I', 'O', 'P'),
            listOf('A', 'S', 'D', 'F', 'G', 'H', 'J', 'K', 'L'),
            listOf('Z', 'X', 'C', 'V', 'B', 'N', 'M'),
        )
    }
    val shape = RoundedCornerShape(10.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        rows.forEach { rowKeys ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowKeys.forEach { ch ->
                    KeyCap(label = ch.toString(), shape = shape) { onKey(ch.lowercaseChar()) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyCap(
                label = stringResource(R.string.launcher_keypad_space),
                shape = shape,
                width = 160.dp,
            ) { onKey(' ') }
            KeyCap(
                label = stringResource(R.string.launcher_keypad_backspace),
                shape = shape,
                width = 110.dp,
                icon = Icons.Rounded.Backspace,
            ) { onBackspace() }
            KeyCap(
                label = stringResource(R.string.launcher_keypad_clear),
                shape = shape,
                width = 110.dp,
                icon = Icons.Rounded.Close,
            ) { onClear() }
        }
    }
}

@Composable
private fun KeyCap(
    label: String,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
    width: androidx.compose.ui.unit.Dp? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .let { m -> width?.let { m.width(it) } ?: m }
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .launcherFocusable(shape = shape, scaleFocused = 1.1f, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = label, tint = TextPrimary, modifier = Modifier.size(20.dp))
        } else {
            Text(label, style = MaterialTheme.typography.labelLarge, color = TextPrimary)
        }
    }
}
