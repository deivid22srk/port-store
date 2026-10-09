package com.deivid22srk.portstore.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deivid22srk.portstore.AppGraph
import com.deivid22srk.portstore.catalog.Game
import com.deivid22srk.portstore.catalog.categoryLabel
import com.deivid22srk.portstore.ui.components.GameImage
import com.deivid22srk.portstore.ui.components.Pill
import com.deivid22srk.portstore.ui.theme.Lime
import com.deivid22srk.portstore.ui.theme.TextSecondary

private val popularTerms = listOf("Sonic", "Halo", "CSGO", "Skate", "Corrida", "Plataforma", "RPG", "Web")

@Composable
fun SearchScreen(
    onOpenGame: (String) -> Unit,
    onBack: () -> Unit,
) {
    val vm: SearchViewModel = viewModel {
        SearchViewModel(AppGraph.catalog, AppGraph.db.searchHistoryDao())
    }
    val query by vm.query.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Voltar")
            }
            OutlinedTextField(
                value = query,
                onValueChange = vm::onQueryChange,
                placeholder = { Text("Pesquisar jogos e ports", color = TextSecondary) },
                singleLine = true,
                shape = RoundedCornerShape(26.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.submit() }),
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = TextSecondary) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { vm.onQueryChange("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Limpar")
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Lime,
                    cursorColor = Lime,
                ),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
            )
        }

        FilterRow(vm = vm, filter = filter)

        if (query.isBlank()) {
            Suggestions(recent = recent, onPick = vm::applyTerm)
        } else {
            ResultList(results = results, query = query, onOpenGame = {
                vm.saveRecent(query)
                onOpenGame(it)
            })
        }
    }
}

@Composable
private fun FilterRow(vm: SearchViewModel, filter: String) {
    val options = listOf(
        "todos" to "Todos",
        "android" to "Android",
        "web" to "Web",
        "leve" to "Leve",
        "medio" to "Médio",
        "pesado" to "Pesado",
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(options.size) { i ->
            val (id, label) = options[i]
            FilterChip(
                selected = filter == id,
                onClick = { vm.setFilter(id) },
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
private fun Suggestions(recent: List<String>, onPick: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        if (recent.isNotEmpty()) {
            item {
                Text(
                    "Buscas recentes",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            items(recent.size) { i ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(recent[i]) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.Rounded.History, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(recent[i], style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        item {
            Text(
                "Populares",
                style = MaterialTheme.typography.titleSmall,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(popularTerms.size) { i ->
                    FilterChip(
                        selected = false,
                        onClick = { onPick(popularTerms[i]) },
                        label = { Text(popularTerms[i]) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultList(results: List<Game>, query: String, onOpenGame: (String) -> Unit) {
    if (results.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            Text(
                "Nenhum resultado para \"$query\"",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
            )
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        items(results.size, key = { results[it].id }) { i ->
            val game = results[i]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenGame(game.id) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                GameImage(
                    url = game.coverUrl,
                    modifier = Modifier.size(width = 52.dp, height = 68.dp),
                    shape = RoundedCornerShape(8.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = highlight(game.title, query),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = game.categories.take(2).joinToString(" · ") { categoryLabel(it) } +
                            (game.original?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        game.performanceLabel?.let { Pill(it, highlight = true) }
                        game.apkSize?.takeIf { it.isNotBlank() }?.let { Pill(it) }
                        if (game.isWeb) Pill("Web", highlight = true)
                    }
                }
            }
        }
    }
}

/** Destaca o termo pesquisado em negrito/lime. */
private fun highlight(text: String, term: String) = buildAnnotatedString {
    if (term.isBlank()) {
        append(text)
        return@buildAnnotatedString
    }
    val lower = text.lowercase()
    val needle = term.lowercase().trim()
    if (needle.isEmpty()) {
        append(text)
        return@buildAnnotatedString
    }
    var index = 0
    while (index <= text.length) {
        val found = lower.indexOf(needle, index)
        if (found < 0) {
            append(text.substring(index))
            break
        }
        append(text.substring(index, found))
        addStyle(
            SpanStyle(color = Lime, fontWeight = FontWeight.Bold),
            found,
            found + needle.length,
        )
        append(text.substring(found, found + needle.length))
        index = found + needle.length
    }
}
