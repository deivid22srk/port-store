package com.deivid22srk.portstore.setup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deivid22srk.portstore.AppGraph

/**
 * Tela autônoma de gerenciamento dos repositórios (rota "repositorios",
 * aberta pela aba "Você"). Reaproveita o conteúdo da etapa 4 do assistente
 * ([RepositoriesStepContent]) e o SetupViewModel: repos de jogos + dados
 * complementares (exportar/importar via SAF). O ViewModel tem escopo da
 * Activity e é compartilhado com o assistente de setup.
 */
@Composable
fun RepositoriesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: SetupViewModel = viewModel {
        SetupViewModel(
            catalog = AppGraph.catalog,
            settings = AppGraph.settings,
            dataBackup = ComplementaryDataBackupManager(
                context = context.applicationContext,
                db = AppGraph.db,
            ),
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()

    // VM compartilhado: limpa resíduos de sessões anteriores (diálogo aberto,
    // flags de carregamento do setup) ao entrar na tela.
    LaunchedEffect(Unit) { vm.resetSessionState() }

    // SAF: exportação cria arquivo novo; importação abre arquivo existente.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ComplementaryDataFormat.MIME_TYPE),
    ) { uri -> uri?.let(vm::exportDataRepoTo) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::prepareDataRepoImport) }

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
        Spacer(Modifier.height(6.dp))
        RepositoriesStepContent(
            state = state,
            gamer = false,
            onExportDataRepo = {
                runCatching { exportLauncher.launch(ComplementaryDataFormat.suggestedFileName()) }
            },
            onImportDataRepo = {
                runCatching { importLauncher.launch(arrayOf(ComplementaryDataFormat.MIME_TYPE)) }
            },
            vm = vm,
            showConfigureLater = false,
        )
    }
}
