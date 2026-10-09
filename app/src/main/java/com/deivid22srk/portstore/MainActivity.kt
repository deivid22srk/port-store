package com.deivid22srk.portstore

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deivid22srk.portstore.setup.SetupFlow
import com.deivid22srk.portstore.ui.nav.AppRoot
import com.deivid22srk.portstore.ui.theme.Lime
import com.deivid22srk.portstore.ui.theme.PortStoreTheme
import com.deivid22srk.portstore.update.SelfUpdateDialog
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val startTab = intent?.getStringExtra("tab") ?: "home"

        setContent {
            val settings by AppGraph.settings.settings.collectAsStateWithLifecycle()
            PortStoreTheme(themeMode = settings.theme) {
                AppScaffold(startTab = startTab)
            }
        }
    }
}

@Composable
private fun AppScaffold(startTab: String) {
    val settings by AppGraph.settings.settings.collectAsStateWithLifecycle()
    val setupSession by AppGraph.settings.setupSession.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Atualiza estados de instalação ao voltar para o app (ex.: após instalar um port).
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) AppGraph.installedApps.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when {
        // Aguarda o DataStore carregar (evita piscar o setup em quem já concluiu).
        !settings.bootReady -> Unit

        // Primeira abertura ou setup reaberto de "Você" -> assistente em etapas.
        !settings.setupCompleted || setupSession -> SetupFlow(
            onFinished = { AppGraph.settings.finishSetupSession() },
        )

        // App principal.
        else -> {
            // Atualização do próprio app: no máximo 1x a cada 6 h.
            val selfUpdateState by AppGraph.selfUpdate.state.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { AppGraph.selfUpdate.maybeAutoCheck() }

            Box(modifier = Modifier.fillMaxSize()) {
                AppRoot(startTab = startTab)

                // Aviso legal exibido na primeira entrada no app.
                if (!settings.disclaimerAccepted) {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text("Aviso legal") },
                        text = {
                            Text(
                                text = "O Port Store é apenas uma vitrine e um gerenciador de downloads. " +
                                    "Nenhum jogo, ROM, ISO ou APK é hospedado por este app — todos os downloads " +
                                    "apontam para os repositórios oficiais dos autores de cada port. " +
                                    "Sem afiliação com SEGA, EA, Microsoft, Valve ou qualquer outra empresa. " +
                                    "Alguns ports exigem a sua própria cópia legal do jogo original.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    scope.launch { AppGraph.settings.setDisclaimerAccepted(true) }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Color(0xFF171800)),
                            ) {
                                Text("Entendi")
                            }
                        },
                    )
                }
            }

            // Diálogo de atualização do próprio app (disponível / baixando).
            SelfUpdateDialog(
                state = selfUpdateState,
                onUpdate = { AppGraph.selfUpdate.startDownload(it) },
                onCancelDownload = { AppGraph.selfUpdate.cancelDownload() },
                onDismiss = { AppGraph.selfUpdate.dismissOffer() },
            )
        }
    }
}
