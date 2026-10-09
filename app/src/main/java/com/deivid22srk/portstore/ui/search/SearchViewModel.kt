package com.deivid22srk.portstore.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deivid22srk.portstore.catalog.CatalogRepository
import com.deivid22srk.portstore.catalog.Game
import com.deivid22srk.portstore.db.SearchHistoryDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.Normalizer

fun String.normalizeSearch(): String = Normalizer.normalize(this, Normalizer.Form.NFD)
    .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    .lowercase()

class SearchViewModel(
    private val catalogRepo: CatalogRepository,
    private val historyDao: SearchHistoryDao,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filter = MutableStateFlow("todos")
    val filter: StateFlow<String> = _filter.asStateFlow()

    val results: StateFlow<List<Game>> = combine(
        catalogRepo.catalog,
        _query,
        _filter,
    ) { catalog, q, f ->
        val all = catalog?.games?.filter { !it.isRemoved } ?: emptyList()
        val needle = q.normalizeSearch().trim()
        val byText = if (needle.isEmpty()) all else all.filter { game ->
            listOf(game.title, game.port, game.original, game.shortDescription)
                .asSequence()
                .filterNotNull()
                .plus(game.tags)
                .plus(game.categories)
                .any { it.normalizeSearch().contains(needle) }
        }
        when (f) {
            "android" -> byText.filter { !it.isWeb }
            "web" -> byText.filter { it.isWeb }
            "leve", "medio", "pesado" -> byText.filter { it.performance == f }
            else -> byText
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val recent: StateFlow<List<String>> = historyDao.observeRecent()
        .map { list -> list.map { it.query } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun setFilter(filter: String) {
        _filter.value = filter
    }

    fun applyTerm(term: String) {
        _query.value = term
    }

    fun submit() {
        val q = _query.value.trim()
        if (q.isNotEmpty()) saveRecent(q)
    }

    fun saveRecent(q: String) {
        viewModelScope.launch {
            historyDao.insert(com.deivid22srk.portstore.db.SearchHistoryEntity(query = q, at = System.currentTimeMillis()))
        }
    }
}
