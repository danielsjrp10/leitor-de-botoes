package com.labelhelper.app

import android.app.Activity
import android.os.Bundle

/**
 * Tela de diagnóstico. Não faz parte da funcionalidade principal do app -
 * existe só para verificar, com casos conhecidos, se o serviço de
 * acessibilidade está realmente processando os eventos, sem depender de
 * apps de terceiros (onde nunca dá pra saber com certeza o que deveria
 * acontecer).
 *
 * Três cenários, navegando com o TalkBack:
 * 1. Um botão com texto real desenhado na tela mas escondido do leitor de
 *    tela -> só o OCR pode encontrar isso. Esperado: "Provavelmente: Teste
 *    um dois tres".
 * 2. Um botão sem texto nenhum, mas com um resourceId conhecido ("close")
 *    -> não precisa de OCR. Esperado: "Provavelmente: Fechar".
 * 3. Um botão cujo texto já é visível para o leitor de tela (o TalkBack já
 *    lê sozinho) -> o Leitor de Botões deve ficar em silêncio.
 */
class TestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test)
    }
}
