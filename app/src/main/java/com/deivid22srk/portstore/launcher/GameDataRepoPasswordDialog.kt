package com.deivid22srk.portstore.launcher

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.deivid22srk.portstore.AppGraph
import com.deivid22srk.portstore.R
import kotlinx.coroutines.launch

/**
 * Diálogo de ATIVAÇÃO do repositório de dados dos jogos: pede a senha do
 * repositório, valida contra o verificador PBKDF2 do index.json (rede) e
 * só persiste a flag+senha no sucesso. Navegável por gamepad (campo de
 * texto abre o teclado virtual; A confirma).
 */
@Composable
fun GameDataRepoPasswordDialog(
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var activating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!activating) onDismiss() },
        title = { Text(stringResource(R.string.data_repo_dialog_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(stringResource(R.string.data_repo_dialog_message))
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        error = false
                    },
                    singleLine = true,
                    isError = error,
                    enabled = !activating,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text(stringResource(R.string.data_repo_dialog_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
                if (error) {
                    Text(
                        stringResource(R.string.data_repo_dialog_error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else if (activating) {
                    Text(
                        stringResource(R.string.data_repo_activating),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !activating && password.isNotBlank(),
                onClick = {
                    activating = true
                    scope.launch {
                        val ok = runCatching {
                            AppGraph.dataRepo.activate(password)
                        }.isSuccess
                        if (ok) {
                            AppGraph.settings.setDataRepoPassword(password)
                            AppGraph.settings.setDataRepoEnabled(true)
                            onDismiss()
                        } else {
                            error = true
                            activating = false
                        }
                    }
                },
            ) { Text(stringResource(R.string.data_repo_dialog_confirm)) }
        },
        dismissButton = {
            TextButton(enabled = !activating, onClick = onDismiss) {
                Text(stringResource(R.string.data_repo_dialog_cancel))
            }
        },
    )
}
