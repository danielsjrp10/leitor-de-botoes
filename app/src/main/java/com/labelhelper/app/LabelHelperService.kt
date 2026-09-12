package com.labelhelper.app

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Serviço de acessibilidade que roda JUNTO com o TalkBack (não substitui ele).
 *
 * Ordem de decisão para cada elemento clicável sem rótulo:
 * 1. tooltipText / hintText - fornecidos pelo próprio app, se existirem.
 * 2. resourceId conhecido - só quando um segmento do id bate exatamente com um
 *    dicionário fixo (nunca anuncia o id em si, só uma tradução conhecida).
 * 3. OCR local (ML Kit) apenas na área do elemento, como último recurso.
 *
 * Nenhuma imagem sai do aparelho. Nada é salvo em disco.
 */
class LabelHelperService : AccessibilityService() {

    private val executor = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // Cada novo elemento focado incrementa esse contador. Se um resultado
    // assíncrono (screenshot ou OCR) voltar depois que o foco já mudou de novo,
    // o número não vai bater mais e o resultado é descartado.
    private val requestSequence = AtomicLong(0)

    // "Cache" de um único elemento, para não repetir OCR se o foco voltar pro
    // mesmo controle logo em seguida. Não é uma lista, não persiste em disco.
    private var lastProcessedKey: String? = null
    private var lastAnnouncedText: String? = null

    companion object {
        private const val TAG = "LabelHelperService"

        // Faixas de "quanto da tela o elemento ocupa" -> tamanho máximo de texto
        // aceito como rótulo plausível. Não é uma trava rígida: elementos grandes
        // continuam sendo analisados, só ficamos mais exigentes com o tamanho do
        // texto aceito, porque um contêiner enorme marcado como clicável por engano
        // tende a devolver um bloco de texto longo, enquanto um botão grande
        // legítimo (ex.: um botão "Continuar" de largura total) normalmente ainda
        // tem um texto curto.
        private const val AREA_SMALL = 0.15f
        private const val AREA_MEDIUM = 0.35f
        private const val AREA_LARGE = 0.60f
        private const val LEN_SMALL_AREA = 60
        private const val LEN_MEDIUM_AREA = 45
        private const val LEN_LARGE_AREA = 30
        private const val LEN_HUGE_AREA = 20

        // Proporção mínima de caracteres "úteis" (letra/número/espaço) no texto
        // reconhecido. Abaixo disso, é provável que seja ruído do OCR.
        private const val MIN_USEFUL_CHAR_RATIO = 0.6f

        // Elementos menores que isso (em dp) são ignorados: candidatos demais
        // pequenos costumam ser marcadores invisíveis, não botões de verdade.
        private const val MIN_ELEMENT_DP = 12f

        // Dicionário fixo: só usado quando um SEGMENTO do resourceId bate
        // EXATAMENTE com uma destas chaves (nunca por conter/substring), e o
        // que é anunciado é sempre a tradução - nunca o id original.
        private val KNOWN_ID_TOKENS = mapOf(
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
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        recognizer.close()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) return

        val node = event.source ?: return
        val requestId = requestSequence.incrementAndGet()
        try {
            handleFocusedNode(node, requestId)
        } finally {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    private fun handleFocusedNode(node: AccessibilityNodeInfo, requestId: Long) {
        if (!isUnlabeled(node)) return
        if (!isTrulyInteractive(node)) return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return

        val density = resources.displayMetrics.density
        val minSizePx = (MIN_ELEMENT_DP * density)
        if (bounds.width() < minSizePx || bounds.height() < minSizePx) return

        val key = elementKey(node, bounds)

        // Mesmo elemento de antes: reaproveita o resultado, sem refazer OCR.
        if (key == lastProcessedKey && lastAnnouncedText != null) {
            announce(lastAnnouncedText!!)
            return
        }

        // 1) tooltipText / hintText - vêm prontos do próprio app, alta confiança.
        node.tooltipText?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            rememberAndAnnounce(key, it)
            return
        }
        node.hintText?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            rememberAndAnnounce(key, it)
            return
        }

        // 2) resourceId conhecido - confiança média (é uma tradução de um id interno).
        lookupKnownResourceId(node)?.let {
            rememberAndAnnounce(key, "Provavelmente: $it")
            return
        }

        // 3) OCR local - último recurso.
        captureAndDescribe(bounds, key, requestId)
    }

    private fun elementKey(node: AccessibilityNodeInfo, bounds: Rect): String {
        val resId = node.viewIdResourceName ?: ""
        return "${node.windowId}|$resId|${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
    }

