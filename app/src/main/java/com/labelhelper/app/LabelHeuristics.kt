package com.labelhelper.app

/**
 * Regras de decisão "puras" do Leitor de Botões: nenhuma dependência do
 * Android aqui de propósito, para que essa lógica possa ser testada
 * isoladamente (com um compilador Kotlin comum), sem precisar de um
 * aparelho, emulador ou do SDK do Android.
 */
object LabelHeuristics {

    // Faixas de "quanto da tela o elemento ocupa" -> tamanho máximo de texto
    // aceito como rótulo plausível. É um sinal de alerta, não uma trava
    // rígida: elementos grandes continuam sendo analisados, só ficamos mais
    // exigentes com o tamanho do texto aceito.
    const val AREA_SMALL = 0.15f
    const val AREA_MEDIUM = 0.35f
    const val AREA_LARGE = 0.60f
    const val LEN_SMALL_AREA = 60
    const val LEN_MEDIUM_AREA = 45
    const val LEN_LARGE_AREA = 30
    const val LEN_HUGE_AREA = 20

    // Proporção mínima de caracteres "úteis" (letra/número/espaço) no texto
    // reconhecido. Abaixo disso, é provável que seja ruído do OCR.
    const val MIN_USEFUL_CHAR_RATIO = 0.6f

    // Dicionário fixo: só usado quando um SEGMENTO do resourceId bate
    // EXATAMENTE com uma destas chaves (nunca por conter/substring).
    val KNOWN_ID_TOKENS: Map<String, String> = mapOf(
        "back" to "Voltar",
        "close" to "Fechar",
        "cancel" to "Cancelar",
        "menu" to "Menu",
        "search" to "Buscar",
        "settings" to "Configurações",
        "delete" to "Excluir",
        "remove" to "Remover",
        "add" to "Adicionar",
        "create" to "Criar",
        "edit" to "Editar",
        "save" to "Salvar",
        "share" to "Compartilhar",
        "send" to "Enviar",
        "play" to "Reproduzir",
        "pause" to "Pausar",
        "next" to "Próximo",
        "previous" to "Anterior",
        "home" to "Início",
        "help" to "Ajuda",
        "refresh" to "Atualizar",
        "download" to "Baixar",
        "upload" to "Enviar arquivo",
        "check" to "Confirmar"
    )

    /** Extrai os "pedaços" de um resourceId (ex.: "btn_closeDialog") em tokens minúsculos. */
    fun tokensFromResourceId(resourceName: String): List<String> {
        val lastSegment = resourceName.substringAfterLast('/')
        return lastSegment
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2") // separa camelCase
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() }
    }

    /** Busca correspondência EXATA de token no dicionário - nunca por substring. */
    fun matchKnownResourceToken(tokens: List<String>): String? {
        for (token in tokens) {
            KNOWN_ID_TOKENS[token]?.let { return it }
        }
        return null
    }

    /**
     * Decide se um texto reconhecido por OCR é um rótulo plausível, usando a
     * fração da área da tela como sinal de alerta (não como bloqueio).
     * Retorna o texto limpo se for plausível, ou null se deve ser descartado.
     */
    fun isPlausibleLabel(rawText: String, areaFraction: Float): String? {
        val cleaned = rawText.trim().replace(Regex("\\s+"), " ")
        if (cleaned.isEmpty()) return null
        if (cleaned.none { it.isLetterOrDigit() }) return null

        val maxLength = when {
            areaFraction <= AREA_SMALL -> LEN_SMALL_AREA
            areaFraction <= AREA_MEDIUM -> LEN_MEDIUM_AREA
            areaFraction <= AREA_LARGE -> LEN_LARGE_AREA
            else -> LEN_HUGE_AREA
        }
        if (cleaned.length > maxLength) return null

        val usefulChars = cleaned.count { it.isLetterOrDigit() || it.isWhitespace() }
        val usefulRatio = usefulChars.toFloat() / cleaned.length
        if (usefulRatio < MIN_USEFUL_CHAR_RATIO) return null

        return cleaned
    }
}
