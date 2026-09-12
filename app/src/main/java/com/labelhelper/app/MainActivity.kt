package com.labelhelper.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Tela única do app. Sua única função é explicar o que o app faz e levar o
 * usuário direto para a tela do Android onde ele ativa o serviço de
 * acessibilidade (isso não pode ser feito automaticamente, é uma exigência
 * de segurança do próprio Android).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen(onOpenSettings = { openAccessibilitySettings() })
                }
            }
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}

@Composable
fun HomeScreen(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Leitor de Botões",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Este app trabalha junto com o TalkBack. Quando você navegar até um " +
                "botão ou ícone sem rótulo em qualquer outro aplicativo, ele tenta " +
                "identificar o que é e anuncia isso por voz.\n\n" +
                "Para ativar, toque no botão abaixo e ligue o \"Leitor de Botões\" na " +
                "lista de serviços de acessibilidade."
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onOpenSettings) {
            Text("Abrir configurações de acessibilidade")
        }
    }
}
