package com.labelhelper.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView

/**
 * Tela única do app: explica o que ele faz, mostra se o serviço já está
 * ativado, e leva direto para a tela do Android onde se ativa o serviço
 * (isso não pode ser feito automaticamente, por segurança do próprio Android).
 *
 * Usa Views tradicionais (sem Jetpack Compose) - a tela é simples o
 * suficiente para não precisar dessa dependência, e isso reduz bastante o
 * tamanho final do APK. Funciona normalmente com o TalkBack, como qualquer
 * tela Android padrão.
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.status)
        findViewById<Button>(R.id.open_settings_button).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        // Atualiza sempre que a tela volta a aparecer (ex: ao voltar das
        // configurações depois de ativar ou desativar o serviço).
        statusView.text = if (isServiceEnabled()) {
            getString(R.string.status_enabled)
        } else {
            getString(R.string.status_disabled)
        }
    }

    private fun isServiceEnabled(): Boolean {
        val expected = ComponentName(this, LabelHelperService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
