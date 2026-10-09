package com.deivid22srk.portstore.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deivid22srk.portstore.ui.theme.TextSecondary

/**
 * Tela autônoma de gerenciamento dos repositórios do catálogo
 * (rota "repositorios", aberta pela aba "Você").
 *
 * Reaproveita o RepositoriesStep do assistente e o SetupViewModel. O ViewModel
 * tem escopo da Activity e é compartilhado com o assistente de setup — o estado
 * dos repositórios vem do CatalogRepository de qualquer forma, então a lista,
 * os diálogos e as ações funcionam iguais nos dois lugares.
 */
@Composable
fun RepositoriesScreen(onBack: () -> Unit) {
    val vm: SetupViewModel = viewModel {
        SetupViewModel(
            catalog = com.deivid22srk.portstore.AppGraph.catalog,
            settings = com.deivid22srk.portstore.AppGraph.settings,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()

    // VM compartilhado: limpa resíduos de sessões anteriores (diálogo aberto,
    // flags de carregamento do setup) ao entrar na tela.
    LaunchedEffect(Unit) { vm.resetSessionState() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Voltar")
            }
            Text("Repositórios", style = MaterialTheme.typography.titleLarge)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(14.dp))
        Text(
            "Os repositórios fornecem os dados dos jogos (título, capas, screenshots e links). " +
                "O Port DB é a fonte oficial e já vem ativado.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(14.dp))
        RepositoriesStep(
            state = state,
            vm = vm,
            modifier = Modifier.weight(1f),
            showHeader = false,
        )
    }
}
