package com.deivid22srk.portstore.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deivid22srk.portstore.catalog.Catalog
import com.deivid22srk.portstore.catalog.CatalogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CatalogUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val catalog: Catalog? = null,
    val fromCache: Boolean = false,
    val error: String? = null,
)

/** Chips da Home. */
object Chips {
    const val EM_ALTA = "em-alta"
    const val NOVOS = "novos"
}

/** ViewModel compartilhado pelas abas Início e Jogos (com modo diferente). */
class CatalogViewModel(
    private val repo: CatalogRepository,
    private val mode: Mode = Mode.HOME,
) : ViewModel() {

    enum class Mode { HOME, GAMES }

    private val _state = MutableStateFlow(CatalogUiState())
    val state: StateFlow<CatalogUiState> = _state.asStateFlow()

    private val _selectedChip = MutableStateFlow(Chips.EM_ALTA)
    val selectedChip: StateFlow<String> = _selectedChip.asStateFlow()

    init {
        refresh(pull = false)
    }

    fun selectChip(chip: String) {
        _selectedChip.value = chip
    }

    fun refresh(pull: Boolean = true) {
        viewModelScope.launch {
            if (pull) _state.value = _state.value.copy(refreshing = true)
            try {
                val load = repo.load(force = pull)
                _state.value = CatalogUiState(
                    loading = false,
                    refreshing = false,
                    catalog = load.catalog,
                    fromCache = load.fromCache,
                    error = null,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    refreshing = false,
                    error = e.message ?: "Erro ao carregar o catálogo",
                )
            }
        }
    }
}
