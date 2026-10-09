package com.deivid22srk.portstore

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deivid22srk.portstore.ui.nav.AppRoot
import com.deivid22srk.portstore.ui.theme.Lime
import com.deivid22srk.portstore.ui.theme.PortStoreTheme
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
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val activityContext = androidx.compose.ui.platform.LocalContext.current

    // Pede permissão de notificações (Android 13+) na primeira abertura.
    var askedNotifications by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !askedNotifications) {
            askedNotifications = true
            val granted = ContextCompat.checkSelfPermission(
                activityContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AppRoot(startTab = startTab)

        // Aviso legal exibido na primeira abertura.
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
}
