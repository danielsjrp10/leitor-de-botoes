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
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Serviço de acessibilidade que roda JUNTO com o TalkBack (não substitui ele).
 *
 * Um elemento só é considerado "sem rótulo" se: não tem texto/contentDescription
 * próprios, não tem um `labeledBy` com texto, e nenhum descendente visível já
 * fornece um texto que o TalkBack leria sozinho.
 *
 * Ordem de decisão para cada elemento clicável realmente sem rótulo:
 * 1. tooltipText / hintText - fornecidos pelo próprio app, se existirem.
 * 2. resourceId conhecido - só quando um segmento do id bate exatamente com um
 *    dicionário fixo (nunca anuncia o id em si, só uma tradução conhecida).
 * 3. OCR local (ML Kit) apenas na área do elemento, como último recurso.
 *
 * A lógica de decisão "pura" (sem dependências do Android) fica em
 * LabelHeuristics.kt, e é coberta por testes automatizados.
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

    // "Cache" de um único elemento, para não repetir trabalho (nem OCR, nem
    // anúncio) se o foco voltar pro mesmo controle logo em seguida. Guarda
    // também o caso "nada encontrado" (texto null), para não tentar OCR de
    // novo à toa. Não é uma lista, não persiste em disco.
    private var lastProcessedKey: String? = null
    private var lastAnnouncedText: String? = null

    companion object {
        private const val TAG = "LabelHelperService"

        // Elementos menores que isso (em dp) são ignorados: candidatos demais
        // pequenos costumam ser marcadores invisíveis, não botões de verdade.
        private const val MIN_ELEMENT_DP = 12f

        // Limite de profundidade ao checar se algum descendente já tem texto
        // que o TalkBack leria sozinho. Suficiente para os padrões comuns
        // (ícone + texto dentro de um container clicável) sem custo alto.
        private const val MAX_DESCENDANT_DEPTH = 3
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
        } catch (e: Exception) {
            // Uma exceção inesperada aqui (ex: nó ficou inválido no meio do
            // processamento, por uma mudança rápida de tela em outro app) não
            // pode derrubar o serviço inteiro - só registra e segue.
            Log.e(TAG, "Erro inesperado ao processar elemento focado - evento ignorado", e)
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

        // Mesmo elemento de antes: reaproveita o resultado (inclusive o
        // "nada encontrado"), sem refazer OCR.
        if (key == lastProcessedKey) {
            lastAnnouncedText?.let { announce(it) }
            return
        }

        // 1) tooltipText / hintText - vêm prontos do próprio app, alta confiança.
        node.tooltipText?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            rememberResult(key, it)
            return
        }
        node.hintText?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            rememberResult(key, it)
            return
        }

        // 2) resourceId conhecido - confiança média (é uma tradução de um id interno).
        LabelHeuristics.matchKnownResourceToken(
            LabelHeuristics.tokensFromResourceId(node.viewIdResourceName ?: "")
        )?.let {
            rememberResult(key, "Provavelmente: $it")
            return
        }

        // 3) OCR local - último recurso.
        val displayId = resolveDisplayId(node)
        captureAndDescribe(bounds, key, requestId, displayId)
    }

    private fun resolveDisplayId(node: AccessibilityNodeInfo): Int {
        // Usa a tela onde a janela do elemento realmente está (relevante em
        // aparelhos dobráveis, Android Auto, ou telas externas conectadas),
        // em vez de assumir sempre a tela principal do aparelho.
        val window = node.window ?: return Display.DEFAULT_DISPLAY
        return try {
            window.displayId
        } finally {
            @Suppress("DEPRECATION")
            window.recycle()
        }
    }

    private fun elementKey(node: AccessibilityNodeInfo, bounds: Rect): String {
        val resId = node.viewIdResourceName ?: ""
        return "${node.windowId}|$resId|${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
    }

    /**
     * Um elemento só é "sem rótulo" se: não tem texto/descrição próprios, não
     * tem um `labeledBy` com texto, e nenhum descendente visível já tem texto
     * que o TalkBack leria sozinho (padrão comum: container clicável com um
     * ícone + um TextView dentro). Isso evita processar à toa - e possivelmente
     * anunciar algo redundante ou conflitante - em botões que o TalkBack já
     * sabe descrever sem ajuda nenhuma.
     */
    private fun isUnlabeled(node: AccessibilityNodeInfo): Boolean {
        val hasOwnLabel = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
        if (hasOwnLabel) return false
        if (hasLabeledByText(node)) return false
        if (hasLabeledDescendant(node)) return false
        return true
    }

    /** Verifica a relação explícita android:labelFor / labeledBy. */
    private fun hasLabeledByText(node: AccessibilityNodeInfo): Boolean {
        val labelNode = node.labeledBy ?: return false
        return try {
            !labelNode.text.isNullOrBlank() || !labelNode.contentDescription.isNullOrBlank()
        } finally {
            @Suppress("DEPRECATION")
            labelNode.recycle()
        }
    }

    /** Busca (com profundidade limitada) um filho visível e importante para acessibilidade com texto próprio. */
    private fun hasLabeledDescendant(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth >= MAX_DESCENDANT_DEPTH) return false
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (!child.isVisibleToUser) continue
                if (!child.isImportantForAccessibility) continue
                if (!child.text.isNullOrBlank() || !child.contentDescription.isNullOrBlank()) {
                    return true
                }
                if (hasLabeledDescendant(child, depth + 1)) return true
            } finally {
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }
        return false
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

    @SuppressLint("NewApi")
    private fun captureAndDescribe(bounds: Rect, key: String, requestId: Long, displayId: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        takeScreenshot(
            displayId,
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
                    val label = LabelHeuristics.isPlausibleLabel(visionText.text, areaFraction)
                    rememberResult(key, label?.let { "Provavelmente: $it" })
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

    /** Guarda o resultado (mesmo quando não há nada a anunciar) e anuncia se houver texto. */
    private fun rememberResult(key: String, text: String?) {
        lastProcessedKey = key
        lastAnnouncedText = text
        text?.let { announce(it) }
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