    private fun isUnlabeled(node: AccessibilityNodeInfo): Boolean {
        val hasText = !node.text.isNullOrBlank()
        val hasDescription = !node.contentDescription.isNullOrBlank()
        return !hasText && !hasDescription
    }

    /**
     * Só considera "interativo" com base em sinais reais de ação (clicável,
     * clicável-longo, ou tem a ação ACTION_CLICK disponível) - nunca pelo nome
     * da classe. Uma ImageView puramente decorativa não deve disparar OCR.
     */
    private fun isTrulyInteractive(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable || node.isLongClickable) return true
        return node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.id }
    }

    /** Busca um rótulo conhecido a partir do resourceId, por correspondência exata de token. */
    private fun lookupKnownResourceId(node: AccessibilityNodeInfo): String? {
        val resourceName = node.viewIdResourceName ?: return null
        val lastSegment = resourceName.substringAfterLast('/')
        val tokens = lastSegment
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2") // separa camelCase
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() }
        for (token in tokens) {
            KNOWN_ID_TOKENS[token]?.let { return it }
        }
        return null
    }

    @SuppressLint("NewApi")
    private fun captureAndDescribe(bounds: Rect, key: String, requestId: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            executor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hardwareBuffer = result.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, result.colorSpace)
                    hardwareBuffer.close()

                    if (bitmap == null || requestId != requestSequence.get()) {
                        // Ou falhou, ou o foco já mudou - descarta sem anunciar.
                        bitmap?.recycle()
                        return
                    }
                    processCroppedBitmap(bitmap, bounds, key, requestId)
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "Falha ao capturar a tela: código $errorCode")
                }
            }
        )
    }

    private fun processCroppedBitmap(fullBitmap: Bitmap, bounds: Rect, key: String, requestId: Long) {
        val screenArea = fullBitmap.width.toFloat() * fullBitmap.height.toFloat()
        try {
            val safeRect = Rect(
                bounds.left.coerceIn(0, fullBitmap.width),
                bounds.top.coerceIn(0, fullBitmap.height),
                bounds.right.coerceIn(0, fullBitmap.width),
                bounds.bottom.coerceIn(0, fullBitmap.height)
            )
            if (safeRect.width() <= 0 || safeRect.height() <= 0) {
                fullBitmap.recycle()
                return
            }

            val elementArea = safeRect.width().toFloat() * safeRect.height().toFloat()
            val areaFraction = if (screenArea > 0f) elementArea / screenArea else 0f

            val cropped = Bitmap.createBitmap(
                fullBitmap, safeRect.left, safeRect.top, safeRect.width(), safeRect.height()
            )
            // A partir daqui só precisamos do recorte - libera a screenshot inteira já.
            fullBitmap.recycle()

            val image = InputImage.fromBitmap(cropped, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (requestId != requestSequence.get()) return@addOnSuccessListener
                    extractPlausibleLabel(visionText, areaFraction)?.let { label ->
                        rememberAndAnnounce(key, "Provavelmente: $label")
                    }
                    // Se não achou nada confiável, fica em silêncio - não anuncia ruído.
                }
                .addOnFailureListener { error ->
                    Log.w(TAG, "Erro no reconhecimento de texto", error)
                }
                .addOnCompleteListener {
                    // Roda sempre, com sucesso ou erro - só aqui é seguro reciclar,
                    // porque o ML Kit já terminou de usar o bitmap.
                    cropped.recycle()
                }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao processar a imagem recortada", e)
            if (!fullBitmap.isRecycled) fullBitmap.recycle()
        }
    }

    /**
     * Decide se o texto reconhecido é um rótulo plausível, usando a fração da
     * área da tela como sinal de alerta (não como bloqueio): quanto maior a
     * área do elemento, mais rigoroso o limite de tamanho de texto aceito.
     */
    private fun extractPlausibleLabel(visionText: Text, areaFraction: Float): String? {
        val cleaned = visionText.text.trim().replace(Regex("\\s+"), " ")
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

    private fun rememberAndAnnounce(key: String, text: String) {
        lastProcessedKey = key
        lastAnnouncedText = text
        announce(text)
    }

    /** Envia um anúncio de voz que entra no fluxo do TalkBack. */
    private fun announce(text: String) {
        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return
        if (!manager.isEnabled) return

        @Suppress("DEPRECATION")
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT)
        event.text.add(text)
        event.packageName = packageName
        event.className = javaClass.name
        @Suppress("DEPRECATION")
        manager.sendAccessibilityEvent(event)
    }

    override fun onInterrupt() {
        Log.i(TAG, "Leitor de Botões interrompido.")
    }
}
