package com.deivid22srk.portstore.ui.you

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.deivid22srk.portstore.ui.components.AppLogo
import com.deivid22srk.portstore.ui.theme.Lime
import com.deivid22srk.portstore.ui.theme.TextSecondary

private val LEGAL_TEXT = """
    AVISO LEGAL — Port Store / Hail Games

    O Port Store é apenas uma vitrine e um gerenciador de downloads. Nenhum jogo,
    ROM, ISO ou APK é hospedado por este aplicativo — todos os links de download
    apontam para os repositórios oficiais dos autores de cada port da comunidade.

    O catálogo do aplicativo é alimentado pelo projeto port-droid
    (github.com/deivid22srk/port-droid), mantido pelo canal Hail Games.

    Não há qualquer afiliação com SEGA, EA, Microsoft, Valve, Nintendo ou qualquer
    outra empresa detentora de direitos. Todos os jogos, nomes e marcas citados
    pertencem aos seus respectivos titulares.

    Vários ports exibidos aqui são projetos open source (alguns sob licença GPL).
    Verifique os créditos e a licença de cada port na sua página. Muitos ports
    exigem que você possua a sua própria cópia legal do jogo original (dump) —
    o app e o catálogo não distribuem esses arquivos.

    Se você é um detentor de direitos e acredita que algum conteúdo listado viola
    seus direitos, entre em contato pelos canais do projeto port-droid para que o
    item seja removido.

    Canal: youtube.com/@Hail-Games1 • Telegram: t.me/hailgames2
""".trimIndent()

@Composable
fun LegalScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Voltar")
            }
            Text("Aviso legal", style = MaterialTheme.typography.titleLarge)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            LEGAL_TEXT.split("\n\n").forEach { block ->
                Text(
                    text = block,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (block.startsWith("AVISO")) Lime else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Voltar")
            }
            Text("Sobre o app", style = MaterialTheme.typography.titleLarge)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            AppLogo()
            Spacer(Modifier.height(12.dp))
            Text("Port Store", style = MaterialTheme.typography.headlineSmall)
            Text("1.0.0", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Spacer(Modifier.height(20.dp))
            Text(
                "Loja/baixador de ports de jogos da comunidade, com catálogo do projeto port-droid (Hail Games) e motor de download em Rust com múltiplas conexões, retomada robusta e fila em segundo plano.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "UI inspirada na Google Play Store. Feito por deivid22srk.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
    }
}
