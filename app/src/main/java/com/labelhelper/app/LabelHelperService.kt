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

/**
 * Serviço de acessibilidade que roda JUNTO com o TalkBack (não substitui ele).
 *
 * Fluxo:
 * 1. O usuário navega normalmente com o TalkBack.
 * 2. Quando o foco de acessibilidade cai em um elemento clicável SEM texto e
 *    SEM contentDescription, este serviço entra em ação.
 * 3. Ele tira um "print" só daquela área da tela (API disponível a partir do
 *    Android 11) e roda reconhecimento de texto (OCR) nela, tudo localmente
 *    no aparelho — nada é enviado para a internet.
 * 4. Se achar texto, anuncia por voz: "Provavelmente: <texto>".
 *    Se não achar nada (provável ícone puro), avisa que é um ícone sem rótulo.
 */
class LabelHelperService : AccessibilityService() {

    private val executor = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    companion object {
        private const val TAG = "LabelHelperService"

        // Tipos de elemento que costumam ser botões/controles interativos.
        private val CLICKABLE_HINTS = listOf(
            "Button", "ImageButton", "ImageView", "CheckBox", "Switch", "RadioButton"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Leitor de Botões conectado e ativo.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) return

        val node = event.source ?: return
        try {
            if (!isUnlabeled(node)) return
            if (!looksInteractive(node)) return

            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() <= 0 || bounds.height() <= 0) return

            captureAndDescribe(bounds)
        } finally {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    /** true se o elemento não tem nem texto visível, nem descrição para leitor de tela. */
    private fun isUnlabeled(node: AccessibilityNodeInfo): Boolean {
        val hasText = !node.text.isNullOrBlank()
        val hasDescription = !node.contentDescription.isNullOrBlank()
        return !hasText && !hasDescription
    }

    /** true se o elemento parece ser algo em que dá pra tocar. */
    private fun looksInteractive(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable || node.isLongClickable) return true
        val className = node.className?.toString() ?: return false
        return CLICKABLE_HINTS.any { className.contains(it) }
    }

    @SuppressLint("NewApi")
    private fun captureAndDescribe(bounds: Rect) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            announce("Elemento sem rótulo detectado. Este recurso precisa do Android 11 ou mais novo.")
            return
        }

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            executor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hardwareBuffer = result.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, result.colorSpace)
                    hardwareBuffer.close()
                    if (bitmap == null) {
                        announce("Botão sem rótulo. Não foi possível analisar a imagem.")
                        return
                    }
                    processCroppedBitmap(bitmap, bounds)
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "Falha ao capturar a tela: código $errorCode")
                    announce("Botão sem rótulo detectado.")
                }
            }
        )
    }

    private fun processCroppedBitmap(fullBitmap: Bitmap, bounds: Rect) {
        try {
            val safeRect = Rect(
                bounds.left.coerceIn(0, fullBitmap.width),
                bounds.top.coerceIn(0, fullBitmap.height),
                bounds.right.coerceIn(0, fullBitmap.width),
                bounds.bottom.coerceIn(0, fullBitmap.height)
            )
            if (safeRect.width() <= 0 || safeRect.height() <= 0) {
                announce("Botão sem rótulo detectado.")
                return
            }

            val cropped = Bitmap.createBitmap(
                fullBitmap, safeRect.left, safeRect.top, safeRect.width(), safeRect.height()
            )
            val image = InputImage.fromBitmap(cropped, 0)

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val text = visionText.text.trim()
                    if (text.isNotEmpty()) {
                        announce("Provavelmente: $text")
                    } else {
                        announce("Botão sem rótulo, sem texto identificado. Pode ser um ícone.")
                    }
                }
                .addOnFailureListener { error ->
                    Log.w(TAG, "Erro no reconhecimento de texto", error)
                    announce("Botão sem rótulo detectado.")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao processar a imagem recortada", e)
            announce("Botão sem rótulo detectado.")
        }
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
