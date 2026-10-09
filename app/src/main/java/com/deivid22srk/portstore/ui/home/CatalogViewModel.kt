package com.deivid22srk.portstore.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deivid22srk.portstore.catalog.Catalog
import com.deivid22srk.portstore.catalog.CatalogRepository
import com.deivid22srk.portstore.catalog.RefreshResult
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
    val backgroundUpdated: Boolean = false,
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
        // Abre com o cache (offline-first) e atualiza em segundo plano.
        viewModelScope.launch {
            repo.catalog.collect { catalog ->
                if (catalog != null) {
                    _state.value = _state.value.copy(
                        loading = false,
                        catalog = catalog,
                        error = null,
                    )
                }
            }
        }
        refresh(pull = false)
    }

    fun selectChip(chip: String) {
        _selectedChip.value = chip
    }

    fun refresh(pull: Boolean = true) {
        viewModelScope.launch {
            if (pull) _state.value = _state.value.copy(refreshing = true)
            try {
                val hadCatalog = repo.catalog.value != null
                val result: RefreshResult = repo.refreshAll(force = pull)
                _state.value = _state.value.copy(
                    loading = false,
                    refreshing = false,
                    error = when {
                        result.allFailed && !result.hasCache -> "Não foi possível carregar os repositórios. Verifique sua conexão."
                        else -> null
                    },
                    fromCache = result.allFailed && result.hasCache,
                    backgroundUpdated = !pull && hadCatalog && !result.allFailed,
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
